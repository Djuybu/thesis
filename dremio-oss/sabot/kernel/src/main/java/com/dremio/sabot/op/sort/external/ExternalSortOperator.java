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
package com.dremio.sabot.op.sort.external;

import static com.dremio.exec.ExecConstants.ENABLE_SPILLABLE_OPERATORS;
import static com.dremio.exec.ExecConstants.EXTERNAL_SORT_ENABLE_SEGMENT_SORT;
import static com.dremio.exec.store.SystemSchemas.SEGMENT_COMPARATOR_FIELD;
import static com.dremio.exec.util.VectorUtil.getVectorFromSchemaPath;

import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.physical.config.ExternalSort;
import com.dremio.exec.proto.CoordExecRPC;
import com.dremio.exec.proto.CoordinationProtos.NodeEndpoint;
import com.dremio.exec.proto.ExecProtos.ExtSortSpillNotificationMessage;
import com.dremio.exec.record.BatchSchema.SelectionVectorMode;
import com.dremio.exec.record.TypedFieldId;
import com.dremio.exec.record.VectorAccessible;
import com.dremio.exec.record.VectorContainer;
import com.dremio.options.Options;
import com.dremio.options.TypeValidators.BooleanValidator;
import com.dremio.options.TypeValidators.DoubleValidator;
import com.dremio.options.TypeValidators.RangeDoubleValidator;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.context.OperatorStats;
import com.dremio.sabot.exec.fragment.OutOfBandMessage;
import com.dremio.sabot.op.sort.external.VectorSorter.SortState;
import com.dremio.sabot.op.spi.Operator.ShrinkableOperator;
import com.dremio.sabot.op.spi.SingleInputOperator;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Streams;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.OutOfMemoryException;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.util.TransferPair;

/**
 * Primary algorithm:
 *
 * <p>Multiple allocators: - incoming data - one allocator per batch - simpleIntVector or treeVector
 * - outputCopier (always holds reservation at least as much as x) -
 *
 * <p>Each record batch group that comes in is: - tracked in a QuickSorter and the whole set is
 * sorted at the time a final list is requested (default mode) - each batch is locally sorter, then
 * added to a SplayTreeSorter of sv4 values (sv4), the SplayTree is traversed when the final list is
 * requested (if SplaySort is enabled) - (in either sort method case, the data-buffers used to track
 * the row-indices in the batches are resized as new batches come in.)
 *
 * <p>We ensure that we always have a reservation of at least maxBatchSize. This guarantees that we
 * can spill.
 *
 * <p>We spill to disk if any of the following conditions occurs: - OutOfMemory or Can't add max
 * batch size reservation or Hit 64k batches
 *
 * <p>When we spill, we do batch copies (guaranteed successful since we previously reserved memory
 * to do the spills) to generate a new spill run. A DiskRun is written to disk.
 *
 * <p>Once spilled to disk, we keep track of each spilled run (including the maximum batch size of
 * that run).
 *
 * <p>Once we complete a number of runs, we determine a merge plan to complete the data merges. (For
 * now, the merge plan is always a simple priority queue n-way merge where n is the final number of
 * disk runs.)
 */
@Options
public class ExternalSortOperator implements SingleInputOperator, ShrinkableOperator {
  public static final BooleanValidator OOB_SORT_TRIGGER_ENABLED =
      new BooleanValidator("exec.operator.sort.oob_trigger_enabled", true);
  public static final DoubleValidator OOB_SORT_SPILL_TRIGGER_FACTOR =
      new RangeDoubleValidator("exec.operator.sort.oob_trigger_factor", 0.0d, 10.0d, .75d);
  public static final DoubleValidator OOB_SORT_SPILL_TRIGGER_HEADROOM_FACTOR =
      new RangeDoubleValidator("exec.operator.sort.oob_trigger_headroom_factor", 0.0d, 10.0d, .2d);

  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(ExternalSortOperator.class);

