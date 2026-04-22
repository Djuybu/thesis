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

package com.dremio.sabot.op.common.ht2;

import com.dremio.common.config.SabotConfig;
import com.dremio.options.OptionManager;
import com.koloboke.collect.hash.HashConfig;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface HashTable extends AutoCloseable {
  Logger logger = LoggerFactory.getLogger(HashTable.class);
  public static final int CONTROL_WIDTH = 8;
  public static final int VAR_OFFSET_SIZE = 4;
  public static final int VAR_LENGTH_SIZE = 4;
  public static final int SKIP = -1;

  public static final int FREE = -1; // same for both int and long.
  public static final long LFREE = -1L; // same for both int and long.
  public static final int RETRY_RETURN_CODE = -2;
  public static final int ORDINAL_SIZE = 4;
  public static final int MIN_RESERVATION_BATCH_SIZE = 128;
  public static final int PAGE_BITS = 17;
  public static final int PAGE_SIZE = 1 << PAGE_BITS;
  public static final int DEFAULT_HASH_TABLE_TYPE = 0;

  String FACTORY_KEY =
      "dremio.ht2.implementation.factory"; // Replacing class with factory to keep with our current

  // pattern...

  static HashTable getInstance(
      SabotConfig sabotConfig, OptionManager optionsManager, HashTableCreateArgs createArgs) {
    HashTableFactory factory =
        sabotConfig.getInstance(FACTORY_KEY, HashTableFactory.class, LBlockHashTableFactory.class);
    return factory.getInstanceForHashJoin(optionsManager, createArgs);
  }

  static HashTable getInstanceForAggregate(
      SabotConfig sabotConfig, OptionManager optionsManager, HashTableCreateArgs createArgs) {
    HashTableFactory factory =
        sabotConfig.getInstance(FACTORY_KEY, HashTableFactory.class, LBlockHashTableFactory.class);
    return factory.getInstanceForHashAgg(optionsManager, createArgs);
  }

  void computeHash(
      int numRecords, ArrowBuf keyFixed, ArrowBuf keyVar, long seed, ArrowBuf hashOut8B);

  int add(
      int numRecords, ArrowBuf keyFixed, ArrowBuf keyVar, ArrowBuf hash8B, ArrowBuf outOrdinals);

  int add(
      final long keyFixedVectorAddr,
      final long keyVarVectorAddr,
      final long keyVarVectorSize,
      final int keyIndex,
      final int keyHash);

  void find(
      int numRecords, ArrowBuf keyFixed, ArrowBuf keyVar, ArrowBuf hash8B, ArrowBuf outOrdinals);

  int addSv2(
      ArrowBuf sv2,
      int pivotShift,
      int numRecords,
      ArrowBuf keyFixed,
      ArrowBuf keyVar,
      ArrowBuf hash4B,
      ArrowBuf outOrdinals);

  void findSv2(
      ArrowBuf sv2,
      int pivotShift,
      int numRecords,
      ArrowBuf keyFixed,
      ArrowBuf keyVar,
      ArrowBuf hash4B,
      ArrowBuf outOrdinals);

  void copyKeysToBuffer(ArrowBuf ordinals, int numRecords, ArrowBuf keyFixed, ArrowBuf keyVar);

  int getCumulativeVarKeyLength(ArrowBuf ordinals, int numRecords);

  void getVarKeyLengths(ArrowBuf ordinals, int numRecords, ArrowBuf outLengths);

  int getMaxOrdinal();

  int size();

  int capacity();

  int getRehashCount();

  public void registerResizeListener(ResizeListener resizeListener);

  public int getActualValuesPerBatch();

  public int getBitsInChunk();

  public int getChunkOffsetMask();

  public int getVariableBlockMaxLength();

  public int getCurrentNumberOfBlocks();

  public long getSizeInBytes();

  public int blocks();

  public int getBatchIndexForOrdinal(final int ordinal);

  public int getMaxValuesPerBatch();

  public int getMaxPerBlock();

  public long getSpliceTime(TimeUnit unit);

  public int getSpliceCount();

  public int getAccumCompactionCount();

  public long getAccumCompactionTime(TimeUnit unit);

  public int getMaxVarLenKeySize();

  int getNumHashTableBuffersPerBatch();

  public void resetToMinimumSize() throws Exception;

  default int splice(final int batchIndex, final long seed) {
    throw new UnsupportedOperationException("splice() not implemented");
  }

  public int getOrInsertWithAccumSpaceCheck(
      final long keyFixedAddr,
      final long keyVarAddr,
      final int keyVarLen,
      final int keyHash,
      final int dataWidth,
      final long seed);

  public void preallocateSingleBatch();

  default void unpivot(int startBatchIndex, int[] recordsInBatches) {
    throw new UnsupportedOperationException("unpivot() not implemented");
  }

  public int getNumRecordsInBlock(final int blockIndex);

  public int[] getRecordsInBlocks();

  default List<ArrowBuf> getFixedBlockBuffers() {
    throw new UnsupportedOperationException("getFixedBlockBuffers() not implemented");
  }

  default List<ArrowBuf> getVariableBlockBuffers() {
    throw new UnsupportedOperationException("getVariableBlockBuffers() not implemented");
  }

  default int verifyBlocks(String caller) {
    return -1;
  }

  public List<ArrowBuf> getKeyBuffers(int batchIdx) throws Exception;

  public int getNumChunks();

  public void releaseBatch(final int batchIdx) throws Exception;

  long getRehashTime(TimeUnit timeUnit);

  public long getAllocatedForFixedBlocks();

  public long getUnusedForFixedBlocks();

  public long getAllocatedForVarBlocks();

  public long getUnusedForVarBlocks();

  public int getMaxHashTableBatchSize();

  public int getHashTableType();

  static int getMaxVariableBlockSize(int pivotKeyLength) {
    return HashTable.PAGE_SIZE - pivotKeyLength;
  }

  class HashTableKeyAddress {
    private final long fixedKeyAddress;
    private final long varKeyAddress; /* 0, if no variable length columns present */

    public HashTableKeyAddress(long fixedKeyAddress, long varKeyAddress) {
      this.fixedKeyAddress = fixedKeyAddress;
      this.varKeyAddress = varKeyAddress;
    }

    public long getFixedKeyAddress() {
      return fixedKeyAddress;
    }

    public long getVarKeyAddress() {
      return varKeyAddress;
    }
  }

  Iterator<HashTableKeyAddress> keyIterator();

  /* Used in API testing */
  long[] getDataPageAddresses();

  @Override
  void close() throws Exception;

  /* XXX: Until tracing is supported by NativeHashTable, i.e DX-42630 */
  default void traceStart(int numRecords) {}

  default void traceEnd() {}

  default void traceInsertStart(int numRecords) {}

  default void traceInsertEnd() {}

  default void traceOrdinals(long outputAddr, int numRecords) {}

  default String traceReport() {
    return "";
  }

  /** Args that passed to SabotConfig to create an instance. */
  class HashTableCreateArgs {
    private final HashConfig hashConfig;
    private final PivotDef pivot;
    private final BufferAllocator allocator;
    private final int initialSize;
    private final int defaultVarLengthSize;
    private final boolean enforceVarWidthBufferLimit;
    private final int maxHashTableBatchSize;
    private final NullComparator nullComparator;
    private final boolean runtimeFilterEnabled;
    private final boolean preAllocateBatch;
    private final boolean lightWeightInstance;

    public HashTableCreateArgs(
        HashConfig hashConfig,
        PivotDef pivot,
        BufferAllocator allocator,
        int initialSize,
        int defaultVarLengthSize,
        boolean enforceVarWidthBufferLimit,
        int maxHashTableBatchSize,
        NullComparator nullComparator,
        boolean runtimeFilterEnabled,
        boolean preAllocateBatch,
        boolean lightWeightInstance) {
      this.hashConfig = hashConfig;
      this.pivot = pivot;
      this.allocator = allocator;
      this.initialSize = initialSize;
      this.defaultVarLengthSize = defaultVarLengthSize;
      this.enforceVarWidthBufferLimit = enforceVarWidthBufferLimit;
      this.maxHashTableBatchSize = maxHashTableBatchSize;
      this.nullComparator = nullComparator;
      this.runtimeFilterEnabled = runtimeFilterEnabled;
      this.preAllocateBatch = preAllocateBatch;
      this.lightWeightInstance = lightWeightInstance;
    }

    public HashConfig getHashConfig() {
      return hashConfig;
    }

    public PivotDef getPivot() {
      return pivot;
    }

    public BufferAllocator getAllocator() {
      return allocator;
    }

    public int getInitialSize() {
      return initialSize;
    }

    public int getDefaultVarLengthSize() {
      return defaultVarLengthSize;
    }

    public boolean isEnforceVarWidthBufferLimit() {
      return enforceVarWidthBufferLimit;
    }

    public int getMaxHashTableBatchSize() {
      return maxHashTableBatchSize;
    }

    public NullComparator getNullComparator() {
      return nullComparator;
    }

    public boolean isRuntimeFilterEnabled() {
      return runtimeFilterEnabled;
    }

    public boolean preAllocateBatch() {
      return this.preAllocateBatch;
    }

    public boolean isLightWeightInstance() {
      return this.lightWeightInstance;
    }
  }
}
