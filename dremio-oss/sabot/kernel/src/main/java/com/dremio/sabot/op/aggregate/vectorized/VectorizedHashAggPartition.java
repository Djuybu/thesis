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
package com.dremio.sabot.op.aggregate.vectorized;

import com.dremio.common.AutoCloseables;
import com.dremio.exec.record.selection.SelectionVector2;
import com.dremio.sabot.op.common.ht2.FixedBlockVector;
import com.dremio.sabot.op.common.ht2.HashTable;
import com.dremio.sabot.op.common.ht2.LBlockHashTable;
import com.dremio.sabot.op.common.ht2.PivotDef;
import com.dremio.sabot.op.common.ht2.ResizeListener;
import com.dremio.sabot.op.common.ht2.SpaceCheckListener;
import com.dremio.sabot.op.common.ht2.Unpivots;
import com.dremio.sabot.op.common.ht2.VariableBlockVector;
import com.dremio.sabot.op.join.vhash.spill.SV2UnsignedUtil;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import io.netty.util.internal.PlatformDependent;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.util.LargeMemoryUtil;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.types.Types;

public class VectorizedHashAggPartition implements SpaceCheckListener, AutoCloseable {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(VectorizedHashAggPartition.class);
  boolean spilled;
  final HashTable hashTable;
  final AccumulatorSet accumulator;
  final int blockWidth;
  private final boolean useNewFixedAccumulatorAllocation;
  private final int targetBatchSize;
  int records;
  ArrowBuf buffer;
  private VectorizedHashAggDiskPartition spillInfo;
  private String identifier;
  private final boolean decimalV2Enabled;
  private int varFieldLen = 0;
  private int varFieldCount = 0;

  private int[] batchSpaceUsage;
  private int[] batchValueCount;
  private final Stopwatch forceAccumTimer = Stopwatch.createUnstarted();
  private int numForceAccums = 0;
  private int[] blockRowCounts;
  private int currentOutputBlockIndex;
  private int maxHashTableBatchSize;

  /*
  When this VectorizedHashAggOperator picks this partition to output, it does it one batch at a time and serially.
  Below member keeps track of current batch index to output.
   */
  private int nextBatchToOutput = 0;
  private PivotDef pivot;
  private BufferAllocator allocator;
  private ArrowBuf sv2;
  private int sv2Index;
  private FixedBlockVector fbv;
  private VariableBlockVector vbv;
  private boolean isClosed;
  private int currentBlockStartRow;
  private boolean eod;

  // private ArrowBuf ordinals;

  /**
   * Create a partition. Used by {@link VectorizedHashAggOperator} at setup time to initialize all
   * partitions. The operator allocates all the data structures for a partition and invokes this
   * constructor.
   *
   * @param accumulator accumulator for the partition
   * @param hashTable hashtable for the partition
   * @param pivot PivotDef for the partition
   * @param decimalV2Enabled
   */
  public VectorizedHashAggPartition(
      final BufferAllocator allocator,
      final int maxHashTableBatchSize,
      final int targetBatchSize,
      final AccumulatorSet accumulator,
      final HashTable hashTable,
      final PivotDef pivot,
      final String identifier,
      final ArrowBuf buffer,
      boolean decimalV2Enabled,
      boolean useNewFixedAccumulatorAllocation) {
    Preconditions.checkArgument(
        hashTable != null, "Error: initializing a partition with invalid hash table");
    this.allocator = allocator;
    this.spilled = false;
    this.records = 0;
    this.hashTable = hashTable;
    if (hashTable instanceof LBlockHashTable) {
      ((LBlockHashTable) this.hashTable).registerSpaceCheckListener(this);
    }
    // In order to output the single block of hash table into multiple batches
    // the target batch size needs to rounded down to a multiple of 8. This eases the
    // slicing of validity buffer.
    this.targetBatchSize = targetBatchSize >> 3 << 3;
    this.accumulator = accumulator;
    this.pivot = pivot;
    this.blockWidth = pivot.getBlockWidth();
    this.spillInfo = null;
    this.identifier = identifier;
    this.buffer = buffer;
    this.decimalV2Enabled = decimalV2Enabled;
    batchSpaceUsage = new int[0];
    batchValueCount = new int[0];
    buffer.getReferenceManager().retain(1);
    currentOutputBlockIndex = -1;
    currentBlockStartRow = 0;
    eod = false;
    this.maxHashTableBatchSize = maxHashTableBatchSize;
    this.useNewFixedAccumulatorAllocation = useNewFixedAccumulatorAllocation;
  }

  /**
   * Get identifier for this partition
   *
   * @return partition identifier
   */
  public String getIdentifier() {
    return identifier;
  }

  @VisibleForTesting
  public ArrowBuf getBuffer() {
    return this.buffer;
  }

  @Override
  public void close() throws Exception {
    if (!isClosed) {
      // When there is a problem during setup, it is possible that the
      // close could be called more than once.
      AutoCloseables.close(hashTable, accumulator, buffer, sv2);
      this.buffer = null;
      isClosed = true;
    }
  }

  public boolean useNewFixedAccumulatorAllocation() {
    return this.useNewFixedAccumulatorAllocation;
  }