  private final OperatorContext context;
  private final ExternalSort config;
  private VectorSorter vectorSorter;
  private Queue<VectorSorter> segmentedVectorSorters;
  private VectorContainer incoming;
  private VectorContainer output;
  private BufferAllocator allocator;
  private BitVector segmentChangeVector;
  public static boolean oobSpillNotificationsEnabled;

  public int oobSends;
  public int oobReceives;
  public int oobDropLocal;
  public int oobDropWrongState;
  public int oobDropUnderThreshold;
  public int oobSpills;
  private final Stopwatch produceDataWatch = Stopwatch.createUnstarted();
  private final Stopwatch consumeDataWatch = Stopwatch.createUnstarted();
  private final Stopwatch noMoreToConsumeWatch = Stopwatch.createUnstarted();
  private int segmentId = 0;

  @Override
  public State getState() {
    return vectorSorter.getState();
  }

  public ExternalSortOperator(OperatorContext context, ExternalSort popConfig)
      throws OutOfMemoryException {
    this.context = context;
    this.config = popConfig;
    this.vectorSorter =
        new VectorSorter(
            context,
            this::notifyOthersOfSpill,
            config.getOrderings(),
            config.getProps().getLocalOperatorId());
    this.allocator = context.getAllocator();
    // not sending oob spill notifications to sibling minor fragments when MemoryArbiter is ON
    oobSpillNotificationsEnabled =
        !(context.getOptions().getOption(ENABLE_SPILLABLE_OPERATORS))
            && context.getOptions().getOption(OOB_SORT_TRIGGER_ENABLED);
  }

  @Override
  public VectorAccessible setup(VectorAccessible incoming) {
    this.incoming = (VectorContainer) incoming;
    final TypedFieldId segmentChangeDetectionField =
        incoming.getValueVectorId(SchemaPath.getSimplePath(SEGMENT_COMPARATOR_FIELD));
    VectorContainer vectorSorterOutput = (VectorContainer) vectorSorter.setup(incoming, true);

    if (context.getOptions().getOption(EXTERNAL_SORT_ENABLE_SEGMENT_SORT)
        && segmentChangeDetectionField != null) {
      segmentChangeVector =
          incoming
              .getValueAccessorById(BitVector.class, segmentChangeDetectionField.getFieldIds())
              .getValueVector();
      segmentedVectorSorters = new LinkedList<>();
      output = context.createOutputVectorContainer(incoming.getSchema());
      output.buildSchema(SelectionVectorMode.NONE);
    } else {
      output = vectorSorterOutput;
    }
    return output;
  }

  @Override
  public void close() throws Exception {
    /**
     * 'diskRuns' holds a ref to a VectorContainer, which is created by 'memoryRun'. Thus,'diskRuns'
     * must be closed before 'memoryRun' so that all the buffers referred in the VectorContainer
     * etc. are released first. Otherwise 'memoryRun' close would fail reporting memory leak.
     */
    vectorSorter.close();
    if (output != null) {
      output.close();
    }

    if (segmentedVectorSorters != null) {
      while (!segmentedVectorSorters.isEmpty()) {
        VectorSorter sorter = segmentedVectorSorters.poll();
        sorter.close();
      }
    }

    addDisplayStatsWithZeroValue(context, EnumSet.allOf(ExternalSortStats.Metric.class));
    updateStats(true);
  }

  @VisibleForTesting
  public void setStateToCanConsume() {
    this.vectorSorter.setStateToCanConsume();
  }

  @VisibleForTesting
  public void setStates(State masterState, SortState sortState) {
    this.vectorSorter.setStates(masterState, sortState);
  }

  private void addDataToSorter(int offset, int length, VectorSorter vectorSorter) {
    boolean multiSegmentsBatchMode = !(offset == 0 && length == incoming.getRecordCount());

    VectorContainer consumableBatch =
        multiSegmentsBatchMode ? buildSubBatch(incoming, allocator, offset, length) : incoming;

    try {
      setupSorter(consumableBatch, vectorSorter, true);

      vectorSorter.consumeData(length);
    } catch (Exception e) {
      consumableBatch.close();
      throw new RuntimeException("Unable to add batch into sorter.", e);
    } finally {
      if (multiSegmentsBatchMode) {
        consumableBatch.close();
      }
    }
  }

