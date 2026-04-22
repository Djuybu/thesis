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

package com.dremio.sabot.op.writer;

import static com.dremio.exec.planner.physical.WriterPrel.BUCKET_NUMBER_FIELD;
import static com.dremio.exec.planner.physical.WriterPrel.PARTITION_COMPARATOR_FIELD;
import static com.dremio.exec.planner.physical.visitor.WriterUpdater.requireSortBeforeWriteForClustering;
import static com.dremio.exec.store.SystemSchemas.CLUSTERING_INDEX;
import static com.dremio.exec.store.SystemSchemas.FILE_GROUP_INDEX;
import static com.dremio.exec.store.SystemSchemas.SEGMENT_COMPARATOR_FIELD;
import static com.dremio.exec.store.SystemSchemas.SYSTEM_COLUMNS;
import static com.dremio.exec.store.iceberg.IcebergUtils.isIncrementalRefresh;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.dremio.common.AutoCloseables;
import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.physical.base.WriterOptions;
import com.dremio.exec.proto.ExecProtos.FragmentHandle;
import com.dremio.exec.record.TypedFieldId;
import com.dremio.exec.record.VectorAccessible;
import com.dremio.exec.record.VectorContainer;
import com.dremio.exec.record.VectorWrapper;
import com.dremio.exec.store.RecordWriter;
import com.dremio.exec.store.RecordWriter.OutputEntryListener;
import com.dremio.exec.store.RecordWriter.WriteStatsListener;
import com.dremio.exec.store.SystemSchemas.SystemColumnStatistics;
import com.dremio.exec.store.WritePartition;
import com.dremio.exec.store.iceberg.IcebergPartitionData;
import com.dremio.exec.store.iceberg.IcebergSerDe;
import com.dremio.exec.store.iceberg.model.IcebergCommandType;
import com.dremio.sabot.exec.context.MetricDef;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.context.OperatorStats;
import com.dremio.sabot.op.spi.SingleInputOperator;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.OutOfMemoryException;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.complex.impl.UnionListWriter;
import org.apache.commons.collections4.CollectionUtils;

public class WriterOperator implements SingleInputOperator {

  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(WriterOperator.class);
  private final Listener listener = new Listener();
  private final StatsListener statsListener = new StatsListener();
  private final OperatorContext context;
  private final OperatorStats stats;
  private final RecordWriter recordWriter;
  private final String fragmentUniqueId;
  private final VectorContainer output;
  private final WriterOptions options;

  private boolean completedInput = false;

  private State state = State.NEEDS_SETUP;

  private VectorContainer maskedContainer;
  private BigIntVector summaryVector;
  private BigIntVector fileSizeVector;
  private VarCharVector fragmentIdVector;
  private VarCharVector pathVector;
  private VarBinaryVector metadataVector;
  private VarBinaryVector icebergMetadataVector;
  private IntVector partitionNumberVector;
  private VarBinaryVector schemaVector;
  private ListVector partitionDataVector;
  private IntVector operationTypeVector;
  private VarCharVector partitionValueVector;
  private BigIntVector rejectedRecordVector;
  private VarBinaryVector referencedDataFilesVector;

  private VarBinaryVector clusteringIndexVector;

  private PartitionWriteManager partitionManager;

  private WritePartition partition = null;

  private long writtenRecords = 0L;

  private final long writtenRecordLimit;
  private boolean reachedOutputLimit = false;
  private BigIntVector fileGroupIndexVector;

  private boolean writeAsClustering = false;

  private Long lastFileGroupIndex = null;

  public enum Metric implements MetricDef {
    BYTES_WRITTEN, // Number of bytes written to the output file(s)
    OUTPUT_LIMITED; // 1, if the output limit was reached; 0, if not

    @Override
    public int metricId() {
      return ordinal();
    }
  }