  void checkAndResizeHTBuffers(int numRecords) {
    if (sv2 == null) {
      int numRecordsToUse = Math.max(numRecords, maxHashTableBatchSize);
      try (AutoCloseables.RollbackCloseable rc = new AutoCloseables.RollbackCloseable(true)) {
        sv2 = rc.add(allocator.buffer(numRecordsToUse * SelectionVector2.RECORD_SIZE));
        rc.commit();
      } catch (RuntimeException ex) {
        throw ex;
      } catch (Exception ex) {
        throw new RuntimeException(ex);
      }
    }
    if (sv2.capacity() < numRecords * SelectionVector2.RECORD_SIZE) {
      try (AutoCloseables.RollbackCloseable rc = new AutoCloseables.RollbackCloseable(true)) {
        sv2.close();
        sv2 = rc.add(allocator.buffer(numRecords * SelectionVector2.RECORD_SIZE));
        rc.commit();
      } catch (RuntimeException ex) {
        throw ex;
      } catch (Exception ex) {
        throw new RuntimeException(ex);
      }
    }
  }

  void hashPivoted(int records, ArrowBuf keyFixed, ArrowBuf keyVar, long seed, ArrowBuf hashout8B) {
    hashTable.computeHash(records, keyFixed, keyVar, seed, hashout8B);
  }

  public int insertPivoted(
      int records,
      int recordsConsumed,
      ArrowBuf tableHash4B,
      ArrowBuf fixed,
      ArrowBuf variable,
      ArrowBuf ordinals) {
    int recordsToBeInserted = getRecordsToBeInserted();
    if (logger.isDebugEnabled()) {
      logger.debug(
          "Before insertPivoted VerifyBlocks returned records {} in {} blocks for partition {}",
          hashTable.verifyBlocks("Before insertPivoted"),
          hashTable.getCurrentNumberOfBlocks(),
          getIdentifier());
      logger.debug(
          "Inserting {} records into partition {}, Total records in this batch: {} Total records consumed: {} , sv2Size {}",
          recordsToBeInserted,
          this.getIdentifier(),
          records,
          recordsConsumed,
          sv2Index);
    }
    int recordsInserted =
        hashTable.addSv2(sv2, 0, recordsToBeInserted, fixed, variable, tableHash4B, ordinals);
    if (logger.isDebugEnabled()) {
      logger.debug(
          "After insertPivoted VerifyBlocks returned records {} in {} blocks for partition {}",
          hashTable.verifyBlocks("After insertPivoted"),
          hashTable.getCurrentNumberOfBlocks(),
          getIdentifier());
      logger.debug(
          "Inserted {} records of {} into partition {},  recordsInTheBatch: {} Total recordsConsumed: {} ",
          recordsInserted,
          recordsToBeInserted,
          this.getIdentifier(),
          records,
          recordsConsumed);
    }
    if (recordsInserted > 0) {
      long ordinalsAddr = ordinals.memoryAddress();
      long sv2Addr = sv2.memoryAddress();
      for (int recNum = 0; recNum < recordsInserted; recNum++) {
        int keyIndex =
            PlatformDependent.getShort(sv2Addr + (recNum * SelectionVector2.RECORD_SIZE));
        int ordinal = PlatformDependent.getInt(ordinalsAddr + (recNum * HashTable.ORDINAL_SIZE));
        appendRecord(ordinal, keyIndex + recordsConsumed);
      }
    }
    return recordsInserted;
  }

  void addToSV2(int ordinal) {
    SV2UnsignedUtil.writeAtOffset(sv2, sv2Index * SelectionVector2.RECORD_SIZE, ordinal);
    ++sv2Index;
  }

  void resetSv2Index() {
    sv2Index = 0;
  }

  void removeInsertedRecordsInSV2(int numInserted) {
    if (numInserted == 0) {
      return;
    }
    Preconditions.checkArgument(numInserted < sv2Index);

    // move the records to the start of the sv2
    int remainingRecords = sv2Index - numInserted;
    logger.debug("Moving sv2 index from {} to {} for length {} ", numInserted, 0, remainingRecords);
    for (int idx = 0; idx < remainingRecords; idx++) {
      int ordinal = SV2UnsignedUtil.readAtIndex(sv2, numInserted + idx);
      SV2UnsignedUtil.writeAtIndex(sv2, idx, ordinal);
    }
    sv2Index -= numInserted;
  }

  int getRecordsToBeInserted() {
    return sv2Index;
  }

  /**
   * Check if the partition has been spilled (1 or more times)
   *
   * @return true if partition has been spilled, false if partition has never been spilled.
   */
  public boolean isSpilled() {
    return spilled;
  }

  /**
   * Get the spill info for the partition.
   *
   * @return disk version of partition that has spill related info
   */
  public VectorizedHashAggDiskPartition getSpillInfo() {
    return spillInfo;
  }

  /**
   * Set spill info for the partition.
   *
   * @param spillInfo spill related information
   */
  public void setSpilled(final VectorizedHashAggDiskPartition spillInfo) {
    Preconditions.checkArgument(
        spillInfo != null, "Error: attempted to mark partition as spilled with invalid spill info");
    this.spilled = true;
    this.spillInfo = spillInfo;
  }

  /**
   * Get the size (in bytes) of the data structures inside the partition. This is used by {@link
   * VectorizedHashAggPartitionSpillHandler} when deciding the victim partition to be spilled.
   *
   * @return total size (in bytes) of all the partition structures
   */
  public long getSize() {
    long hashTableSize = hashTable.getSizeInBytes();
    if (hashTableSize > 0) {
      return hashTableSize + accumulator.getSizeInBytes();
    } else {
      return 0;
    }
  }

