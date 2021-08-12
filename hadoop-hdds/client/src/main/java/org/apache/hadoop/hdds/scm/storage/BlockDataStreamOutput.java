/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.hadoop.hdds.scm.storage;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import io.netty.buffer.ByteBuf;
import org.apache.hadoop.hdds.client.BlockID;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.BlockData;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.ChunkInfo;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.KeyValue;
import org.apache.hadoop.hdds.ratis.ContainerCommandRequestMessage;
import org.apache.hadoop.hdds.scm.OzoneClientConfig;
import org.apache.hadoop.hdds.scm.XceiverClientFactory;
import org.apache.hadoop.hdds.scm.XceiverClientRatis;
import org.apache.hadoop.hdds.scm.XceiverClientReply;
import org.apache.hadoop.hdds.scm.container.common.helpers.StorageContainerException;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline;
import org.apache.hadoop.ozone.common.Checksum;
import org.apache.hadoop.ozone.common.ChecksumData;
import org.apache.hadoop.ozone.common.ChunkBuffer;
import org.apache.hadoop.ozone.common.OzoneChecksumException;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.ratis.client.api.DataStreamOutput;
import org.apache.ratis.io.StandardWriteOption;
import org.apache.ratis.protocol.DataStreamReply;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.apache.hadoop.hdds.scm.storage.ContainerProtocolCalls.putBlockAsync;

/**
 * An {@link ByteBufStreamOutput} used by the REST service in combination
 * with the SCMClient to write the value of a key to a sequence
 * of container chunks.  Writes are buffered locally and periodically written to
 * the container as a new chunk.  In order to preserve the semantics that
 * replacement of a pre-existing key is atomic, each instance of the stream has
 * an internal unique identifier.  This unique identifier and a monotonically
 * increasing chunk index form a composite key that is used as the chunk name.
 * After all data is written, a putKey call creates or updates the corresponding
 * container key, and this call includes the full list of chunks that make up
 * the key data.  The list of chunks is updated all at once.  Therefore, a
 * concurrent reader never can see an intermediate state in which different
 * chunks of data from different versions of the key data are interleaved.
 * This class encapsulates all state management for buffering and writing
 * through to the container.
 */
public class BlockDataStreamOutput implements ByteBufStreamOutput {
  public static final Logger LOG =
      LoggerFactory.getLogger(BlockDataStreamOutput.class);
  public static final String EXCEPTION_MSG =
      "Unexpected Storage Container Exception: ";
  private static final CompletableFuture[] EMPTY_FUTURE_ARRAY = {};

  private AtomicReference<BlockID> blockID;

  private final BlockData.Builder containerBlockData;
  private XceiverClientFactory xceiverClientFactory;
  private XceiverClientRatis xceiverClient;
  private OzoneClientConfig config;

  private int chunkIndex;
  private final AtomicLong chunkOffset = new AtomicLong();
  private final BufferPool bufferPool;
  // The IOException will be set by response handling thread in case there is an
  // exception received in the response. If the exception is set, the next
  // request will fail upfront.
  private final AtomicReference<IOException> ioException;
  private final ExecutorService responseExecutor;

  // the effective length of data flushed so far
  private long totalDataFlushedLength;

  // effective data write attempted so far for the block
  private long writtenDataLength;

  // List containing buffers for which the putBlock call will
  // update the length in the datanodes. This list will just maintain
  // references to the buffers in the BufferPool which will be cleared
  // when the watchForCommit acknowledges a putBlock logIndex has been
  // committed on all datanodes. This list will be a  place holder for buffers
  // which got written between successive putBlock calls.
  private List<ChunkBuffer> bufferList;

  // This object will maintain the commitIndexes and byteBufferList in order
  // Also, corresponding to the logIndex, the corresponding list of buffers will
  // be released from the buffer pool.
  private final CommitWatcher commitWatcher;

  private final List<DatanodeDetails> failedServers;
  private final Checksum checksum;