  public WriterOperator(OperatorContext context, WriterOptions options, RecordWriter recordWriter)
      throws OutOfMemoryException {
    this.context = context;
    this.stats = context.getStats();
    this.output = context.createOutputVectorContainer(RecordWriter.SCHEMA);
    this.options = options;
    final FragmentHandle handle = context.getFragmentHandle();
    this.fragmentUniqueId =
        String.format("%d_%d", handle.getMajorFragmentId(), handle.getMinorFragmentId());
    this.recordWriter = recordWriter;
    this.writtenRecordLimit = options.getRecordLimit();
  }

  private VectorContainer getMaskedContainer(VectorAccessible incoming) {
    VectorContainer maskedContainer = new VectorContainer();
    for (VectorWrapper<?> wrapper : incoming) {
      if (wrapper.getField().getName().equalsIgnoreCase(CLUSTERING_INDEX)
          || wrapper.getField().getName().equalsIgnoreCase(FILE_GROUP_INDEX)
          || wrapper.getField().getName().equalsIgnoreCase(SEGMENT_COMPARATOR_FIELD)
          || wrapper.getField().getName().equalsIgnoreCase(PARTITION_COMPARATOR_FIELD)
          || wrapper.getField().getName().equalsIgnoreCase(BUCKET_NUMBER_FIELD)) {
        continue;
      }
      maskedContainer.add(wrapper.getValueVector());
    }
    maskedContainer.buildSchema();
    return maskedContainer;
  }

  @Override
  public VectorAccessible setup(VectorAccessible incoming) throws Exception {
    state.is(State.NEEDS_SETUP);

    if (options.hasPartitions() || options.hasDistributions()) {
      partitionManager =
          new PartitionWriteManager(
              options, incoming, options.getTableFormatOptions().isTableFormatWriter());
      this.maskedContainer = partitionManager.getMaskedContainer();
      recordWriter.setup(maskedContainer, listener, statsListener);
    } else {
      if (options.isWriteAsClustering()
          && (options.getCombineSmallFileOptions() == null
              || !options.getCombineSmallFileOptions().getIsSmallFileWriter())) {
        final TypedFieldId fileGroupIndexField =
            incoming.getValueVectorId(SchemaPath.getSimplePath(FILE_GROUP_INDEX));
        writeAsClustering = (fileGroupIndexField != null);
        if (writeAsClustering) {
          fileGroupIndexVector =
              incoming
                  .getValueAccessorById(BigIntVector.class, fileGroupIndexField.getFieldIds())
                  .getValueVector();

          final TypedFieldId clusteringIndexField =
              incoming.getValueVectorId(SchemaPath.getSimplePath(CLUSTERING_INDEX));
          clusteringIndexVector =
              incoming
                  .getValueAccessorById(VarBinaryVector.class, clusteringIndexField.getFieldIds())
                  .getValueVector();

          Map<String, ValueVector> systemColumnStatisticsSource =
              ImmutableMap.of(CLUSTERING_INDEX, clusteringIndexVector);

          // only add column statistics if the data has been ordered by clustering
          if (requireSortBeforeWriteForClustering(options)) {
            statsListener.setup(systemColumnStatisticsSource);
          }
        }
      }
      this.maskedContainer = getMaskedContainer(incoming);
      recordWriter.setup(maskedContainer, listener, statsListener);
    }

    // Create the RecordWriter.SCHEMA vectors.
    fragmentIdVector = output.addOrGet(RecordWriter.FRAGMENT);
    pathVector = output.addOrGet(RecordWriter.PATH);
    summaryVector = output.addOrGet(RecordWriter.RECORDS);
    fileSizeVector = output.addOrGet(RecordWriter.FILESIZE);
    metadataVector = output.addOrGet(RecordWriter.METADATA);
    partitionNumberVector = output.addOrGet(RecordWriter.PARTITION);
    icebergMetadataVector = output.addOrGet(RecordWriter.ICEBERG_METADATA);
    schemaVector = output.addOrGet(RecordWriter.FILE_SCHEMA);
    partitionDataVector = output.addOrGet(RecordWriter.PARTITION_DATA);
    operationTypeVector = output.addOrGet(RecordWriter.OPERATION_TYPE);
    partitionValueVector = output.addOrGet(RecordWriter.PARTITION_VALUE);
    rejectedRecordVector = output.addOrGet(RecordWriter.REJECTED_RECORDS);
    referencedDataFilesVector = output.addOrGet(RecordWriter.REFERENCED_DATA_FILES);
    output.buildSchema();
    output.setInitialCapacity(context.getTargetBatchSize());
    state = State.CAN_CONSUME;
    return output;
  }