  /**
   * Downsize the partition's structures. Release all memory associated with partition' structures
   * except for what is needed for holding a single batch of data. We currently use this in two
   * places:
   *
   * <p>(1) after spilling this partition (2) after sending data back from the operator from this
   * partition
   *
   * <p>In both cases we don't want to release all memory associated with the partition since we may
   * not get it back and we need memory for starting the second iteration of the operator to process
   * spilled partitions.
   *
   * @throws Exception
   */
  public void resetToMinimumSize() throws Exception {
    if (logger.isDebugEnabled()) {
      logger.debug(
          "Calling resetToMinimumSize for partition {} containing {} batches ",
          getIdentifier(),
          hashTable.getCurrentNumberOfBlocks());
    }
    /* hashtable internally resets the corresponding accumulator to minimum size */
    hashTable.resetToMinimumSize();
    currentOutputBlockIndex = -1;
    currentBlockStartRow = 0;
    eod = false;
    setNextBatchToOutput(0);
  }

  /**
   * {@link VectorizedHashAggOperator} uses this function to record metadata about a new record that
   * has been inserted into this {@link LBlockHashTable} of this partition. Each partition maintains
   * an offset buffer to store a 8 byte tuple < 4 byte ordinal, 4 byte record index> for each record
   * inserted into the partition. Note that this buffer is always overwritten (as opposed to zeroed
   * out) for every cycle of consumeData()
   *
   * @param ordinal hash table ordinal of the newly inserted record
   * @param keyIndex absolute index of the record in incoming batch
   */
  public void appendRecord(int ordinal, int keyIndex) {
    long addrNext =
        buffer.memoryAddress()
            + (records * VectorizedHashAggOperator.PARTITIONINDEX_HTORDINAL_WIDTH);
    PlatformDependent.putInt(addrNext + VectorizedHashAggOperator.HTORDINAL_OFFSET, ordinal);
    PlatformDependent.putInt(addrNext + VectorizedHashAggOperator.KEYINDEX_OFFSET, keyIndex);
    ++records;
  }

  /**
   * The number of records in the partition are reset to 0 when we finish accumulation for that
   * partition
   *
   * <p>-- this could be done midway when we decide to spill this partition in
   * accumulateBeforeSpill() -- this will also be done at the end of consume cycle when we
   * accumulate for each partition in accumulateForAllPartitions()
   */
  public void resetRecords() {
    records = 0;
    /* Reset the batchSpaceUsage as well */
    resetBatchSpaceAndCount();
  }

  /**
   * Return the number of records in this partition
   *
   * @return records
   */
  public int getRecords() {
    return records;
  }

  /** Set the partition as not spilled. */
  void transitionToMemoryOnlyPartition() {
    Preconditions.checkArgument(
        spilled && spillInfo != null, "Error: expecting a spilled partition");
    this.spilled = false;
    this.spillInfo = null;
    this.records = 0;
  }