  //number of buffers used before doing a flush/putBlock.
  private int flushPeriod;
  //bytes remaining to write in the current buffer.
  private int currentBufferRemaining;
  //current buffer allocated to write
  private ChunkBuffer currentBuffer;
  private final Token<? extends TokenIdentifier> token;
  private final DataStreamOutput out;
  private CompletableFuture<DataStreamReply> dataStreamCloseReply;
  private List<CompletableFuture<DataStreamReply>> futures = new ArrayList<>();
  private final long syncSize = 0; // TODO: disk sync is disabled for now
  private long syncPosition = 0;

  /**
   * Creates a new BlockDataStreamOutput.
   *
   * @param blockID              block ID
   * @param xceiverClientManager client manager that controls client
   * @param pipeline             pipeline where block will be written
   * @param bufferPool           pool of buffers
   */
  public BlockDataStreamOutput(
      BlockID blockID,
      XceiverClientFactory xceiverClientManager,
      Pipeline pipeline,
      BufferPool bufferPool,
      OzoneClientConfig config,
      Token<? extends TokenIdentifier> token
  ) throws IOException {
    this.xceiverClientFactory = xceiverClientManager;
    this.config = config;
    this.blockID = new AtomicReference<>(blockID);
    KeyValue keyValue =
        KeyValue.newBuilder().setKey("TYPE").setValue("KEY").build();
    this.containerBlockData =
        BlockData.newBuilder().setBlockID(blockID.getDatanodeBlockIDProtobuf())
            .addMetadata(keyValue);
    this.xceiverClient =
        (XceiverClientRatis)xceiverClientManager.acquireClient(pipeline);
    // Alternatively, stream setup can be delayed till the first chunk write.
    this.out = setupStream();
    this.bufferPool = bufferPool;
    this.token = token;

    //number of buffers used before doing a flush
    refreshCurrentBuffer(bufferPool);
    flushPeriod = (int) (config.getStreamBufferFlushSize() / config
        .getStreamBufferSize());

    Preconditions
        .checkArgument(
            (long) flushPeriod * config.getStreamBufferSize() == config
                .getStreamBufferFlushSize());

    // A single thread executor handle the responses of async requests
    responseExecutor = Executors.newSingleThreadExecutor();
    commitWatcher = new CommitWatcher(bufferPool, xceiverClient);
    bufferList = null;
    totalDataFlushedLength = 0;
    writtenDataLength = 0;
    failedServers = new ArrayList<>(0);
    ioException = new AtomicReference<>(null);
    checksum = new Checksum(config.getChecksumType(),
        config.getBytesPerChecksum());
  }

  private DataStreamOutput setupStream() throws IOException {
    // Execute a dummy WriteChunk request to get the path of the target file,
    // but does NOT write any data to it.
    ContainerProtos.WriteChunkRequestProto.Builder writeChunkRequest =
        ContainerProtos.WriteChunkRequestProto.newBuilder()
            .setBlockID(blockID.get().getDatanodeBlockIDProtobuf());

    String id = xceiverClient.getPipeline().getFirstNode().getUuidString();
    ContainerProtos.ContainerCommandRequestProto.Builder builder =
        ContainerProtos.ContainerCommandRequestProto.newBuilder()
            .setCmdType(ContainerProtos.Type.StreamInit)
            .setContainerID(blockID.get().getContainerID())
            .setDatanodeUuid(id).setWriteChunk(writeChunkRequest);

    ContainerCommandRequestMessage message =
        ContainerCommandRequestMessage.toMessage(builder.build(), null);

    return Preconditions.checkNotNull(xceiverClient.getDataStreamApi())
        .stream(message.getContent().asReadOnlyByteBuffer());
  }

  private void refreshCurrentBuffer(BufferPool pool) {
    currentBuffer = pool.getCurrentBuffer();
    currentBufferRemaining =
        currentBuffer != null ? currentBuffer.remaining() : 0;
  }

  public BlockID getBlockID() {
    return blockID.get();
  }

  public long getTotalAckDataLength() {
    return commitWatcher.getTotalAckDataLength();
  }

  public long getWrittenDataLength() {
    return writtenDataLength;
  }

  public List<DatanodeDetails> getFailedServers() {
    return failedServers;
  }

  @VisibleForTesting
  public XceiverClientRatis getXceiverClient() {
    return xceiverClient;
  }

  @VisibleForTesting
  public long getTotalDataFlushedLength() {
    return totalDataFlushedLength;
  }