  @Override
  public void consumeData(final int records) throws Exception {
    state.is(State.CAN_CONSUME);

    // always need to keep the masked container in alignment.
    if (maskedContainer != null) {
      maskedContainer.setRecordCount(records);
    }

    // no partitions.
    if (!options.hasPartitions() && !options.hasDistributions() && !writeAsClustering) {
      if (partition == null) {
        partition = WritePartition.NONE;
        recordWriter.startPartition(partition);
      }
      writtenRecords += recordWriter.writeBatch(0, records);
      if (writtenRecords > writtenRecordLimit) {
        if (!listener.hasPendingOutput) {
          recordWriter.close();
        }
        reachedOutputLimit = true;
        state = State.CAN_PRODUCE;
      } else {
        moveToCanProduceStateIfOutputExists();
      }
      return;
    }

    // we're partitioning.
    int pointer = 0;
    int start = 0;
    if (writeAsClustering) {
      this.partition = WritePartition.NONE;
    }
    while (pointer < records) {
      boolean changingValue = false;
      if (writeAsClustering) {
        Long currentFileGroupIndex = fileGroupIndexVector.get(pointer);
        if (lastFileGroupIndex == null || !lastFileGroupIndex.equals(currentFileGroupIndex)) {
          changingValue = true;
        }
        lastFileGroupIndex = currentFileGroupIndex;
      } else {
        final WritePartition newPartition = partitionManager.getExistingOrNewPartition(pointer);
        changingValue = newPartition != partition;
        this.partition = newPartition;
      }

      if (changingValue) {
        int length = pointer - start;
        if (length > 0) {
          recordWriter.writeBatch(start, length);
          updateSystemColumnUpperBounds(pointer - 1);
        }
        start = pointer;
        recordWriter.startPartition(partition);
        moveToCanProduceStateIfOutputExists();
      }
      pointer++;
    }

    // write any remaining to existing partition.
    recordWriter.writeBatch(start, pointer - start);
    updateSystemColumnUpperBounds(pointer - 1);
    moveToCanProduceStateIfOutputExists();
  }

  private void updateSystemColumnUpperBounds(int offset) {
    if (writeAsClustering) {
      statsListener.setBatchOffset(offset);
      statsListener.updateSystemColumnUpperBounds();
    }
  }