  /**
   * Update accumulator info for this partition. This is used by {@link VectorizedHashAggOperator}
   * when it starts the processing of spilled partitions. We need to update partition's accumulator
   * in two ways:
   *
   * <p>(1) Set the input vector for each accumulator. The new input vector is the one we
   * deserialized from spilled batch. (2) For count, count1, sum, $sum0 convert the accumulator
   * type. For example an pre-spill IntSumAccumulator will become BigIntSumAccumulator for
   * post-spill processing.
   *
   * @param accumulatorTypes
   * @param accumulatorVectors
   */
  public void updateAccumulator(
      final byte[] accumulatorTypes,
      final FieldVector[] accumulatorVectors,
      final BufferAllocator computationVectorAllocator) {
    Preconditions.checkArgument(
        accumulatorTypes.length == accumulatorVectors.length,
        "Error: detected inconsistent number of accumulators");
    final Accumulator[] partitionAccumulators = this.accumulator.getChildren();
    for (int i = 0; i < accumulatorTypes.length; i++) {
      final Accumulator partitionAccumulator = partitionAccumulators[i];
      final FieldVector deserializedAccumulator = accumulatorVectors[i];
      final byte accumulatorType = accumulatorTypes[i];
      if (accumulatorType == AccumulatorBuilder.AccumulatorType.COUNT1.ordinal()) {
        partitionAccumulators[i] =
            new SumAccumulators.BigIntSumAccumulator(
                (CountOneAccumulator) partitionAccumulator,
                deserializedAccumulator,
                hashTable.getActualValuesPerBatch(),
                computationVectorAllocator);
      } else if (accumulatorType == AccumulatorBuilder.AccumulatorType.COUNT.ordinal()) {
        partitionAccumulators[i] =
            new SumAccumulators.BigIntSumAccumulator(
                (CountColumnAccumulator) partitionAccumulator,
                deserializedAccumulator,
                hashTable.getActualValuesPerBatch(),
                computationVectorAllocator);
      } else if (accumulatorType == AccumulatorBuilder.AccumulatorType.SUM.ordinal()) {
        updateSumAccumulator(
            deserializedAccumulator, partitionAccumulators,
            i, computationVectorAllocator);
      } else if (accumulatorType == AccumulatorBuilder.AccumulatorType.SUM0.ordinal()) {
        updateSumZeroAccumulator(
            deserializedAccumulator, partitionAccumulators,
            i, computationVectorAllocator);
      } else if (accumulatorType == AccumulatorBuilder.AccumulatorType.HLL.ordinal()) {
        /*
         * HLL NDV results are serialized and saved in variable width vector. In order to
         * merge them across spilled batches, need to unionize them.
         */
        partitionAccumulators[i] =
            new NdvAccumulators.NdvUnionAccumulators(
                (BaseNdvAccumulator) partitionAccumulator,
                deserializedAccumulator,
                hashTable.getActualValuesPerBatch(),
                computationVectorAllocator);
        try {
          /* This never fail */
          partitionAccumulator.close();
        } catch (Exception e) {
          logger.error("An unexpected error was thrown while closing partitionAccumulator", e);
          e.printStackTrace();
        }
      } else if (accumulatorType == AccumulatorBuilder.AccumulatorType.HLL_MERGE.ordinal()) {
        partitionAccumulators[i].setInput(deserializedAccumulator);
      } else if (accumulatorType == AccumulatorBuilder.AccumulatorType.LISTAGG.ordinal()
          || accumulatorType == AccumulatorBuilder.AccumulatorType.LOCAL_LISTAGG.ordinal()) {
        /*
         * LISTAGG results are saved as ListVector on top of BaseVariableWidthVector. In order to
         * merge them across spilled batches, need to call LISTAGG_MERGE.
         */
        partitionAccumulators[i] =
            new ListAggMergeAccumulator(
                (ListAggAccumulator) partitionAccumulator,
                deserializedAccumulator,
                hashTable.getActualValuesPerBatch(),
                computationVectorAllocator,
                accumulatorType == AccumulatorBuilder.AccumulatorType.LOCAL_LISTAGG.ordinal());
        try {
          /* This never fail */
          partitionAccumulator.close();
        } catch (Exception e) {
          logger.error("An unexpected error was thrown while closing partitionAccumulator", e);
          e.printStackTrace();
        }
      } else if (accumulatorType == AccumulatorBuilder.AccumulatorType.LISTAGG_MERGE.ordinal()) {
        partitionAccumulators[i].setInput(deserializedAccumulator);
      } else {
        /* handle MIN, MAX */
        Preconditions.checkArgument(
            accumulatorType == AccumulatorBuilder.AccumulatorType.MAX.ordinal()
                || accumulatorType == AccumulatorBuilder.AccumulatorType.MIN.ordinal(),
            "Error: unexpected type of accumulator. Expecting min or max");
        updateMinMaxAccumulator(
            deserializedAccumulator, partitionAccumulators,
            i, computationVectorAllocator);
      }
    }
    /*
     * accumulators in accmulator.children might have updated. Recalculate the actual
     * fixedLen and varLen accumulators list.
     */
    accumulator.updateVarlenAndFixedAccumusLst();
  }

  private void updateMinMaxAccumulator(
      final FieldVector deserializedAccumulator,
      final Accumulator[] partitionAccumulators,
      final int index,
      final BufferAllocator computationVectorAllocator) {

    /* We only need to handle DECIMAL in case decimal v2 is disabled.
     * For example, Int(Min/Max)Accumulator remains Int(Min/Max)Accumulator, Float(Min/Max)Accumulator
     * remains Float(Min/Max)Accumulator and similarly for Double(Min/Max), BigInt(Min/Max) etc.
     * The accumulator vector that was spilled now becomes the new input vector for
     * post-spill processing. However, for decimal min/max we store the output
     * (min and max values) in an accumulator vector of type Double. So for post-spill
     * processing Decimal(Min/Max)Accumulator becomes Double(Min/Max)Accumulator
     */
    final Accumulator partitionAccumulator = partitionAccumulators[index];
    if (!decimalV2Enabled) {
      if (partitionAccumulator instanceof MaxAccumulators.DecimalMaxAccumulator) {
        Preconditions.checkArgument(
            partitionAccumulator.getInput() instanceof DecimalVector,
            "Error: expecting decimal vector");
        partitionAccumulators[index] =
            new MaxAccumulators.DoubleMaxAccumulator(
                (MaxAccumulators.DecimalMaxAccumulator) partitionAccumulator,
                deserializedAccumulator,
                hashTable.getActualValuesPerBatch(),
                computationVectorAllocator);
        return;
      } else if (partitionAccumulator instanceof MinAccumulators.DecimalMinAccumulator) {
        Preconditions.checkArgument(
            partitionAccumulator.getInput() instanceof DecimalVector,
            "Error: expecting decimal vector");
        partitionAccumulators[index] =
            new MinAccumulators.DoubleMinAccumulator(
                (MinAccumulators.DecimalMinAccumulator) partitionAccumulator,
                deserializedAccumulator,
                hashTable.getActualValuesPerBatch(),
                computationVectorAllocator);
        return;
      }
      /* fall through in all other cases */
    }
    partitionAccumulator.setInput(deserializedAccumulator);
  }

