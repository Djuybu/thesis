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

import static com.dremio.sabot.op.aggregate.vectorized.VectorizedHashAggOperator.HTORDINAL_OFFSET;
import static com.dremio.sabot.op.aggregate.vectorized.VectorizedHashAggOperator.KEYINDEX_OFFSET;
import static com.dremio.sabot.op.aggregate.vectorized.VectorizedHashAggOperator.PARTITIONINDEX_HTORDINAL_WIDTH;

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.util.DecimalUtils;
import io.netty.util.internal.PlatformDependent;
import java.math.BigDecimal;
import java.util.Objects;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BaseValueVector;
import org.apache.arrow.vector.BaseVariableWidthVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.MutableVarcharVector;
import org.apache.arrow.vector.holders.NullableVarCharHolder;

public class SingleValueAccumulators {
  public static class IntSingleValueAccumulator extends BaseSingleAccumulator {
    private static final long INIT_VALUE = -1L;
    private static final int NULL_VALUE = 0;
    private static final int WIDTH_INPUT = 4;
    private static final int WIDTH_ACCUMULATOR = 4;

    public IntSingleValueAccumulator(
        FieldVector input,
        FieldVector output,
        FieldVector transferVector,
        int maxValuesPerBatch,
        BufferAllocator computationVectorAllocator) {
      super(
          input,
          output,
          transferVector,
          AccumulatorBuilder.AccumulatorType.SINGLE_VALUE,
          maxValuesPerBatch,
          computationVectorAllocator);
    }

    @Override
    void initialize(FieldVector vector) {
      setNullAndValue(vector, INIT_VALUE);
    }

    @Override
    public void accumulate(
        final long memoryAddr, final int count, final int bitsInChunk, final int chunkOffsetMask) {
      accumulateHelper(
          this,
          memoryAddr,
          count,
          bitsInChunk,
          chunkOffsetMask,
          WIDTH_ACCUMULATOR,
          WIDTH_INPUT,
          (inputVector, srcAddr, accAddr, newBitValue, oldBitValue) -> {
            final int newVal = PlatformDependent.getInt(srcAddr);
            final int oldVal = PlatformDependent.getInt(accAddr);
            checkSingleValue(oldBitValue, Objects.equals(oldVal, NULL_VALUE));
            PlatformDependent.putInt(accAddr, newBitValue ? newVal : NULL_VALUE);
          });
    }
  }

  public static class FloatSingleValueAccumulator extends BaseSingleAccumulator {
    private static final int INIT_VALUE = Float.floatToRawIntBits(-Float.MAX_VALUE);
    private static final float NULL_VALUE = 0;
    private static final int WIDTH_INPUT = 4;
    private static final int WIDTH_ACCUMULATOR = 4;

    public FloatSingleValueAccumulator(
        FieldVector input,
        FieldVector output,
        FieldVector transferVector,
        int maxValuesPerBatch,
        BufferAllocator computationVectorAllocator) {
      super(
          input,
          output,
          transferVector,
          AccumulatorBuilder.AccumulatorType.SINGLE_VALUE,
          maxValuesPerBatch,
          computationVectorAllocator);
    }

    @Override
    void initialize(FieldVector vector) {
      setNullAndValue(vector, INIT_VALUE);
    }

    @Override
    public void accumulate(
        final long memoryAddr, final int count, final int bitsInChunk, final int chunkOffsetMask) {
      accumulateHelper(
          this,
          memoryAddr,
          count,
          bitsInChunk,
          chunkOffsetMask,
          WIDTH_ACCUMULATOR,
          WIDTH_INPUT,
          (inputVector, srcAddr, accAddr, newBitValue, oldBitValue) -> {
            final float newVal = Float.intBitsToFloat(PlatformDependent.getInt(srcAddr));
            final float oldVal = Float.intBitsToFloat(PlatformDependent.getInt(accAddr));
            checkSingleValue(oldBitValue, Objects.equals(oldVal, NULL_VALUE));
            PlatformDependent.putInt(
                accAddr, Float.floatToRawIntBits(newBitValue ? newVal : NULL_VALUE));
          });
    }
  }