  /**
   * Consume data for segment sort Each segment uses dedicate vectorSorter 1. except for the first
   * segment, first row of each segment has segmentChangeVector == 1 2. when start a new segment,
   * wrap current vectorSort and start a new vectorSort 3. when the incoming batch has multiple
   * segments, add additional vectorSorts to a queue 4. current vectorSort calls noMoreToConsume(),
   * the state of ExternalSortOperator becomes CAN_PRODUCE
   */
  private void consumeSegmentData(int records) throws Exception {
    int pointer = 0;
    int start = 0;
    VectorSorter currentSorter = vectorSorter;
    while (pointer < records) {
      if (segmentChangeVector.get(pointer) == 1) {
        segmentId++;
        int length = pointer - start;
        if (length > 0) {
          addDataToSorter(start, length, currentSorter);
        }

        // the state of currentSorter becomes CAN_PRODUCE
        currentSorter.noMoreToConsume();

        // create additional sorters
        currentSorter =
            new VectorSorter(
                context,
                this::notifyOthersOfSpill,
                config.getOrderings(),
                // operatorLocalId in VectorSorter has to be unique
                (config.getProps().getLocalOperatorId() << 16) + segmentId);

        // add additional sorters to the queue
        segmentedVectorSorters.add(currentSorter);

        start = pointer;
      }
      pointer++;
    }

    // add any remaining to existing sorter.
    addDataToSorter(start, pointer - start, currentSorter);
  }

  /**
   * Builds a sub batch the source
   *
   * @param offset the starting boundary of the sub-batch
   * @param length the number of records from the batch that belong to sub-batch
   */
  private static VectorContainer buildSubBatch(
      VectorContainer source, BufferAllocator allocator, int offset, int length) {
    VectorContainer subBatch = new VectorContainer(allocator);
    subBatch.addSchema(source.getSchema());
    subBatch.buildSchema();

    copyVectorContainer(source, subBatch, offset, length);

    subBatch.setRecordCount(length);
    return subBatch;
  }

  private static void copyVectorContainer(
      VectorContainer from, VectorContainer to, int offset, int length) {
    List<TransferPair> transfers =
        Streams.stream(from)
            .map(
                vw ->
                    vw.getValueVector()
                        .makeTransferPair(getVectorFromSchemaPath(to, vw.getField().getName())))
            .collect(Collectors.toList());

    for (int i = 0; i < length; i++) {
      int finalI = i;
      transfers.forEach(transfer -> transfer.copyValueSafe(finalI + offset, finalI));
      to.setAllCount(i + 1);
    }
  }

  /** prepare the VectorSorter for consumption. */
  private static void setupSorter(
      VectorContainer incoming, VectorSorter vectorSorter, boolean isSpillAllowed) {
    if (vectorSorter.getState() == State.NEEDS_SETUP) {
      vectorSorter.setup(incoming, isSpillAllowed);
    } else {
      vectorSorter.setIncoming(incoming);
    }
  }

  @Override
  public void consumeData(int records) throws Exception {
    consumeDataWatch.start();
    // sorting mode:
    // 1. regular sorting: read entire data and sort
    // 2. segment sorting: sort data by segment (segmentChangeVector != null)
    if (segmentChangeVector != null) {
      consumeSegmentData(records);
    } else {
      vectorSorter.consumeData(records);
    }
    consumeDataWatch.stop();
    updateStats(false);
  }

  @Override
  public void noMoreToConsume() throws Exception {
    noMoreToConsumeWatch.start();
    vectorSorter.noMoreToConsume();
    noMoreToConsumeWatch.stop();
    updateStats(false);
  }

  @Override
  public int getOperatorId() {
    return this.config.getProps().getLocalOperatorId();
  }

