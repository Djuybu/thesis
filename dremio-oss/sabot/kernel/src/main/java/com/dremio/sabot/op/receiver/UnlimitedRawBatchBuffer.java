/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.sabot.op.receiver;

import com.dremio.exec.ExecConstants;
import com.dremio.exec.proto.ExecProtos.FragmentHandle;
import com.dremio.options.OptionManager;
import com.dremio.sabot.threads.sharedres.SharedResource;
import com.google.common.collect.Queues;
import java.util.concurrent.LinkedBlockingDeque;
import org.apache.arrow.memory.BufferAllocator;

public class UnlimitedRawBatchBuffer extends BaseRawBatchBuffer<RawFragmentBatch> {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(UnlimitedRawBatchBuffer.class);

  private final int softLimit;
  private final long softByteLimit;

  public UnlimitedRawBatchBuffer(
      SharedResource resource,
      OptionManager options,
      FragmentHandle handle,
      BufferAllocator allocator,
      int fragmentCount,
      int oppositeId) {
    super(resource, options, handle, allocator, fragmentCount);
    int bufferSizePerSocket = (int) options.getOption(ExecConstants.INCOMING_BUFFER_SIZE);
    int bufferSize = (int) options.getOption(ExecConstants.INCOMING_BUFFER_BATCH_LIMIT);
    this.softLimit = Math.min(bufferSizePerSocket * fragmentCount, bufferSize);
    this.softByteLimit = (int) options.getOption(ExecConstants.INCOMING_BUFFER_BATCH_BYTE_LIMIT);
    logger.debug("softLimit: {}", softLimit);
    this.bufferQueue = new UnlimitedBufferQueue();
  }

  private static class UnlimitedBufferQueue implements BufferQueue<RawFragmentBatch> {
    private final LinkedBlockingDeque<RawFragmentBatch> buffer = Queues.newLinkedBlockingDeque();

    private long currentBytesInBuffer;

    @Override
    public RawFragmentBatch poll() {
      RawFragmentBatch batch = buffer.poll();
      if (batch != null) {
        currentBytesInBuffer -= batch.getByteCount();
        batch.sendOk();
      }
      return batch;
    }

    @Override
    public int size() {
      return buffer.size();
    }

    @Override
    public boolean isEmpty() {
      return buffer.isEmpty();
    }

    @Override
    public void add(RawFragmentBatch batch) {
      buffer.add(batch);
      currentBytesInBuffer += batch.getByteCount();
    }

    @Override
    public void clear() {
      RawFragmentBatch batch;
      while (!buffer.isEmpty()) {
        batch = buffer.poll();
        currentBytesInBuffer -= batch.getByteCount();
        if (batch.getBody() != null) {
          batch.getBody().close();
        }
      }
    }

    @Override
    public long readableBytesSize() {
      return currentBytesInBuffer;
    }
  }

  @Override
  protected void enqueueInner(final RawFragmentBatch batch) {
    if (bufferQueue.size() < softLimit
        && (batch.getByteCount() + bufferQueue.readableBytesSize()) < softByteLimit) {
      batch.sendOk();
    }
    bufferQueue.add(batch);
  }

  @Override
  protected void upkeep(RawFragmentBatch batch) {}
}