  public static class BigIntSingleValueAccumulator extends BaseSingleAccumulator {
    private static final long INIT_VALUE = -1L;
    private static final long NULL_VALUE = 0;
    private static final int WIDTH_INPUT = 8;
    private static final int WIDTH_ACCUMULATOR = 8;

    public BigIntSingleValueAccumulator(
        FieldVector input,
        FieldVector output,
        FieldVector transferVector,
        int maxValuesPerBatch,
        BufferAllocator computationVectorAllocator) {
      super(
          input,
          output,
          transferVector,
          AccumulatorBuilder.AccumulatorType.SINGLE_VALUE,
          maxValuesPerBatch,
          computationVectorAllocator);
    }

    @Override
    void initialize(FieldVector vector) {
      setNullAndValue(vector, INIT_VALUE);
    }

    @Override
    public void accumulate(
        final long memoryAddr, final int count, final int bitsInChunk, final int chunkOffsetMask) {
      accumulateHelper(
          this,
          memoryAddr,
          count,
          bitsInChunk,
          chunkOffsetMask,
          WIDTH_ACCUMULATOR,
          WIDTH_INPUT,
          (inputVector, srcAddr, accAddr, newBitValue, oldBitValue) -> {
            final long newVal = PlatformDependent.getLong(srcAddr);
            final long oldVal = PlatformDependent.getLong(accAddr);
            checkSingleValue(oldBitValue, Objects.equals(oldVal, NULL_VALUE));
            PlatformDependent.putLong(accAddr, newBitValue ? newVal : NULL_VALUE);
          });
    }
  }

  public static class DecimalSingleValueAccumulator extends BaseSingleAccumulator {
    private static final long INIT_VALUE = Double.doubleToLongBits(-Double.MAX_VALUE);
    private static final BigDecimal NULL_VALUE = BigDecimal.ZERO;
    private static final int WIDTH_INPUT = 16;
    private static final int WIDTH_ACCUMULATOR = 8;
    private byte[] valBuf = new byte[WIDTH_INPUT];

    public DecimalSingleValueAccumulator(
        FieldVector input,
        FieldVector output,
        FieldVector transferVector,
        int maxValuesPerBatch,
        BufferAllocator computationVectorAllocator) {
      super(
          input,
          output,
          transferVector,
          AccumulatorBuilder.AccumulatorType.SINGLE_VALUE,
          maxValuesPerBatch,
          computationVectorAllocator);
    }

    @Override
    void initialize(FieldVector vector) {
      setNullAndValue(vector, INIT_VALUE);
    }

    @Override
    public void accumulate(
        final long memoryAddr, final int count, final int bitsInChunk, final int chunkOffsetMask) {
      accumulateHelper(
          this,
          memoryAddr,
          count,
          bitsInChunk,
          chunkOffsetMask,
          WIDTH_ACCUMULATOR,
          WIDTH_INPUT,
          (inputVector, srcAddr, accAddr, newBitValue, oldBitValue) -> {
            final int scale = ((DecimalVector) inputVector).getScale();
            java.math.BigDecimal newVal =
                DecimalUtils.getBigDecimalFromLEBytes(srcAddr, valBuf, scale);
            java.math.BigDecimal oldVal =
                DecimalUtils.getBigDecimalFromLEBytes(accAddr, valBuf, scale);
            checkSingleValue(oldBitValue, Objects.equals(oldVal, NULL_VALUE));
            PlatformDependent.putLong(
                accAddr,
                Double.doubleToLongBits(
                    newBitValue ? newVal.doubleValue() : NULL_VALUE.doubleValue()));
          });
    }
  }

  public static class DecimalSingleValueAccumulatorV2 extends BaseSingleAccumulator {
    private static final BigDecimal INIT_VALUE = DecimalUtils.MIN_DECIMAL;
    private static final long NULL_VALUE = 0;
    private static final int WIDTH_INPUT = 16;
    private static final int WIDTH_ACCUMULATOR = 8;

    public DecimalSingleValueAccumulatorV2(
        FieldVector input,
        FieldVector output,
        FieldVector transferVector,
        int maxValuesPerBatch,
        BufferAllocator computationVectorAllocator) {
      super(
          input,
          output,
          transferVector,
          AccumulatorBuilder.AccumulatorType.SINGLE_VALUE,
          maxValuesPerBatch,
          computationVectorAllocator);
    }