  @Override
  public int outputData() throws Exception {
    state.is(State.CAN_PRODUCE);

    // If we have pending output, we need to process it before continuing.
    boolean blocked = recordWriter.processPendingOutput(completedInput, reachedOutputLimit);
    if (blocked) {
      return 0;
    }

    output.allocateNew();

    final byte[] fragmentUniqueIdBytes = fragmentUniqueId.getBytes();
    int i = 0;
    for (OutputEntry e : listener.entries) {
      fragmentIdVector.setSafe(i, fragmentUniqueIdBytes, 0, fragmentUniqueIdBytes.length);

      summaryVector.setSafe(i, e.recordCount);

      if (e.metadata != null) {
        metadataVector.setSafe(i, e.metadata, 0, e.metadata.length);
      }

      if (e.icebergMetadata != null) {
        icebergMetadataVector.setSafe(i, e.icebergMetadata, 0, e.icebergMetadata.length);
      }

      if (e.path != null) {
        byte[] bytePath = e.path.getBytes(UTF_8);
        pathVector.setSafe(i, bytePath, 0, bytePath.length);
      }

      if (e.partitionNumber != null) {
        partitionNumberVector.setSafe(i, e.partitionNumber);
      }

      if (e.schema != null) {
        schemaVector.setSafe(i, e.schema, 0, e.schema.length);
      }

      if (CollectionUtils.isNotEmpty(e.partitions)) {
        UnionListWriter listWriter = partitionDataVector.getWriter();
        listWriter.setPosition(i);
        listWriter.startList();
        for (IcebergPartitionData partitionData : e.partitions) {
          byte[] bytes = IcebergSerDe.serializeToByteArray(partitionData);
          ArrowBuf buffer = null;
          try {
            buffer = context.getAllocator().buffer(bytes.length);
            buffer.setBytes(0, bytes);
            listWriter.varBinary().writeVarBinary(0, bytes.length, buffer);
          } finally {
            AutoCloseables.close(buffer);
          }
        }
        listWriter.endList();
      }

      fileSizeVector.setSafe(i, e.fileSize);

      if (e.operationType != null) {
        operationTypeVector.setSafe(i, e.operationType);
      }

      if (e.partitionValue != null) {
        byte[] bytePath = e.partitionValue.getBytes(UTF_8);
        partitionValueVector.setSafe(i, bytePath, 0, bytePath.length);
      }

      rejectedRecordVector.setSafe(i, e.rejectedRecordCount);

      if (e.referencedDataFiles != null) {
        referencedDataFilesVector.setSafe(
            i, e.referencedDataFiles, 0, e.referencedDataFiles.length);
      }

      i++;
    }

    listener.entries.clear();

    if (completedInput || reachedOutputLimit) {
      stats.addLongStat(Metric.OUTPUT_LIMITED, reachedOutputLimit ? 1 : 0);
      state = State.DONE;
    } else {
      state = State.CAN_CONSUME;
    }
    return output.setAllCount(i);
  }

  @Override
  public void noMoreToConsume() throws Exception {
    state.is(State.CAN_CONSUME);
    if (!listener.hasPendingOutput) {
      recordWriter.close();
    }
    this.completedInput = true;
    state = State.CAN_PRODUCE;
  }

  @Override
  public State getState() {
    return state;
  }

  @Override
  public <OUT, IN, EXCEP extends Throwable> OUT accept(
      OperatorVisitor<OUT, IN, EXCEP> visitor, IN value) throws EXCEP {
    return visitor.visitSingleInput(this, value);
  }

  @Override
  public void close() throws Exception {
    AutoCloseables.close(recordWriter, output);
    try {
      if (checkForIcebergRecordWriterAbort()) {
        recordWriter.abort();
        if (options
                .getTableFormatOptions()
                .getIcebergSpecificOptions()
                .getIcebergTableProps()
                .getIcebergOpType()
            == IcebergCommandType.INSERT) {
          Preconditions.checkNotNull(recordWriter.getLocation(), "Location cannot be null");
          Preconditions.checkState(recordWriter.getFs() != null, "Unexpected state");
          recordWriter.getFs().delete(recordWriter.getLocation(), true);
        }
      }
    } catch (Exception e) {
      logger.error(
          String.format("Failure during cleaning up the unwanted files: %s", e.getMessage()));
    }
  }

  private boolean checkForIcebergRecordWriterAbort() throws Exception {
    if (state != State.DONE
        && recordWriter != null
        && options != null
        && options.getTableFormatOptions().getIcebergSpecificOptions().getIcebergTableProps()
            != null) {
      IcebergCommandType command =
          options
              .getTableFormatOptions()
              .getIcebergSpecificOptions()
              .getIcebergTableProps()
              .getIcebergOpType();
      if (isIncrementalRefresh(command)
          || command == IcebergCommandType.CREATE
          || command == IcebergCommandType.INSERT) {
        return true;
      }
    }
    return false;
  }

  private void moveToCanProduceStateIfOutputExists() {
    if (!listener.entries.isEmpty() || listener.hasPendingOutput) {
      state = State.CAN_PRODUCE;
    }
  }

  public class Listener implements OutputEntryListener {

    private final List<OutputEntry> entries = new ArrayList<>();

    private boolean hasPendingOutput = false;