  private void updateSumAccumulator(
      final FieldVector deserializedAccumulator,
      final Accumulator[] partitionAccumulators,
      final int index,
      final BufferAllocator computationVectorAllocator) {
    final Accumulator partitionAccumulator = partitionAccumulators[index];
    Types.MinorType type = deserializedAccumulator.getMinorType();
    switch (type) {
      case BIGINT:
        {
          if (partitionAccumulator.getInput() instanceof BigIntVector) {
            /* We started with BigIntSumAccumulator so type doesn't change.
             * Just set the input vector to the accumulator vector read from
             * spilled batch
             */
            Preconditions.checkArgument(
                partitionAccumulator instanceof SumAccumulators.BigIntSumAccumulator,
                "Error: expecting bigint sum accumulator");
            partitionAccumulator.setInput(deserializedAccumulator);
          } else {
            /* We started with IntSumAccumulator that has input vector of type INT
             * and accumulator vector of type BIGINT and the output vector into which
             * we will eventually transfer contents also BIGINT. We spilled the
             * accumulator vector. So IntSumAccumulator now becomes BigIntSumAccumulator
             * for post-spill processing with the accumulator vector read from spilled batch
             * acting as new input vector for BigIntSumAccumulator
             */
            Preconditions.checkArgument(
                partitionAccumulator instanceof SumAccumulators.IntSumAccumulator,
                "Error: expecting int sum accumulator");
            partitionAccumulators[index] =
                new SumAccumulators.BigIntSumAccumulator(
                    (SumAccumulators.IntSumAccumulator) partitionAccumulator,
                    deserializedAccumulator,
                    hashTable.getActualValuesPerBatch(),
                    computationVectorAllocator);
          }

          break;
        }

      case FLOAT8:
        {
          if (partitionAccumulator.getInput() instanceof Float8Vector) {
            /* We started with DoubleSumAccumulator so type doesn't change.
             * Just set the input vector to the accumulator vector read from
             * spilled batch
             */
            Preconditions.checkArgument(
                partitionAccumulator instanceof SumAccumulators.DoubleSumAccumulator,
                "Error: expecting double sum accumulator");
            partitionAccumulator.setInput(deserializedAccumulator);
          } else if (partitionAccumulator.getInput() instanceof Float4Vector) {
            /* We started with FloatSumAccumulator that has input vector of type FLOAT
             * and accumulator vector of type DOUBLE and the output vector into which
             * we will eventually transfer contents also DOUBLE. We spilled the
             * accumulator vector. So FloatSumAccumulator now becomes DoubleSumAccumulator
             * for post-spill processing with the accumulator vector read from spilled batch
             * acting as new input vector for DoubleSumAccumulator
             */
            Preconditions.checkArgument(
                partitionAccumulator instanceof SumAccumulators.FloatSumAccumulator,
                "Error: expecting float sum accumulator");
            partitionAccumulators[index] =
                new SumAccumulators.DoubleSumAccumulator(
                    (SumAccumulators.FloatSumAccumulator) partitionAccumulator,
                    deserializedAccumulator,
                    hashTable.getActualValuesPerBatch(),
                    computationVectorAllocator);
          } else if (partitionAccumulator.getInput() instanceof DecimalVector) {
            /* We started with DecimalSumAccumulator that has input vector of type DECIMAL
             * and accumulator vector of type DOUBLE and the output vector into which
             * we will eventually transfer contents also DOUBLE. We spilled the
             * accumulator vector. So DecimalSumAccumulator now becomes DoubleSumAccumulator
             * for post-spill processing with the accumulator vector read from spilled batch
             * acting as new input vector for DoubleSumAccumulator
             */
            // ensure that if this happens decimal complete is turned off.
            Preconditions.checkArgument(!decimalV2Enabled);
            Preconditions.checkArgument(
                partitionAccumulator instanceof SumAccumulators.DecimalSumAccumulator,
                "Error: expecting decimal sum accumulator");
            partitionAccumulators[index] =
                new SumAccumulators.DoubleSumAccumulator(
                    (SumAccumulators.DecimalSumAccumulator) partitionAccumulator,
                    deserializedAccumulator,
                    hashTable.getActualValuesPerBatch(),
                    computationVectorAllocator);
          }

          break;
        }

      case DECIMAL:
        {
          Preconditions.checkArgument(
              partitionAccumulator instanceof SumAccumulators.DecimalSumAccumulatorV2,
              "Error: expecting decimal sum accumulator");
          partitionAccumulator.setInput(deserializedAccumulator);
          break;
        }

      default:
        {
          /* we should not be here */
          throw new IllegalStateException("Error: incorrect type of deserialized sum accumulator");
        }
    }
  }