    @Override
    void initialize(FieldVector vector) {
      setNullAndValue(vector, INIT_VALUE);
    }

    @Override
    public void accumulate(
        final long memoryAddr, final int count, final int bitsInChunk, final int chunkOffsetMask) {
      accumulateHelper(
          this,
          memoryAddr,
          count,
          bitsInChunk,
          chunkOffsetMask,
          WIDTH_ACCUMULATOR,
          WIDTH_INPUT,
          (inputVector, srcAddr, accAddr, newBitValue, oldBitValue) -> {
            long newValLow = PlatformDependent.getLong(srcAddr);
            long newValHigh = PlatformDependent.getLong(srcAddr + DecimalUtils.LENGTH_OF_LONG);

            long oldValLow = PlatformDependent.getLong(accAddr);
            long oldValHigh = PlatformDependent.getLong(accAddr + DecimalUtils.LENGTH_OF_LONG);

            checkSingleValue(oldBitValue, Objects.equals(oldValLow, NULL_VALUE));
            PlatformDependent.putLong(accAddr, newValLow);
            PlatformDependent.putLong(accAddr + DecimalUtils.LENGTH_OF_LONG, newValHigh);
          });
    }
  }

  public static class DoubleSingleValueAccumulator extends BaseSingleAccumulator {
    private static final long INIT_VALUE = Double.doubleToRawLongBits(-Double.MAX_VALUE);
    private static final double NULL_VALUE = 0.0d;
    private static final int WIDTH_INPUT = 8;
    private static final int WIDTH_ACCUMULATOR = 8;

    public DoubleSingleValueAccumulator(
        FieldVector input,
        FieldVector output,
        FieldVector transferVector,
        int maxValuesPerBatch,
        BufferAllocator computationVectorAllocator) {
      super(
          input,
          output,
          transferVector,
          AccumulatorBuilder.AccumulatorType.SINGLE_VALUE,
          maxValuesPerBatch,
          computationVectorAllocator);
    }

    @Override
    void initialize(FieldVector vector) {
      setNullAndValue(vector, INIT_VALUE);
    }

    @Override
    public void accumulate(
        final long memoryAddr, final int count, final int bitsInChunk, final int chunkOffsetMask) {
      accumulateHelper(
          this,
          memoryAddr,
          count,
          bitsInChunk,
          chunkOffsetMask,
          WIDTH_ACCUMULATOR,
          WIDTH_INPUT,
          (inputVector, srcAddr, accAddr, newBitValue, oldBitValue) -> {
            final double newVal = Double.longBitsToDouble(PlatformDependent.getLong(srcAddr));
            final double oldVal = Double.longBitsToDouble(PlatformDependent.getLong(accAddr));
            checkSingleValue(oldBitValue, Objects.equals(oldVal, NULL_VALUE));
            PlatformDependent.putLong(
                accAddr, Double.doubleToRawLongBits(newBitValue ? newVal : NULL_VALUE));
          });
    }
  }

  public static class IntervalDaySingleValueAccumulator extends BaseSingleAccumulator {
    private static final long INIT_VALUE = -1;
    private static final long NULL_VALUE = 0;
    private static final int WIDTH_INPUT = 8;
    private static final int WIDTH_ACCUMULATOR = 8;

    public IntervalDaySingleValueAccumulator(
        FieldVector input,
        FieldVector output,
        FieldVector transferVector,
        int maxValuesPerBatch,
        BufferAllocator computationVectorAllocator) {
      super(
          input,
          output,
          transferVector,
          AccumulatorBuilder.AccumulatorType.SINGLE_VALUE,
          maxValuesPerBatch,
          computationVectorAllocator);
    }

    @Override
    void initialize(FieldVector vector) {
      setNullAndValue(vector, INIT_VALUE);
    }