  @Override
  public long shrinkableMemory() {
    return vectorSorter.shrinkableMemory();
  }

  @Override
  public boolean shrinkMemory(long size) throws Exception {
    return vectorSorter.shrinkMemory(size);
  }

  /**
   * Printing operator state for debug logs
   *
   * @return
   */
  @Override
  public String getOperatorStateToPrint() {
    return vectorSorter.getOperatorStateToPrint();
  }

  @Override
  public int outputData() throws Exception {
    produceDataWatch.start();

    vectorSorter.unblockSpill();

    if (vectorSorter.handleIfSpillInProgress()) {
      produceDataWatch.stop();
      return 0;
    }

    if (vectorSorter.handleIfCannotCopy()) {
      produceDataWatch.stop();
      updateStats(false);
      return 0;
    }

    int sortedOutputRecords = vectorSorter.outputData();
    if (segmentedVectorSorters != null) {
      if (sortedOutputRecords == 0 && !segmentedVectorSorters.isEmpty()) {
        // done with outputting with current segment
        produceDataWatch.stop();
        vectorSorter.close();
        // get vectorSorter from the queue
        vectorSorter = segmentedVectorSorters.poll();
        return 0;
      } else if (sortedOutputRecords > 0) {
        // copy vectorSort's output to ExternalSortOperator's output
        copyVectorContainer(
            (VectorContainer) vectorSorter.getOutputVector(), output, 0, sortedOutputRecords);
      }
    }

    produceDataWatch.stop();
    return sortedOutputRecords;
  }

  /** When this operator starts spilling, notify others if the triggering is enabled. */
  private void notifyOthersOfSpill() {
    if (!oobSpillNotificationsEnabled) {
      return;
    }

    try {
      OutOfBandMessage.Payload payload =
          new OutOfBandMessage.Payload(
              ExtSortSpillNotificationMessage.newBuilder()
                  .setMemoryUse(allocator.getAllocatedMemory())
                  .build());
      for (CoordExecRPC.FragmentAssignment a : context.getAssignments()) {
        OutOfBandMessage message =
            new OutOfBandMessage(
                context.getFragmentHandle().getQueryId(),
                context.getFragmentHandle().getMajorFragmentId(),
                a.getMinorFragmentIdList(),
                config.getProps().getOperatorId(),
                context.getFragmentHandle().getMinorFragmentId(),
                payload,
                true);

        NodeEndpoint endpoint = context.getEndpointsIndex().getNodeEndpoint(a.getAssignmentIndex());
        context.getTunnelProvider().getExecTunnel(endpoint).sendOOBMessage(message);
      }
      oobSends++;
      updateStats(false);
      if (logger.isDebugEnabled()) {
        logger.debug(
            "notifyOthersOfSpill allocated memory {}. headroom {} oobsends {} oobreceives {}",
            allocator.getAllocatedMemory(),
            allocator.getHeadroom(),
            oobSends,
            oobReceives);
      }
    } catch (Exception ex) {
      logger.warn("Failure while attempting to notify others of spilling.", ex);
    }
  }