  private void updateSumZeroAccumulator(
      final FieldVector deserializedAccumulator,
      final Accumulator[] partitionAccumulators,
      final int index,
      final BufferAllocator computationVectorAllocator) {
    final Accumulator partitionAccumulator = partitionAccumulators[index];
    Types.MinorType type = deserializedAccumulator.getMinorType();
    switch (type) {
      case BIGINT:
        {
          if (partitionAccumulator.getInput() instanceof BigIntVector) {
            /* We started with BigIntSumZeroAccumulator so type doesn't change.
             * Just set the input vector to the accumulator vector read from
             * spilled batch
             */
            Preconditions.checkArgument(
                partitionAccumulator instanceof SumZeroAccumulators.BigIntSumZeroAccumulator,
                "Error: expecting bigint sumzero accumulator");
            partitionAccumulator.setInput(deserializedAccumulator);
          } else {
            /* We started with IntSumZeroAccumulator that has input vector of type INT
             * and accumulator vector of type BIGINT and the output vector into which
             * we will eventually transfer contents also BIGINT. We spilled the
             * accumulator vector. So IntSumZeroAccumulator now becomes BigIntSumZeroAccumulator
             * for post-spill processing with the accumulator vector read from spilled batch
             * acting as new input vector for BigIntSumZeroAccumulator
             */
            Preconditions.checkArgument(
                partitionAccumulator instanceof SumZeroAccumulators.IntSumZeroAccumulator,
                "Error: expecting int sumzero accumulator");
            partitionAccumulators[index] =
                new SumZeroAccumulators.BigIntSumZeroAccumulator(
                    (SumZeroAccumulators.IntSumZeroAccumulator) partitionAccumulator,
                    deserializedAccumulator,
                    hashTable.getActualValuesPerBatch(),
                    computationVectorAllocator);
          }

          break;
        }

      case FLOAT8:
        {
          if (partitionAccumulator.getInput() instanceof Float8Vector) {
            /* We started with DoubleSumZeroAccumulator so type doesn't change.
             * Just set the input vector to the accumulator vector read from
             * spilled batch
             */
            Preconditions.checkArgument(
                partitionAccumulator instanceof SumZeroAccumulators.DoubleSumZeroAccumulator,
                "Error: expecting double sum zero accumulator");
            partitionAccumulator.setInput(deserializedAccumulator);
          } else if (partitionAccumulator.getInput() instanceof Float4Vector) {
            /* We started with FloatSumZeroAccumulator that has input vector of type FLOAT
             * and accumulator vector of type DOUBLE and the output vector into which
             * we will eventually transfer contents also DOUBLE. We spilled the
             * accumulator vector. So FloatZeroAccumulator now becomes DoubleSumZeroAccumulator
             * for post-spill processing with the accumulator vector read from spilled batch
             * acting as new input vector for DoubleSumZeroAccumulator
             */
            Preconditions.checkArgument(
                partitionAccumulator instanceof SumZeroAccumulators.FloatSumZeroAccumulator,
                "Error: expecting float sum zero accumulator");
            partitionAccumulators[index] =
                new SumZeroAccumulators.DoubleSumZeroAccumulator(
                    (SumZeroAccumulators.FloatSumZeroAccumulator) partitionAccumulator,
                    deserializedAccumulator,
                    hashTable.getActualValuesPerBatch(),
                    computationVectorAllocator);
          } else if (partitionAccumulator.getInput() instanceof DecimalVector) {
            /* We started with DecimalSumZeroAccumulator that has input vector of type DECIMAL
             * and accumulator vector of type DOUBLE and the output vector into which
             * we will eventually transfer contents also DOUBLE. We spilled the
             * accumulator vector. So DecimalSumZeroAccumulator now becomes DoubleSumAccumulator
             * for post-spill processing with the accumulator vector read from spilled batch
             * acting as new input vector for DoubleSumAccumulator
             */
            // ensure that if this happens decimal complete is turned off.
            Preconditions.checkArgument(!decimalV2Enabled);
            Preconditions.checkArgument(
                partitionAccumulator instanceof SumZeroAccumulators.DecimalSumZeroAccumulator,
                "Error: expecting decimal sum zero accumulator");
            partitionAccumulators[index] =
                new SumZeroAccumulators.DoubleSumZeroAccumulator(
                    (SumZeroAccumulators.DecimalSumZeroAccumulator) partitionAccumulator,
                    deserializedAccumulator,
                    hashTable.getActualValuesPerBatch(),
                    computationVectorAllocator);
          }
          break;
        }

      case DECIMAL:
        {
          Preconditions.checkArgument(
              partitionAccumulator instanceof SumZeroAccumulators.DecimalSumZeroAccumulatorV2,
              "Error: expecting decimal sum zero accumulator");
          partitionAccumulator.setInput(deserializedAccumulator);
          break;
        }

      default:
        {
          /* we should not be here */
          throw new IllegalStateException(
              "Error: incorrect type of deserialized sumzero accumulator");
        }
    }
  }

  public void updateIdentifier(final String identifier) {
    this.identifier = identifier;
  }

  public int getNumberOfBlocks() {
    return hashTable.getCurrentNumberOfBlocks();
  }

  @VisibleForTesting
  public HashTable getHashTable() {
    return hashTable;
  }

  @VisibleForTesting
  public AccumulatorSet getAccumulator() {
    return accumulator;
  }

  private void reallocateBatchSpaceAndCount() {
    final int[] newBatches = new int[batchSpaceUsage.length + 64];
    final int[] newBatchCounts = new int[batchValueCount.length + 64];

    for (int i = 0; i < batchSpaceUsage.length; ++i) {
      newBatches[i] = batchSpaceUsage[i];
      newBatchCounts[i] = batchValueCount[i];
    }
    batchSpaceUsage = newBatches;
    batchValueCount = newBatchCounts;
  }

  private int getBatchUsedSpace(int batchIndex) {
    while (batchIndex >= batchSpaceUsage.length) {
      reallocateBatchSpaceAndCount();
    }
    Preconditions.checkArgument(batchIndex < batchSpaceUsage.length);
    return batchSpaceUsage[batchIndex];
  }

  private int getBatchValueCounts(final int batchIndex) {
    if (batchIndex >= batchValueCount.length) {
      reallocateBatchSpaceAndCount();
      Preconditions.checkArgument(batchIndex < batchValueCount.length);
    }
    return batchValueCount[batchIndex];
  }