  @VisibleForTesting
  public BufferPool getBufferPool() {
    return bufferPool;
  }

  public IOException getIoException() {
    return ioException.get();
  }

  @VisibleForTesting
  public Map<Long, List<ChunkBuffer>> getCommitIndex2flushedDataMap() {
    return commitWatcher.getCommitIndex2flushedDataMap();
  }

  @Override
  public void write(ByteBuf b) throws IOException {
    checkOpen();
    if (b == null) {
      throw new NullPointerException();
    }
    int off = b.readerIndex();
    int len = b.readableBytes();

    while (len > 0) {
      allocateNewBufferIfNeeded();
      final int writeLen = Math.min(currentBufferRemaining, len);
      // TODO: avoid buffer copy here
      currentBuffer.put(b.nioBuffer(off, writeLen));
      currentBufferRemaining -= writeLen;
      writeChunkIfNeeded();
      off += writeLen;
      len -= writeLen;
      writtenDataLength += writeLen;
      doFlushOrWatchIfNeeded();
    }
  }

  private void writeChunkIfNeeded() throws IOException {
    if (currentBufferRemaining == 0) {
      writeChunk(currentBuffer);
    }
  }

  private void doFlushOrWatchIfNeeded() throws IOException {
    if (currentBufferRemaining == 0) {
      if (bufferPool.getNumberOfUsedBuffers() % flushPeriod == 0) {
        updateFlushLength();
        executePutBlock(false, false);
      }
      // Data in the bufferPool can not exceed streamBufferMaxSize
      if (bufferPool.getNumberOfUsedBuffers() == bufferPool.getCapacity()) {
        handleFullBuffer();
      }
    }
  }

  private void allocateNewBufferIfNeeded() {
    if (currentBufferRemaining == 0) {
      currentBuffer = bufferPool.allocateBuffer(config.getBufferIncrement());
      currentBufferRemaining = currentBuffer.remaining();
    }
  }

  private void updateFlushLength() {
    totalDataFlushedLength = writtenDataLength;
  }

  private boolean isBufferPoolFull() {
    return bufferPool.computeBufferData() == config.getStreamBufferMaxSize();
  }

  /**
   * Will be called on the retryPath in case closedContainerException/
   * TimeoutException.
   * @param len length of data to write
   * @throws IOException if error occurred
   */

  // In this case, the data is already cached in the currentBuffer.
  public void writeOnRetry(long len) throws IOException {
    if (len == 0) {
      return;
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("Retrying write length {} for blockID {}", len, blockID);
    }
    Preconditions.checkArgument(len <= config.getStreamBufferMaxSize());
    int count = 0;
    while (len > 0) {
      ChunkBuffer buffer = bufferPool.getBuffer(count);
      long writeLen = Math.min(buffer.position(), len);
      if (!buffer.hasRemaining()) {
        writeChunk(buffer);
      }
      len -= writeLen;
      count++;
      writtenDataLength += writeLen;
      // we should not call isBufferFull/shouldFlush here.
      // The buffer might already be full as whole data is already cached in
      // the buffer. We should just validate
      // if we wrote data of size streamBufferMaxSize/streamBufferFlushSize to
      // call for handling full buffer/flush buffer condition.
      if (writtenDataLength % config.getStreamBufferFlushSize() == 0) {
        // reset the position to zero as now we will be reading the
        // next buffer in the list
        updateFlushLength();
        executePutBlock(false, false);
      }
      if (writtenDataLength == config.getStreamBufferMaxSize()) {
        handleFullBuffer();
      }
    }
  }

  /**
   * This is a blocking call. It will wait for the flush till the commit index
   * at the head of the commitIndex2flushedDataMap gets replicated to all or
   * majority.
   * @throws IOException
   */
  private void handleFullBuffer() throws IOException {
    try {
      checkOpen();
      if (!commitWatcher.getFutureMap().isEmpty()) {
        waitOnFlushFutures();
      }
    } catch (ExecutionException e) {
      handleExecutionException(e);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      handleInterruptedException(ex, true);
    }
    watchForCommit(true);
  }