    @Override
    public void accumulate(
        final long memoryAddr, final int count, final int bitsInChunk, final int chunkOffsetMask) {
      accumulateHelper(
          this,
          memoryAddr,
          count,
          bitsInChunk,
          chunkOffsetMask,
          WIDTH_ACCUMULATOR,
          WIDTH_INPUT,
          (inputVector, srcAddr, accAddr, newBitValue, oldBitValue) -> {
            final long newVal = PlatformDependent.getLong(srcAddr);
            final long oldVal = PlatformDependent.getLong(accAddr);
            checkSingleValue(oldBitValue, Objects.equals(oldVal, NULL_VALUE));
            PlatformDependent.putLong(accAddr, newBitValue ? newVal : NULL_VALUE);
          });
    }
  }

  public static class BitSingleValueAccumulator extends BaseSingleAccumulator {
    private static final int BITS_PER_BYTE_SHIFT = 3; // (1<<3) bits per byte
    private static final int BITS_PER_BYTE = (1 << BITS_PER_BYTE_SHIFT);
    private static final long INIT_VALUE = -1;
    private static final int NULL_VALUE = 0;

    public BitSingleValueAccumulator(
        FieldVector input,
        FieldVector output,
        FieldVector transferVector,
        int maxValuesPerBatch,
        BufferAllocator computationVectorAllocator) {
      super(
          input,
          output,
          transferVector,
          AccumulatorBuilder.AccumulatorType.SINGLE_VALUE,
          maxValuesPerBatch,
          computationVectorAllocator);
    }

    @Override
    void initialize(FieldVector vector) {
      setNullAndValue(vector, INIT_VALUE);
    }

    @Override
    public void accumulate(
        final long memoryAddr, final int count, final int bitsInChunk, final int chunkOffsetMask) {
      FieldVector inputVector = getInput();
      final long incomingBit = inputVector.getValidityBufferAddress();
      final long incomingValue = inputVector.getDataBufferAddress();
      final long[] bitAddresses = this.bitAddresses;
      final long[] valueAddresses = this.valueAddresses;

      final long singleValueOrdinalAddr = memoryAddr + count * PARTITIONINDEX_HTORDINAL_WIDTH;

      // Like every accumulator, the code below essentially implements:
      //   accumulators[ordinals[i]] += inputs[i]
      // with the only complication that both accumulators and inputs are bits.
      // There's nothing we can do about the locality of the accumulators, but inputs can be
      // processed a word at a time.
      // Algorithm:
      // - get 64 bits worth of inputs, until all inputs exhausted. For each long:
      //   - find the accumulator word it pertains to
      //   - read/update/write the accumulator bit
      // Unfortunately, there is no locality: the incoming partition+ordinal array has been ordered
      // by partition, which
      // removes the ability to process input bits a word at a time
      // In the code below:
      // - input* refers to the data values in the incoming batch
      // - ordinal* refers to the temporary table that hashAgg passes in, identifying which hash
      // table entry each input matched to
      // - min* refers to the accumulator
      for (long partitionAndOrdinalAddr = memoryAddr;
          partitionAndOrdinalAddr < singleValueOrdinalAddr;
          partitionAndOrdinalAddr += PARTITIONINDEX_HTORDINAL_WIDTH) {
        /* get the hash table ordinal */
        final int tableIndex = PlatformDependent.getInt(partitionAndOrdinalAddr + HTORDINAL_OFFSET);
        /* get the index of data in input vector */
        final int incomingIndex =
            PlatformDependent.getInt(partitionAndOrdinalAddr + KEYINDEX_OFFSET);
        final int chunkIndex = tableIndex >>> bitsInChunk;
        final int chunkOffset = tableIndex & chunkOffsetMask;
        final long singleValueBitUpdateAddr =
            bitAddresses[chunkIndex] + ((chunkOffset >>> 5) * 4); // 32-bit read-update-write

        final int inputBitVal =
            (PlatformDependent.getByte(incomingBit + ((incomingIndex >>> BITS_PER_BYTE_SHIFT)))
                    >>> (incomingIndex & (BITS_PER_BYTE - 1)))
                & 1;
        final int inputVal =
            (PlatformDependent.getByte(incomingValue + ((incomingIndex >>> BITS_PER_BYTE_SHIFT)))
                    >>> (incomingIndex & (BITS_PER_BYTE - 1)))
                & 1;

        final int singleValueUpdateBit = inputBitVal << (chunkOffset & 31);
        int singleValueUpdateVal = (inputBitVal & inputVal) << (chunkOffset & 31);
        final long singleValueAccumAddr = valueAddresses[chunkIndex] + ((chunkOffset >>> 5) * 4);

        int currentSingleValueAccum = PlatformDependent.getInt(singleValueAccumAddr);

        int singleValueCurrentBit = PlatformDependent.getInt(singleValueBitUpdateAddr);
        boolean currentSingleValueBit = (singleValueCurrentBit & (1 << (chunkOffset & 31))) != 0;

        checkSingleValue(
            currentSingleValueBit,
            Objects.equals((currentSingleValueAccum & (1 << (chunkOffset & 31))), NULL_VALUE));

        PlatformDependent.putInt(
            singleValueAccumAddr,
            (currentSingleValueAccum & ~(int) (1 << (chunkOffset & 31))) | singleValueUpdateVal);
        PlatformDependent.putInt(
            singleValueBitUpdateAddr, singleValueCurrentBit | singleValueUpdateBit);
      }
    }
  }