  public void addToBatchSpaceAndCount(final int batchIndex, final int space, final int count) {
    /* When this function called, batchSpaceUsage and batchValueCount already allocated */
    batchSpaceUsage[batchIndex] += space;
    batchValueCount[batchIndex] += count;
  }

  private void resetBatchSpaceAndCount() {
    Arrays.fill(batchSpaceUsage, 0);
    Arrays.fill(batchValueCount, 0);
  }

  public void setVarFieldLengthAndCount(final int varFieldLen, final int varFieldCount) {
    this.varFieldLen = varFieldLen;
    this.varFieldCount = varFieldCount;
  }

  @Override
  public boolean resizeListenerHasSpace(
      ResizeListener resizeListener, final int batchIndex, final int offsetInBatch, long seed) {
    if (varFieldLen == -1) {
      return true;
    }

    if (resizeListener.hasSpace(
        varFieldLen + getBatchUsedSpace(batchIndex),
        varFieldCount + getBatchValueCounts(batchIndex),
        batchIndex,
        offsetInBatch)) {
      return true;
    }

    /* Not enough space, so try force accumulate all the saved records and recheck it resizer has enough space. */
    forceAccumulateAndCompact(resizeListener, batchIndex, varFieldLen);
    if (resizeListener.hasSpace(
        varFieldLen + getBatchUsedSpace(batchIndex),
        varFieldCount + getBatchValueCounts(batchIndex),
        batchIndex,
        offsetInBatch)) {
      return true;
    }

    /* Still not enough space. Splice the batch and return false for hashtable to retry the insert. */
    int newBatchIndex = hashTable.splice(batchIndex, seed);
    spliceBatchSpaceUsageAndCount(batchIndex, newBatchIndex);
    return false;
  }

  /**
   * Batch 1 was spliced into two. Let batchSpaceUsage and batchValueCount arrays reflect that.
   *
   * @param batchIndex1
   * @param batchIndex2
   */
  private void spliceBatchSpaceUsageAndCount(final int batchIndex1, final int batchIndex2) {
    if (batchIndex1 >= this.batchSpaceUsage.length || batchIndex2 >= batchSpaceUsage.length) {
      reallocateBatchSpaceAndCount();
    }
    final int originalCount = batchValueCount[batchIndex1];
    final int originalSpace = batchSpaceUsage[batchIndex1];

    batchSpaceUsage[batchIndex2] = originalSpace / 2;
    batchSpaceUsage[batchIndex1] = originalSpace - (originalSpace / 2);

    batchValueCount[batchIndex2] = originalCount / 2;
    batchValueCount[batchIndex1] = originalCount - (originalCount / 2);
  }

  private void forceAccumulateAndCompact(
      ResizeListener resizeListener, final int batchIndex, final int nextRecSize) {
    ++numForceAccums;
    forceAccumTimer.start();

    if (getRecords() > 0) {
      if (logger.isDebugEnabled()) {
        logger.debug(
            "Before forceAccumulateAndCompact VerifyBlocks returned records {} in {} blocks for partition {}",
            hashTable.verifyBlocks("Before forceAccumulateAndCompact"),
            hashTable.getCurrentNumberOfBlocks(),
            getIdentifier());
      }
      resizeListener.accumulate(
          getBuffer().memoryAddress(),
          getRecords(),
          hashTable.getBitsInChunk(),
          hashTable.getChunkOffsetMask());
      if (logger.isDebugEnabled()) {
        logger.debug(
            "After forceAccumulateAndCompact VerifyBlocks returned records {} in {} blocks for partition {}",
            hashTable.verifyBlocks("After forceAccumulateAndCompact"),
            hashTable.getCurrentNumberOfBlocks(),
            getIdentifier());
      }
      resetRecords();
    }

    resizeListener.compact(batchIndex, nextRecSize);

    forceAccumTimer.stop();
  }

  public int getForceAccumCount() {
    return numForceAccums;
  }

  public long getForceAccumTime(TimeUnit unit) {
    return forceAccumTimer.elapsed(unit);
  }

  public int getNextBatchToOutput() {
    return nextBatchToOutput;
  }

  public void setNextBatchToOutput(int nextBatchToOutput) {
    this.nextBatchToOutput = nextBatchToOutput;
  }