  // It may happen that once the exception is encountered , we still might
  // have successfully flushed up to a certain index. Make sure the buffers
  // only contain data which have not been sufficiently replicated
  private void adjustBuffersOnException() {
    commitWatcher.releaseBuffersOnException();
    refreshCurrentBuffer(bufferPool);
  }

  /**
   * calls watchForCommit API of the Ratis Client. For Standalone client,
   * it is a no op.
   * @param bufferFull flag indicating whether bufferFull condition is hit or
   *              its called as part flush/close
   * @return minimum commit index replicated to all nodes
   * @throws IOException IOException in case watch gets timed out
   */
  private void watchForCommit(boolean bufferFull) throws IOException {
    checkOpen();
    try {
      XceiverClientReply reply = bufferFull ?
          commitWatcher.watchOnFirstIndex() : commitWatcher.watchOnLastIndex();
      if (reply != null) {
        List<DatanodeDetails> dnList = reply.getDatanodes();
        if (!dnList.isEmpty()) {
          Pipeline pipe = xceiverClient.getPipeline();

          LOG.warn("Failed to commit BlockId {} on {}. Failed nodes: {}",
              blockID, pipe, dnList);
          failedServers.addAll(dnList);
        }
      }
    } catch (IOException ioe) {
      setIoException(ioe);
      throw getIoException();
    }
    refreshCurrentBuffer(bufferPool);

  }

  /**
   * @param close whether putBlock is happening as part of closing the stream
   * @param force true if no data was written since most recent putBlock and
   *            stream is being closed
   */
  private CompletableFuture<ContainerProtos.
      ContainerCommandResponseProto> executePutBlock(boolean close,
      boolean force) throws IOException {
    checkOpen();
    long flushPos = totalDataFlushedLength;
    final List<ChunkBuffer> byteBufferList;
    if (!force) {
      Preconditions.checkNotNull(bufferList);
      byteBufferList = bufferList;
      bufferList = null;
      Preconditions.checkNotNull(byteBufferList);
    } else {
      byteBufferList = null;
    }

    try {
      CompletableFuture.allOf(futures.toArray(EMPTY_FUTURE_ARRAY)).get();
    } catch (Exception e) {
      LOG.warn("Failed to write all chunks through stream: " + e);
      throw new IOException(e);
    }
    if (close) {
      dataStreamCloseReply = out.closeAsync();
    }

    CompletableFuture<ContainerProtos.
        ContainerCommandResponseProto> flushFuture = null;
    try {
      BlockData blockData = containerBlockData.build();
      XceiverClientReply asyncReply =
          putBlockAsync(xceiverClient, blockData, close, token);
      CompletableFuture<ContainerProtos.ContainerCommandResponseProto> future =
          asyncReply.getResponse();
      flushFuture = future.thenApplyAsync(e -> {
        try {
          validateResponse(e);
        } catch (IOException sce) {
          throw new CompletionException(sce);
        }
        // if the ioException is not set, putBlock is successful
        if (getIoException() == null && !force) {
          BlockID responseBlockID = BlockID.getFromProtobuf(
              e.getPutBlock().getCommittedBlockLength().getBlockID());
          Preconditions.checkState(blockID.get().getContainerBlockID()
              .equals(responseBlockID.getContainerBlockID()));
          // updates the bcsId of the block
          blockID.set(responseBlockID);
          if (LOG.isDebugEnabled()) {
            LOG.debug(
                "Adding index " + asyncReply.getLogIndex() + " commitMap size "
                    + commitWatcher.getCommitInfoMapSize() + " flushLength "
                    + flushPos + " numBuffers " + byteBufferList.size()
                    + " blockID " + blockID + " bufferPool size" + bufferPool
                    .getSize() + " currentBufferIndex " + bufferPool
                    .getCurrentBufferIndex());
          }
          // for standalone protocol, logIndex will always be 0.
          commitWatcher
              .updateCommitInfoMap(asyncReply.getLogIndex(), byteBufferList);
        }
        return e;
      }, responseExecutor).exceptionally(e -> {
        if (LOG.isDebugEnabled()) {
          LOG.debug("putBlock failed for blockID {} with exception {}",
              blockID, e.getLocalizedMessage());
        }
        CompletionException ce = new CompletionException(e);
        setIoException(ce);
        throw ce;
      });
    } catch (IOException | ExecutionException e) {
      throw new IOException(EXCEPTION_MSG + e.toString(), e);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      handleInterruptedException(ex, false);
    }
    commitWatcher.getFutureMap().put(flushPos, flushFuture);
    return flushFuture;
  }