    @Override
    public void recordsWritten(
        long recordCount,
        long fileSize,
        String path,
        byte[] metadata,
        Integer partitionNumber,
        byte[] icebergMetadata,
        byte[] schema,
        Collection<IcebergPartitionData> partitions,
        Integer operationType,
        String partitionValue,
        long rejectedRecordCount,
        byte[] referencedDataFiles) {
      entries.add(
          new OutputEntry(
              recordCount,
              fileSize,
              path,
              metadata,
              icebergMetadata,
              partitionNumber,
              schema,
              partitions,
              operationType,
              partitionValue,
              rejectedRecordCount,
              referencedDataFiles));
    }

    public void setHasPendingOutput(boolean hasPendingOutput) {
      this.hasPendingOutput = hasPendingOutput;
    }
  }

  private class StatsListener implements WriteStatsListener {
    private Map<String, ValueVector> systemColumnValueSources;
    private final Map<String, SystemColumnStatistics> systemColumnStatistics = new HashMap<>();
    private int batchOffset = -1;

    public void setup(Map<String, ValueVector> systemColumnValueSources) {
      this.systemColumnValueSources = systemColumnValueSources;
    }

    @Override
    public void bytesWritten(long byteCount) {
      stats.addLongStat(Metric.BYTES_WRITTEN, byteCount);
    }

    private void updateColumnStatsByBatchOffset(boolean isLowerBound) {
      if (systemColumnValueSources == null) {
        return;
      }

      // get system column values by the offset in current batch
      Preconditions.checkState(batchOffset >= 0);
      for (Map.Entry<String, ValueVector> entry : systemColumnValueSources.entrySet()) {
        String systemColumn = entry.getKey();
        ValueVector valueVector = entry.getValue();
        Preconditions.checkState(batchOffset < valueVector.getValueCount());
        Object value = valueVector.getObject(batchOffset);
        SystemColumnStatistics columnStatistics =
            systemColumnStatistics.computeIfAbsent(
                systemColumn, key -> new SystemColumnStatistics(SYSTEM_COLUMNS.get(systemColumn)));
        if (isLowerBound) {
          columnStatistics.setLowerBound(value);
        } else {
          columnStatistics.setUpperBound(value);
        }
      }
    }

    @Override
    public void updateSystemColumnLowerBounds() {
      updateColumnStatsByBatchOffset(true);
    }

    @Override
    public void updateSystemColumnUpperBounds() {
      updateColumnStatsByBatchOffset(false);
    }

    @Override
    public List<SystemColumnStatistics> getSystemColumnStatistics() {
      return systemColumnStatistics.values().stream().collect(Collectors.toList());
    }

    @Override
    public void setBatchOffset(int offset) {
      batchOffset = offset;
    }
  }

  private class OutputEntry {
    private final long recordCount;
    private final long fileSize;
    private final String path;
    private final byte[] metadata;
    private final byte[] icebergMetadata;
    private final byte[] schema;
    private final Integer partitionNumber;
    private final Collection<IcebergPartitionData> partitions;
    private final Integer operationType;
    private final String partitionValue;
    private final long rejectedRecordCount;
    private final byte[] referencedDataFiles;

    OutputEntry(
        long recordCount,
        long fileSize,
        String path,
        byte[] metadata,
        byte[] icebergMetadata,
        Integer partitionNumber,
        byte[] schema,
        Collection<IcebergPartitionData> partitions,
        Integer operationType,
        String partitionValue,
        long rejectedRecordCount,
        byte[] referencedDataFiles) {
      this.recordCount = recordCount;
      this.fileSize = fileSize;
      this.path = path;
      this.metadata = metadata;
      this.icebergMetadata = icebergMetadata;
      this.partitionNumber = partitionNumber;
      this.schema = schema;
      this.partitions = partitions;
      this.operationType = operationType;
      this.partitionValue = partitionValue;
      this.rejectedRecordCount = rejectedRecordCount;
      this.referencedDataFiles = referencedDataFiles;
    }
  }
}