  /**
   * When a out of band message arrives, spill if we're within a factor of the other operator that
   * is spilling.
   */
  @Override
  public void workOnOOB(OutOfBandMessage message) {
    ++oobReceives;

    // ignore self notification.
    if (message.getSendingMinorFragmentId() == context.getFragmentHandle().getMinorFragmentId()) {
      oobDropLocal++;
      return;
    }

    if (vectorSorter.getState() != State.CAN_CONSUME) {
      oobDropWrongState++;
      return;
    }

    if (logger.isDebugEnabled()) {
      logger.debug(
          "workOnOOB allocated memory {}. headroom {} oobsends {} oobreceives {}",
          allocator.getAllocatedMemory(),
          allocator.getHeadroom(),
          oobSends,
          oobReceives);
    }

    // check to see if we're at the point where we want to spill.
    final ExtSortSpillNotificationMessage spill =
        message.getPayload(ExtSortSpillNotificationMessage.parser());
    final long allocatedMemoryBeforeSpilling = allocator.getAllocatedMemory();
    final double triggerFactor = context.getOptions().getOption(OOB_SORT_SPILL_TRIGGER_FACTOR);
    final double headroomRemaining =
        allocator.getHeadroom() * 1.0d / (allocator.getHeadroom() + allocator.getAllocatedMemory());
    if (allocatedMemoryBeforeSpilling < (spill.getMemoryUse() * triggerFactor)
        && headroomRemaining
            > context.getOptions().getOption(OOB_SORT_SPILL_TRIGGER_HEADROOM_FACTOR)) {
      if (logger.isDebugEnabled()) {
        logger.debug(
            "Skipping OOB spill trigger, current allocation is {}, which is not within the current factor of the spilling operator ({}) which has memory use of {}. Headroom is at {} which is greater than trigger headroom of {}",
            allocatedMemoryBeforeSpilling,
            triggerFactor,
            spill.getMemoryUse(),
            headroomRemaining,
            context.getOptions().getOption(OOB_SORT_SPILL_TRIGGER_HEADROOM_FACTOR));
      }
      oobDropUnderThreshold++;
      return;
    }

    ++oobSpills;
    updateStats(false);

    if (vectorSorter.isMicroSpillEnabled()) {
      vectorSorter.startMicroSpilling();
    } else {
      vectorSorter.rotateRuns();
    }
  }