  public static class VarLenSingleValueAccumulator extends BaseVarBinaryAccumulator {
    NullableVarCharHolder holder = new NullableVarCharHolder();
    int[] processedAccumulators;

    public VarLenSingleValueAccumulator(
        FieldVector input,
        FieldVector transferVector,
        int maxValuesPerBatch,
        BufferAllocator computationVectorAllocator,
        int estimatedVariableWidthKeySize,
        int maxVariableWidthKeySize,
        int maxVarWidthVecUsagePercent,
        int accumIndex,
        BaseValueVector tempAccumulatorHolder,
        VectorizedHashAggOperator.VarLenVectorResizer varLenVectorResizer) {
      super(
          input,
          transferVector,
          AccumulatorBuilder.AccumulatorType.SINGLE_VALUE,
          maxValuesPerBatch,
          computationVectorAllocator,
          estimatedVariableWidthKeySize,
          maxVariableWidthKeySize,
          maxVarWidthVecUsagePercent,
          accumIndex,
          tempAccumulatorHolder,
          varLenVectorResizer);
      processedAccumulators = new int[1];
    }

    @Override
    public void addBatch(ArrowBuf dataBuffer, ArrowBuf validityBuffer) {
      int oldAccumulatorsLength = accumulators.length;
      super.addBatch(dataBuffer, validityBuffer);
      if (accumulators.length != oldAccumulatorsLength) {
        int oldProcessedAccumulatorsLength = processedAccumulators.length;
        int[] oldProcessedAccumulators = processedAccumulators;
        processedAccumulators = new int[1 + accumulators.length / 8];
        System.arraycopy(
            oldProcessedAccumulators, 0, processedAccumulators, 0, oldProcessedAccumulatorsLength);
      }
    }

    @Override
    public void accumulate(
        final long memoryAddr, final int count, final int bitsInChunk, final int chunkOffsetMask) {
      final long accAddr = memoryAddr + count * PARTITIONINDEX_HTORDINAL_WIDTH;
      final FieldVector inputVector = getInput();
      final long inputValidityBufAddr = inputVector.getValidityBufferAddress();
      final ArrowBuf inputOffsetBuf = inputVector.getOffsetBuffer();
      final ArrowBuf inputDataBuf = inputVector.getDataBuffer();

      for (long partitionAndOrdinalAddr = memoryAddr;
          partitionAndOrdinalAddr < accAddr;
          partitionAndOrdinalAddr += PARTITIONINDEX_HTORDINAL_WIDTH) {
        // get the index of data in input vector
        final int incomingIndex =
            PlatformDependent.getInt(partitionAndOrdinalAddr + KEYINDEX_OFFSET);

        final int bitVal =
            (PlatformDependent.getByte(inputValidityBufAddr + (incomingIndex >>> 3))
                    >>> (incomingIndex & 7))
                & 1;

        // get the hash table ordinal
        final int tableIndex = PlatformDependent.getInt(partitionAndOrdinalAddr + HTORDINAL_OFFSET);

        // get the hash table batch index
        final int chunkIndex = tableIndex >>> bitsInChunk;
        final int chunkOffset = tableIndex & chunkOffsetMask;

        boolean accProcessed = (processedAccumulators[chunkIndex] & (1 << (chunkOffset & 31))) != 0;

        if (accProcessed) { // accumulator is not null
          throw UserException.functionError()
              .message(
                  "Subqueries used in expressions must be scalar (must return a single value).")
              .build();
        }
        if (bitVal != 0) {
          MutableVarcharVector mv = (MutableVarcharVector) this.accumulators[chunkIndex];
          holder.isSet = 0;
          if (mv.isIndexSafe(chunkOffset)) {
            mv.get(chunkOffset, holder);
          }
          // get the offset of incoming record
          final int startOffset =
              inputOffsetBuf.getInt(incomingIndex * BaseVariableWidthVector.OFFSET_WIDTH);
          final int endOffset =
              inputOffsetBuf.getInt((incomingIndex + 1) * BaseVariableWidthVector.OFFSET_WIDTH);

          this.updateRunTimeVarLenColumnSize(endOffset - startOffset);
          mv.set(chunkOffset, startOffset, (endOffset - startOffset), inputDataBuf);
        }
        processedAccumulators[chunkIndex] |= (1 << (chunkOffset & 31)); // mark acc as processed
      }
    }
  }

