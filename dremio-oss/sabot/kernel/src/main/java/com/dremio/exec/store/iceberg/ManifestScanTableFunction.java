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
package com.dremio.exec.store.iceberg;

import static com.dremio.exec.store.SystemSchemas.DATASET_FIELD;
import static com.dremio.exec.util.VectorUtil.getVectorFromSchemaPath;

import com.dremio.common.AutoCloseables;
import com.dremio.exec.physical.config.ManifestScanTableFunctionContext;
import com.dremio.exec.physical.config.TableFunctionConfig;
import com.dremio.exec.record.VectorAccessible;
import com.dremio.exec.store.RecordReader;
import com.dremio.exec.store.dfs.AbstractTableFunction;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.context.OperatorStats;
import com.dremio.sabot.op.tablefunction.TableFunctionOperator;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Streams;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.iceberg.ManifestFile;

/** Table function for Iceberg manifest file scan */
public class ManifestScanTableFunction extends AbstractTableFunction {
  private final OperatorStats operatorStats;
  private final ManifestFileProcessor manifestFileProcessor;
  private final ManifestContentType manifestContentType;

  private VarBinaryVector inputManifestFiles;
  private Optional<VarCharVector> inputDataset;

  public ManifestScanTableFunction(
      OperatorContext context,
      TableFunctionConfig functionConfig,
      ManifestFileProcessor manifestFileProcessor) {
    super(context, functionConfig);
    this.operatorStats = context.getStats();
    this.manifestFileProcessor = manifestFileProcessor;
    this.manifestContentType =
        functionConfig
            .getFunctionContext(ManifestScanTableFunctionContext.class)
            .getManifestContentType();
  }

  @Override
  public VectorAccessible setup(VectorAccessible accessible) throws Exception {
    super.setup(accessible);
    inputManifestFiles =
        (VarBinaryVector) getVectorFromSchemaPath(incoming, RecordReader.SPLIT_INFORMATION);
    // See if dataset column exists
    inputDataset =
        Streams.stream(incoming)
            .filter(vw -> vw.getValueVector().getName().equals(DATASET_FIELD))
            .findFirst()
            .map(vw -> (VarCharVector) vw.getValueVector());
    manifestFileProcessor.setup(incoming, outgoing);
    return outgoing;
  }

  @Override
  public void startBatch(int records) {
    outgoing.allocateNew();
  }

  @Override
  public void startRow(int row) throws Exception {
    ManifestFile manifestFile = getManifestFile(row);
    Optional<List<String>> dataset =
        inputDataset.map(v -> Arrays.asList(new String(v.get(row)).split("\\.")));
    manifestFileProcessor.setupManifestFile(manifestFile, row, dataset);
  }

  @Override
  public int processRow(int startOutIndex, int maxOutputCount) throws Exception {
    int outputCount = manifestFileProcessor.process(startOutIndex, maxOutputCount);
    int totalRecordCount = startOutIndex + outputCount;
    outgoing.forEach(vw -> vw.getValueVector().setValueCount(totalRecordCount));
    outgoing.setRecordCount(totalRecordCount);
    return outputCount;
  }

  @Override
  public void closeRow() throws Exception {
    operatorStats.setReadIOStats();
    manifestFileProcessor.closeManifestFile();
  }

  @Override
  public void close() throws Exception {
    manifestFileProcessor.closeManifestFile();
    AutoCloseables.close(manifestFileProcessor, super::close);
  }

  @VisibleForTesting
  ManifestFile getManifestFile(int manifestFileIndex) throws IOException, ClassNotFoundException {
    ManifestFile manifestFile =
        IcebergSerDe.deserializeFromByteArray(inputManifestFiles.get(manifestFileIndex));
    operatorStats.addLongStat(
        manifestContentType == ManifestContentType.DATA
            ? TableFunctionOperator.Metric.NUM_MANIFEST_FILE
            : TableFunctionOperator.Metric.NUM_DELETE_MANIFESTS,
        1);
    return manifestFile;
  }
}