  public void updateStats(boolean closed) {
    OperatorStats stats = context.getStats();
    vectorSorter.updateStats(closed);

    Map<String, Long> vectorSorterStats = vectorSorter.getStats(closed);

    if (!closed) {
      if (vectorSorter.memoryStatsAvailable()) {
        stats.setLongStat(
            ExternalSortStats.Metric.PEAK_BATCHES_IN_MEMORY,
            vectorSorterStats.get(ExternalSortStats.Metric.PEAK_BATCHES_IN_MEMORY.name()));
        stats.setLongStat(ExternalSortStats.Metric.OOB_SENDS, oobSends);
        stats.setLongStat(ExternalSortStats.Metric.OOB_RECEIVES, oobReceives);
        stats.setLongStat(ExternalSortStats.Metric.OOB_DROP_LOCAL, oobDropLocal);
        stats.setLongStat(ExternalSortStats.Metric.OOB_DROP_WRONG_STATE, oobDropWrongState);
        stats.setLongStat(ExternalSortStats.Metric.OOB_DROP_UNDER_THRESHOLD, oobDropUnderThreshold);
        stats.setLongStat(ExternalSortStats.Metric.OOB_SPILL, oobSpills);
      }
    }

    if (vectorSorter.diskStatsAvailable()) {
      stats.setLongStat(
          ExternalSortStats.Metric.SPILL_COUNT,
          vectorSorterStats.get(ExternalSortStats.Metric.SPILL_COUNT.name()));
      stats.setLongStat(
          ExternalSortStats.Metric.MERGE_COUNT,
          vectorSorterStats.get(ExternalSortStats.Metric.MERGE_COUNT.name()));
      stats.setLongStat(
          ExternalSortStats.Metric.MAX_BATCH_SIZE,
          vectorSorterStats.get(ExternalSortStats.Metric.MAX_BATCH_SIZE.name()));
      stats.setLongStat(
          ExternalSortStats.Metric.AVG_BATCH_SIZE,
          vectorSorterStats.get(ExternalSortStats.Metric.AVG_BATCH_SIZE.name()));
      stats.setLongStat(
          ExternalSortStats.Metric.SPILL_TIME_NANOS,
          vectorSorterStats.get(ExternalSortStats.Metric.SPILL_TIME_NANOS.name()));
      stats.setLongStat(
          ExternalSortStats.Metric.MERGE_TIME_NANOS,
          vectorSorterStats.get(ExternalSortStats.Metric.MERGE_TIME_NANOS.name()));
      stats.setLongStat(
          ExternalSortStats.Metric.BATCHES_SPILLED,
          vectorSorterStats.get(ExternalSortStats.Metric.BATCHES_SPILLED.name()));
      stats.setLongStat(
          ExternalSortStats.Metric.UNCOMPRESSED_BYTES_READ,
          vectorSorterStats.get(ExternalSortStats.Metric.UNCOMPRESSED_BYTES_READ.name()));
      stats.setLongStat(
          ExternalSortStats.Metric.UNCOMPRESSED_BYTES_WRITTEN,
          vectorSorterStats.get(ExternalSortStats.Metric.UNCOMPRESSED_BYTES_WRITTEN.name()));
      stats.setLongStat(
          ExternalSortStats.Metric.IO_BYTES_READ,
          vectorSorterStats.get(ExternalSortStats.Metric.IO_BYTES_READ.name()));
      stats.setLongStat(
          ExternalSortStats.Metric.TOTAL_SPILLED_DATA_SIZE,
          vectorSorterStats.get(ExternalSortStats.Metric.TOTAL_SPILLED_DATA_SIZE.name()));
      // if we use the old encoding path, we don't get the io bytes so we'll behave similar to
      // legacy, reporting pre-compressed size.
      stats.setLongStat(
          ExternalSortStats.Metric.IO_BYTES_WRITTEN,
          vectorSorterStats.get(ExternalSortStats.Metric.IO_BYTES_WRITTEN.name()));
      stats.setLongStat(
          ExternalSortStats.Metric.COMPRESSION_NANOS,
          vectorSorterStats.get(ExternalSortStats.Metric.COMPRESSION_NANOS.name()));
      stats.setLongStat(
          ExternalSortStats.Metric.DECOMPRESSION_NANOS,
          vectorSorterStats.get(ExternalSortStats.Metric.DECOMPRESSION_NANOS.name()));
      stats.setLongStat(
          ExternalSortStats.Metric.IO_READ_WAIT_NANOS,
          vectorSorterStats.get(ExternalSortStats.Metric.IO_READ_WAIT_NANOS.name()));
      stats.setLongStat(
          ExternalSortStats.Metric.IO_WRITE_WAIT_NANOS,
          vectorSorterStats.get(ExternalSortStats.Metric.IO_WRITE_WAIT_NANOS.name()));
      stats.setLongStat(
          ExternalSortStats.Metric.OOM_ALLOCATE_COUNT,
          vectorSorterStats.get(ExternalSortStats.Metric.OOM_ALLOCATE_COUNT.name()));
      stats.setLongStat(
          ExternalSortStats.Metric.OOM_COPY_COUNT,
          vectorSorterStats.get(ExternalSortStats.Metric.OOM_COPY_COUNT.name()));
      stats.setLongStat(
          ExternalSortStats.Metric.SPILL_COPY_NANOS,
          vectorSorterStats.get(ExternalSortStats.Metric.SPILL_COPY_NANOS.name()));
    }
    stats.setLongStat(
        ExternalSortStats.Metric.CAN_PRODUCE_MILLIS,
        produceDataWatch.elapsed(TimeUnit.MILLISECONDS));
    stats.setLongStat(
        ExternalSortStats.Metric.CAN_CONSUME_MILLIS,
        consumeDataWatch.elapsed(TimeUnit.MILLISECONDS));
    stats.setLongStat(
        ExternalSortStats.Metric.SETUP_MILLIS,
        vectorSorterStats.get(ExternalSortStats.Metric.SETUP_MILLIS.name()));
    stats.setLongStat(
        ExternalSortStats.Metric.NO_MORE_TO_CONSUME_MILLIS,
        noMoreToConsumeWatch.elapsed(TimeUnit.MILLISECONDS));
  }

  @Override
  public <OUT, IN, EXCEP extends Throwable> OUT accept(
      OperatorVisitor<OUT, IN, EXCEP> visitor, IN value) throws EXCEP {
    return visitor.visitSingleInput(this, value);
  }
}