  public int outputPartitions(Stopwatch unpivotWatch) throws Exception {
    if (currentOutputBlockIndex == -1) {
      blockRowCounts = hashTable.getRecordsInBlocks();
      if (blockRowCounts == null) {
        return -1;
      }
      currentOutputBlockIndex = 0;
      setNextBatchToOutput(currentOutputBlockIndex);
    }
    if (currentOutputBlockIndex == blockRowCounts.length) {
      return -1;
    }

    int blockIndex = currentOutputBlockIndex;
    int outputRecordsToPump = 0;
    int blockStartRow = 0;
    outputRecordsToPump = blockRowCounts[blockIndex];
    if (outputRecordsToPump == 0) {
      return -1;
    }
    unpivotWatch.start();
    if (logger.isDebugEnabled()) {
      logger.debug(
          "Output partition name: {} Block: {} of {} Requested records: {}",
          getIdentifier(),
          currentOutputBlockIndex,
          blockRowCounts.length,
          outputRecordsToPump);
    }

    /* unpivot GROUP BY key columns for one or more batches into corresponding vectors in outgoing container */
    int numRecords = unpivot(blockIndex, blockStartRow, outputRecordsToPump);
    unpivotWatch.stop();
    if (logger.isDebugEnabled()) {
      logger.debug(
          "Output partition name: {} Block: {} of {}  Requested records: {} OutputRecords : {}",
          getIdentifier(),
          blockIndex,
          blockRowCounts.length,
          outputRecordsToPump,
          numRecords);
    }
    int[] outputRecordsInBatches = new int[1];
    outputRecordsInBatches[0] = numRecords;
    if (logger.isDebugEnabled()) {
      logger.debug(
          "Output accumulator using BatchIndex {} and outputRecordsIn size {}",
          getNextBatchToOutput(),
          outputRecordsInBatches.length);
    }
    Preconditions.checkState(
        numRecords == outputRecordsToPump,
        "Unpivot returned %s records, but expected to return %s for partition %s",
        numRecords,
        outputRecordsToPump,
        getIdentifier());
    accumulator.output(getNextBatchToOutput(), outputRecordsInBatches);
    currentOutputBlockIndex = ++blockIndex;
    setNextBatchToOutput(currentOutputBlockIndex);
    return numRecords;
  }

  /*public int outputPartitions(Stopwatch unpivotWatch) throws Exception {
    if (eod) {
      return -1;
    }
    if (currentOutputBlockIndex == -1) {
      blockRowCounts = hashTable.getRecordsInBlocks();
      if (blockRowCounts == null) {
        eod = true;
        return -1;
      }
      // Empty hash table
      if (blockRowCounts[0] == 0) {
        eod = true;
        return -1;
      }
      currentOutputBlockIndex = 0;
    }
    // When the block contains more entries, output in more than batch with each batch
    // containing targetBatchSize number of records. The last batch may contain fewer rows
    // than the target batch size. Merging blocks to output targetBatchSize records is cumbersome
    // and may turn it to be inefficient.
    int outputRecordsToPump = blockRowCounts[currentOutputBlockIndex] - currentBlockStartRow;
    if (outputRecordsToPump >= targetBatchSize) {
      outputRecordsToPump = targetBatchSize;
    }

    unpivotWatch.start();
    // unpivot GROUP BY key columns for one or more batches into corresponding vectors in outgoing container
    int recordsToOutput =
        unpivot(currentOutputBlockIndex, currentBlockStartRow, outputRecordsToPump);
    unpivotWatch.stop();
    if (logger.isDebugEnabled()) {
      logger.debug(
          "Output partition name: {} Block: {} of {}  Requested records: {} from {}, OutputRecords : {}",
          getIdentifier(),
          currentOutputBlockIndex,
          blockRowCounts.length,
          outputRecordsToPump,
          currentBlockStartRow,
          recordsToOutput);
    }
    Preconditions.checkState(
        recordsToOutput == outputRecordsToPump,
        "Unpivot returned %s records, but expected to return %s for partition %s",
        recordsToOutput,
        outputRecordsToPump,
        getIdentifier());
    accumulator.output(
        currentOutputBlockIndex,
        currentBlockStartRow,
        recordsToOutput,
        blockRowCounts[currentOutputBlockIndex]);
    int remainRecordsInBlock =
        blockRowCounts[currentOutputBlockIndex] - (currentBlockStartRow + recordsToOutput);
    if (remainRecordsInBlock == 0) {
      currentBlockStartRow = 0;
      currentOutputBlockIndex++;
    } else {
      currentBlockStartRow = blockRowCounts[currentOutputBlockIndex] - remainRecordsInBlock;
    }
    if (currentOutputBlockIndex == blockRowCounts.length && remainRecordsInBlock == 0) {
      eod = true;
    }
    return recordsToOutput;
  }
  */

  public int unpivot(int startBatchIndex, int startRow, int outputRecordsToPump) throws Exception {
    int numRecords = 0;
    ArrowBuf fixedKeyBuffer = null;
    ArrowBuf variableKeyBuffer = null;

    List<ArrowBuf> keyBuffers = hashTable.getKeyBuffers(startBatchIndex);
    fixedKeyBuffer = keyBuffers.get(0);
    int fixedKeyBufferLength = LargeMemoryUtil.checkedCastToInt(fixedKeyBuffer.readableBytes());
    int variableKeyBufferLength = 0;
    if (keyBuffers.size() > 1) {
      variableKeyBuffer = keyBuffers.get(1);
      variableKeyBufferLength = LargeMemoryUtil.checkedCastToInt(variableKeyBuffer.readableBytes());
    }
    numRecords = fixedKeyBufferLength / blockWidth;

    if (numRecords == 0) {
      Preconditions.checkState(
          numRecords != 0,
          "Returned records is 0, but the number of records in the block is %s ",
          blockRowCounts[startBatchIndex]);
    }
    Preconditions.checkState(
        numRecords <= outputRecordsToPump,
        "Returned more records than requested, Requested records %s, Returned Records %s",
        outputRecordsToPump,
        numRecords);
    // Unpivot the keys for build side into output
    fbv = new FixedBlockVector(fixedKeyBuffer, blockWidth);
    vbv = null;
    if (variableKeyBufferLength > 0) {
      vbv = new VariableBlockVector(variableKeyBuffer, pivot.getVariableCount());
    }
    Unpivots.unpivot(pivot, fbv, vbv, startRow, numRecords);
    return numRecords;
  }
}