  @Override
  public void flush() throws IOException {
    if (xceiverClientFactory != null && xceiverClient != null
        && bufferPool != null && bufferPool.getSize() > 0
        && (!config.isStreamBufferFlushDelay() ||
            writtenDataLength - totalDataFlushedLength
                >= config.getStreamBufferSize())) {
      try {
        handleFlush(false);
      } catch (ExecutionException e) {
        // just set the exception here as well in order to maintain sanctity of
        // ioException field
        handleExecutionException(e);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        handleInterruptedException(ex, true);
      }
    }
  }

  private void writeChunk(ChunkBuffer buffer)
      throws IOException {
    // This data in the buffer will be pushed to datanode and a reference will
    // be added to the bufferList. Once putBlock gets executed, this list will
    // be marked null. Hence, during first writeChunk call after every putBlock
    // call or during the first call to writeChunk here, the list will be null.

    if (bufferList == null) {
      bufferList = new ArrayList<>();
    }
    bufferList.add(buffer);
    writeChunkToContainer(buffer.duplicate(0, buffer.position()));
  }

  /**
   * @param close whether the flush is happening as part of closing the stream
   */
  private void handleFlush(boolean close)
      throws IOException, InterruptedException, ExecutionException {
    checkOpen();
    // flush the last chunk data residing on the currentBuffer
    if (totalDataFlushedLength < writtenDataLength) {
      refreshCurrentBuffer(bufferPool);
      Preconditions.checkArgument(currentBuffer.position() > 0);
      if (currentBuffer.hasRemaining()) {
        writeChunk(currentBuffer);
      }
      // This can be a partially filled chunk. Since we are flushing the buffer
      // here, we just limit this buffer to the current position. So that next
      // write will happen in new buffer
      updateFlushLength();
      executePutBlock(close, false);
    } else if (close) {
      // forcing an "empty" putBlock if stream is being closed without new
      // data since latest flush - we need to send the "EOF" flag
      executePutBlock(true, true);
    }
    waitOnFlushFutures();
    watchForCommit(false);
    // just check again if the exception is hit while waiting for the
    // futures to ensure flush has indeed succeeded

    // irrespective of whether the commitIndex2flushedDataMap is empty
    // or not, ensure there is no exception set
    checkOpen();
  }

  @Override
  public void close() throws IOException {
    if (xceiverClientFactory != null && xceiverClient != null
        && bufferPool != null && bufferPool.getSize() > 0) {
      try {
        handleFlush(true);
        dataStreamCloseReply.get();
      } catch (ExecutionException e) {
        handleExecutionException(e);
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        handleInterruptedException(ex, true);
      } finally {
        cleanup(false);
      }
      // TODO: Turn the below buffer empty check on when Standalone pipeline
      // is removed in the write path in tests
      // Preconditions.checkArgument(buffer.position() == 0);
      // bufferPool.checkBufferPoolEmpty();

    }
  }

  private void waitOnFlushFutures()
      throws InterruptedException, ExecutionException {
    CompletableFuture<Void> combinedFuture = CompletableFuture.allOf(
        commitWatcher.getFutureMap().values().toArray(
            new CompletableFuture[commitWatcher.getFutureMap().size()]));
    // wait for all the transactions to complete
    combinedFuture.get();
  }

  private void validateResponse(
      ContainerProtos.ContainerCommandResponseProto responseProto)
      throws IOException {
    try {
      // if the ioException is already set, it means a prev request has failed
      // just throw the exception. The current operation will fail with the
      // original error
      IOException exception = getIoException();
      if (exception != null) {
        throw exception;
      }
      ContainerProtocolCalls.validateContainerResponse(responseProto);
    } catch (StorageContainerException sce) {
      setIoException(sce);
      throw sce;
    }
  }


  private void setIoException(Exception e) {
    IOException ioe = getIoException();
    if (ioe == null) {
      IOException exception =  new IOException(EXCEPTION_MSG + e.toString(), e);
      ioException.compareAndSet(null, exception);
    } else {
      LOG.debug("Previous request had already failed with " + ioe.toString()
          + " so subsequent request also encounters"
          + " Storage Container Exception ", e);
    }
  }