  private static void accumulateHelper(
      BaseSingleAccumulator accumulator,
      final long memoryAddr,
      final int count,
      final int bitsInChunk,
      final int chunkOffsetMask,
      final int widthAccumulator,
      final int widthInput,
      ValueUpdater updater) {
    final long maxMemAddr = memoryAddr + count * PARTITIONINDEX_HTORDINAL_WIDTH;
    FieldVector inputVector = accumulator.getInput();
    final long incomingBit = inputVector.getValidityBufferAddress();
    final long incomingValue = inputVector.getDataBufferAddress();
    final long[] bitAddresses = accumulator.bitAddresses;
    final long[] valueAddresses = accumulator.valueAddresses;
    final int maxValuesPerBatch = accumulator.maxValuesPerBatch;

    for (long partitionAndOrdinalAddr = memoryAddr;
        partitionAndOrdinalAddr < maxMemAddr;
        partitionAndOrdinalAddr += PARTITIONINDEX_HTORDINAL_WIDTH) {
      /* get the hash table ordinal */
      final int tableIndex = PlatformDependent.getInt(partitionAndOrdinalAddr + HTORDINAL_OFFSET);
      /* get the index of data in input vector */
      final int incomingIndex = PlatformDependent.getInt(partitionAndOrdinalAddr + KEYINDEX_OFFSET);
      /* get the corresponding data from input vector -- source data for accumulation */

      final int bitVal =
          (PlatformDependent.getByte(incomingBit + ((incomingIndex >>> 3))) >>> (incomingIndex & 7))
              & 1;
      /* get the hash table batch index */
      final int chunkIndex = tableIndex >>> bitsInChunk;
      final int chunkOffset = tableIndex & chunkOffsetMask;
      /* get the target addresses of accumulation vector */
      final long singleValueAddr = valueAddresses[chunkIndex] + (chunkOffset) * widthAccumulator;
      final long bitUpdateAddr = bitAddresses[chunkIndex] + ((chunkOffset >>> 5) * 4);
      final int bitUpdateVal = bitVal << (chunkOffset & 31);
      int accumBitValue = PlatformDependent.getInt(bitUpdateAddr);
      boolean oldBitValue = (accumBitValue & (1 << (chunkOffset & 31))) != 0;
      /* store the accumulated values(new max or existing) at the target location of accumulation vector */
      updater.update(
          inputVector,
          incomingValue + (incomingIndex * widthInput),
          singleValueAddr,
          bitUpdateVal != 0,
          oldBitValue);

      PlatformDependent.putInt(bitUpdateAddr, accumBitValue | bitUpdateVal);
    }
  }

  @FunctionalInterface
  interface ValueUpdater {
    void update(
        FieldVector inputVector,
        long address,
        long singleValueAddr,
        boolean newBitValue,
        boolean oldBitValue);
  }

  private static <T> void checkSingleValue(boolean oldBitValue, boolean valueIsNull) {
    if (oldBitValue || valueIsNull) {
      throw UserException.functionError()
          .message("Subqueries used in expressions must be scalar (must return a single value).")
          .build();
    }
  }
}
