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
package com.dremio.sabot;

import static com.dremio.exec.util.VectorUtil.getVectorFromSchemaPath;

import com.dremio.common.AutoCloseables;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.RecordBatchData;
import com.dremio.exec.record.VectorAccessible;
import com.dremio.exec.record.VectorContainer;
import com.google.common.base.Preconditions;
import com.google.common.collect.Streams;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.util.TransferPair;

public class RecordBatchDataProviderGenerator implements Generator {
  private final VectorContainer container;

  private int currentInputBatch = 0;

  private int rowIndexInCurrentInput = 0;

  private final List<RecordBatchData> inputBatches;

  private List<TransferPair> transfers;

  public RecordBatchDataProviderGenerator(
      List<RecordBatchData> inputBatches, BufferAllocator allocator) {
    Preconditions.checkState(inputBatches.size() > 0);

    this.inputBatches = inputBatches;
    this.container = VectorContainer.create(allocator, inputBatches.get(0).getSchema());
    this.currentInputBatch = 0;
  }

  @Override
  public VectorAccessible getOutput() {
    return container;
  }

  public BatchSchema getSchema() {
    return container.getSchema();
  }

  @Override
  public int next(int records) {
    if (currentInputBatch == inputBatches.size()) {
      return 0;
    }

    int outputRowIndex = 0;
    container.allocateNew();
    while (currentInputBatch <= inputBatches.size()) {
      // first time to use this batch input
      if (rowIndexInCurrentInput == 0) {
        transfers =
            Streams.stream(inputBatches.get(currentInputBatch).getVectors())
                .map(
                    vw ->
                        vw.makeTransferPair(
                            getVectorFromSchemaPath(container, vw.getField().getName())))
                .collect(Collectors.toList());
      }

      int rowsToOutputInCurrentBatchInput =
          Math.min(
              inputBatches.get(currentInputBatch).getRecordCount() - rowIndexInCurrentInput,
              records);

      for (int i = 0; i < rowsToOutputInCurrentBatchInput; i++) {
        int finalI = i;
        int finalOutputRowIndex = outputRowIndex++;
        transfers.forEach(
            transfer ->
                transfer.copyValueSafe(rowIndexInCurrentInput + finalI, finalOutputRowIndex));
      }

      rowIndexInCurrentInput += rowsToOutputInCurrentBatchInput;

      // next batch input
      if (rowIndexInCurrentInput == inputBatches.get(currentInputBatch).getRecordCount()) {
        currentInputBatch++;
        if (currentInputBatch == inputBatches.size()) {
          break;
        }
        rowIndexInCurrentInput = 0;
      }
    }
    container.setAllCount(outputRowIndex);
    return outputRowIndex;
  }

  @Override
  public void close() throws Exception {
    AutoCloseables.close(container);
  }
}