  public void cleanup(boolean invalidateClient) {
    if (xceiverClientFactory != null) {
      xceiverClientFactory.releaseClient(xceiverClient, invalidateClient);
    }
    xceiverClientFactory = null;
    xceiverClient = null;
    commitWatcher.cleanup();
    if (bufferList !=  null) {
      bufferList.clear();
    }
    bufferList = null;
    responseExecutor.shutdown();
  }

  /**
   * Checks if the stream is open or exception has occurred.
   * If not, throws an exception.
   *
   * @throws IOException if stream is closed
   */
  private void checkOpen() throws IOException {
    if (isClosed()) {
      throw new IOException("BlockDataStreamOutput has been closed.");
    } else if (getIoException() != null) {
      adjustBuffersOnException();
      throw getIoException();
    }
  }

  public boolean isClosed() {
    return xceiverClient == null;
  }

  private boolean needSync(long position) {
    if (syncSize > 0) {
      // TODO: or position >= fileLength
      if (position - syncPosition >= syncSize) {
        syncPosition = position;
        return true;
      }
    }
    return false;
  }

  /**
   * Writes buffered data as a new chunk to the container and saves chunk
   * information to be used later in putKey call.
   *
   * @throws IOException if there is an I/O error while performing the call
   * @throws OzoneChecksumException if there is an error while computing
   * checksum
   */
  private void writeChunkToContainer(ChunkBuffer chunk) throws IOException {
    int effectiveChunkSize = chunk.remaining();
    final long offset = chunkOffset.getAndAdd(effectiveChunkSize);
    final ByteString data = chunk.toByteString(
        bufferPool.byteStringConversion());
    ChecksumData checksumData = checksum.computeChecksum(chunk);
    ChunkInfo chunkInfo = ChunkInfo.newBuilder()
        .setChunkName(blockID.get().getLocalID() + "_chunk_" + ++chunkIndex)
        .setOffset(offset)
        .setLen(effectiveChunkSize)
        .setChecksumData(checksumData.getProtoBufMessage())
        .build();

    if (LOG.isDebugEnabled()) {
      LOG.debug("Writing chunk {} length {} at offset {}",
          chunkInfo.getChunkName(), effectiveChunkSize, offset);
    }

    CompletableFuture<DataStreamReply> future =
        (needSync(offset + effectiveChunkSize) ?
        out.writeAsync(data.asReadOnlyByteBuffer(), StandardWriteOption.SYNC) :
        out.writeAsync(data.asReadOnlyByteBuffer()))
        .whenCompleteAsync((r, e) -> {
          if (e != null || !r.isSuccess()) {
            if (e == null) {
              e = new IOException("result is not success");
            }
            String msg = "Failed to write chunk " + chunkInfo.getChunkName() +
                " " + "into block " + blockID;
            LOG.debug("{}, exception: {}", msg, e.getLocalizedMessage());
            CompletionException ce = new CompletionException(msg, e);
            setIoException(ce);
            throw ce;
          }
        }, responseExecutor);

    futures.add(future);
    containerBlockData.addChunks(chunkInfo);
  }

  @VisibleForTesting
  public void setXceiverClient(XceiverClientRatis xceiverClient) {
    this.xceiverClient = xceiverClient;
  }

  /**
   * Handles InterruptedExecution.
   *
   * @param ex
   * @param processExecutionException is optional, if passed as TRUE, then
   * handle ExecutionException else skip it.
   * @throws IOException
   */
  private void handleInterruptedException(Exception ex,
      boolean processExecutionException)
      throws IOException {
    LOG.error("Command execution was interrupted.");
    if(processExecutionException) {
      handleExecutionException(ex);
    } else {
      throw new IOException(EXCEPTION_MSG + ex.toString(), ex);
    }
  }

  /**
   * Handles ExecutionException by adjusting buffers.
   * @param ex
   * @throws IOException
   */
  private void handleExecutionException(Exception ex) throws IOException {
    setIoException(ex);
    adjustBuffersOnException();
    throw getIoException();
  }
}