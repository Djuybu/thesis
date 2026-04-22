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
package com.dremio.sabot.join.hash;

import static com.dremio.exec.ExecConstants.TARGET_BATCH_RECORDS_MAX;
import static com.dremio.sabot.PerformanceTestsHelper.getFieldInfos;
import static com.dremio.sabot.PerformanceTestsHelper.getFieldNames;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.dremio.common.exceptions.ErrorHelper;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.expression.LogicalExpression;
import com.dremio.common.logical.data.JoinCondition;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.physical.config.HashJoinPOP;
import com.dremio.options.OptionManager;
import com.dremio.options.OptionValue;
import com.dremio.options.OptionValue.OptionType;
import com.dremio.sabot.BatchDataGenerator;
import com.dremio.sabot.FieldInfo;
import com.dremio.sabot.exec.context.OperatorStats;
import com.dremio.sabot.op.join.hash.HashJoinOperator;
import com.dremio.sabot.op.join.vhash.spill.VectorizedSpillingHashJoinOperator;
import java.util.ArrayList;
import java.util.List;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.calcite.rel.core.JoinRelType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TestVHashJoinSpill extends TestHashJoin {
  private final OptionManager options = testContext.getOptions();
  private final int minReserve = VectorizedSpillingHashJoinOperator.MIN_RESERVE;

  @Before
  public void before() {
    options.setOption(
        OptionValue.createBoolean(
            OptionType.SYSTEM, HashJoinOperator.ENABLE_SPILL.getOptionName(), true));
    options.setOption(
        OptionValue.createLong(OptionType.SYSTEM, TARGET_BATCH_RECORDS_MAX.getOptionName(), 65535));
    VectorizedSpillingHashJoinOperator.MIN_RESERVE = 9 * 1024 * 1024;
  }

  @After
  public void after() {
    options.setOption(HashJoinOperator.ENABLE_SPILL.getDefault());
    options.setOption(TARGET_BATCH_RECORDS_MAX.getDefault());
    VectorizedSpillingHashJoinOperator.MIN_RESERVE = minReserve;
  }

  @Override
  protected JoinInfo getJoinInfo(List<JoinCondition> conditions, JoinRelType type) {
    return new JoinInfo(
        VectorizedSpillingHashJoinOperator.class,
        new HashJoinPOP(PROPS, null, null, conditions, null, type, true, true, null));
  }

  protected JoinInfo getJoinInfo(
      List<JoinCondition> conditions, LogicalExpression extraCondition, JoinRelType type) {
    return new JoinInfo(
        VectorizedSpillingHashJoinOperator.class,
        new HashJoinPOP(PROPS, null, null, conditions, extraCondition, type, true, true, null));
  }

  @Test
  @Override
  public void manyColumns() throws Exception {
    baseManyColumns();
  }

  @Test
  public void testRowSizeLimitInnerJoin() throws Exception {
    try (AutoCloseable ac = with(ExecConstants.ENABLE_ROW_SIZE_LIMIT_ENFORCEMENT, true);
        AutoCloseable ac1 = with(ExecConstants.LIMIT_ROW_SIZE_BYTES, 52); ) {
      baseManyColumnsDecimal(JoinRelType.INNER);
      fail("Query should have throw RowSizeLimitException");
    } catch (Exception e) {
      assertTrue(e.getMessage().contains("Exceeded maximum allowed row size"));
    }
  }

  @Test
  public void testRowSizeLimitLeftJoin() throws Exception {
    try (AutoCloseable ac = with(ExecConstants.ENABLE_ROW_SIZE_LIMIT_ENFORCEMENT, true);
        AutoCloseable ac1 = with(ExecConstants.LIMIT_ROW_SIZE_BYTES, 52); ) {
      baseManyColumnsDecimal(JoinRelType.LEFT);
      fail("Query should have throw RowSizeLimitException");
    } catch (Exception e) {
      assertTrue(e.getMessage().contains("Exceeded maximum allowed row size"));
    }
  }

  @Test
  public void testRowSizeLimitRightJoin() throws Exception {
    try (AutoCloseable ac = with(ExecConstants.ENABLE_ROW_SIZE_LIMIT_ENFORCEMENT, true);
        AutoCloseable ac1 = with(ExecConstants.LIMIT_ROW_SIZE_BYTES, 52); ) {
      baseManyColumnsDecimal(JoinRelType.RIGHT);
      fail("Query should have throw RowSizeLimitException");
    } catch (Exception e) {
      assertTrue(e.getMessage().contains("Exceeded maximum allowed row size"));
    }
  }

  @Test
  public void manyColumnsDecimal() throws Exception {
    baseManyColumnsDecimal(JoinRelType.LEFT);
  }

  @Test
  public void testPivotSubBatches() throws Exception {
    List<FieldInfo> buildFieldInfos = new ArrayList<>();
    List<FieldInfo> probeFieldInfos = new ArrayList<>();
    int batchSize = 4096;

    buildFieldInfos.addAll(
        getFieldInfos(new ArrowType.Int(32, true), batchSize, FieldInfo.SortOrder.RANDOM, 7));
    buildFieldInfos.addAll(
        getFieldInfos(new ArrowType.Utf8(), batchSize, FieldInfo.SortOrder.RANDOM, 2));
    probeFieldInfos.addAll(
        getFieldInfos(new ArrowType.Int(32, true), batchSize, FieldInfo.SortOrder.RANDOM, 7));
    probeFieldInfos.addAll(
        getFieldInfos(new ArrowType.Utf8(), batchSize, FieldInfo.SortOrder.RANDOM, 2));

    List<String> buildKeyNames = getFieldNames(buildFieldInfos);
    List<String> probeKeyNames = getFieldNames(probeFieldInfos);

    int dataSize = 1_100_000;
    int numOfBatches = (dataSize / batchSize / (2 * 18));

    try (BatchDataGenerator buildGenerator =
            new BatchDataGenerator(
                buildFieldInfos,
                getTestAllocator(),
                numOfBatches * batchSize,
                batchSize,
                250,
                10_000 * batchSize);
        BatchDataGenerator probeGenerator =
            new BatchDataGenerator(
                probeFieldInfos,
                getTestAllocator(),
                numOfBatches * batchSize,
                batchSize,
                250,
                10_000 * batchSize)) {
      JoinInfo info = null;

      List<JoinCondition> joinConditions = new ArrayList<>();
      int numOfFixedJoinKeysPerSide = 5;
      int numOfVarJoinKeysPerSide = 2;
      for (int index = 0; index < numOfFixedJoinKeysPerSide; index++) {
        joinConditions.add(
            new JoinCondition("EQUALS", f(probeKeyNames.get(index)), f(buildKeyNames.get(index))));
      }
      for (int index = 7; index < numOfVarJoinKeysPerSide + 7; index++) {
        joinConditions.add(
            new JoinCondition("EQUALS", f(probeKeyNames.get(index)), f(buildKeyNames.get(index))));
      }

      info = getJoinInfo(joinConditions, JoinRelType.INNER);

      OperatorStats stats;

      // Page size can't be less than 64K based on the following criteria when spilling is involved
      // PAGE_SIZE / HEAD_SIZE (4) needs to be ensured to be power of 2
      // Able to allocate ordinals BuildMemoryReleaser.MiscBuffers to be allocated in one page
      // Required memory size for MiscBuffers is 34KB
      try (AutoCloseable ac1 = with(HashJoinOperator.PAGE_SIZE, 64 * 1024);
          AutoCloseable ac2 = with(TARGET_BATCH_RECORDS_MAX, 4096); ) {
        stats =
            validateDual(
                info.operator, info.clazz, probeGenerator, buildGenerator, batchSize, null, true);
      }
    }
  }

  @Test
  public void testPivotAndInsertSubBatches() throws Exception {
    List<FieldInfo> buildFieldInfos = new ArrayList<>();
    List<FieldInfo> probeFieldInfos = new ArrayList<>();
    int batchSize = 4096;

    buildFieldInfos.addAll(
        getFieldInfos(new ArrowType.Int(32, true), batchSize, FieldInfo.SortOrder.RANDOM, 7));
    buildFieldInfos.addAll(
        getFieldInfos(new ArrowType.Utf8(), batchSize, FieldInfo.SortOrder.RANDOM, 2));
    probeFieldInfos.addAll(
        getFieldInfos(new ArrowType.Int(32, true), batchSize, FieldInfo.SortOrder.RANDOM, 7));
    probeFieldInfos.addAll(
        getFieldInfos(new ArrowType.Utf8(), batchSize, FieldInfo.SortOrder.RANDOM, 2));

    List<String> buildKeyNames = getFieldNames(buildFieldInfos);
    List<String> probeKeyNames = getFieldNames(probeFieldInfos);

    int dataSize = 6_000_000;
    int numOfBatches = (dataSize / batchSize / (2 * 18));

    try (BatchDataGenerator buildGenerator =
            new BatchDataGenerator(
                buildFieldInfos,
                getTestAllocator(),
                numOfBatches * batchSize,
                batchSize,
                250,
                10_000 * batchSize);
        BatchDataGenerator probeGenerator =
            new BatchDataGenerator(
                probeFieldInfos,
                getTestAllocator(),
                numOfBatches * batchSize,
                batchSize,
                250,
                10_000 * batchSize)) {
      JoinInfo info = null;

      List<JoinCondition> joinConditions = new ArrayList<>();
      int numOfFixedJoinKeysPerSide = 5;
      int numOfVarJoinKeysPerSide = 2;
      for (int index = 0; index < numOfFixedJoinKeysPerSide; index++) {
        joinConditions.add(
            new JoinCondition("EQUALS", f(probeKeyNames.get(index)), f(buildKeyNames.get(index))));
      }
      for (int index = 7; index < numOfVarJoinKeysPerSide + 7; index++) {
        joinConditions.add(
            new JoinCondition("EQUALS", f(probeKeyNames.get(index)), f(buildKeyNames.get(index))));
      }
      int memoryLimit = 100_000_000;
      info = getSpillingJoinInfo(joinConditions, null, JoinRelType.INNER, 0, memoryLimit);

      OperatorStats stats;

      // Page size can't be less than 64K based on the following criteria when spilling is involved
      // PAGE_SIZE / HEAD_SIZE (4) needs to be ensured to be power of 2
      // Able to allocate ordinals BuildMemoryReleaser.MiscBuffers to be allocated in one page
      // Required memory size for MiscBuffers is 34KB

      // The test can cause OOM, but the OOM should be during the allocation of a newPage in
      // PagePool
      // When such an error happens, treat this test as succeeded.
      try (AutoCloseable ac1 = with(HashJoinOperator.PAGE_SIZE, 64 * 1024);
          AutoCloseable ac2 = with(TARGET_BATCH_RECORDS_MAX, 4096); ) {
        try {
          stats =
              validateDual(
                  info.operator, info.clazz, probeGenerator, buildGenerator, batchSize, null, true);
          assertTrue(stats != null);
        } catch (UserException ue) {
          boolean newPageStackFound = false;
          Throwable cause = ue.getCause();
          if (cause != null) {
            if (ErrorHelper.isDirectMemoryException(cause)) {
              while (cause != null) {
                for (StackTraceElement ste : cause.getStackTrace()) {
                  if (ste.toString()
                      .contains("com.dremio.sabot.op.join.vhash.spill.pool.PagePool.newPage")) {
                    newPageStackFound = true;
                    break;
                  }
                }
                cause = cause.getCause();
              }
              if (!newPageStackFound) {
                throw ue;
              }
            }
          } else {
            throw ue;
          }
        }
      }
    }
  }

  @Test
  public void testToProducePageSizeError() throws Exception {
    List<FieldInfo> buildFieldInfos = new ArrayList<>();
    List<FieldInfo> probeFieldInfos = new ArrayList<>();
    List<FieldInfo> buildKeyFieldInfos = new ArrayList<>();
    List<FieldInfo> probeKeyFieldInfos = new ArrayList<>();

    int batchSize = 4096;

    buildKeyFieldInfos.addAll(
        getFieldInfos(new ArrowType.Int(32, true), batchSize, FieldInfo.SortOrder.RANDOM, 7));
    buildFieldInfos.addAll(buildKeyFieldInfos);
    buildFieldInfos.addAll(
        getFieldInfos(new ArrowType.Utf8(), batchSize, FieldInfo.SortOrder.RANDOM, 2));
    probeKeyFieldInfos.addAll(
        getFieldInfos(new ArrowType.Int(32, true), batchSize, FieldInfo.SortOrder.RANDOM, 7));
    probeFieldInfos.addAll(probeKeyFieldInfos);
    probeFieldInfos.addAll(
        getFieldInfos(new ArrowType.Utf8(), batchSize, FieldInfo.SortOrder.RANDOM, 2));

    List<String> buildKeyNames = getFieldNames(buildKeyFieldInfos);
    List<String> probeKeyNames = getFieldNames(probeKeyFieldInfos);

    int dataSize = 6_000_000;
    int numOfBatches = (dataSize / batchSize / (2 * 18));

    try (BatchDataGenerator buildGenerator =
            new BatchDataGenerator(
                buildFieldInfos,
                getTestAllocator(),
                numOfBatches * batchSize,
                batchSize,
                65_000,
                10_000 * batchSize);
        BatchDataGenerator probeGenerator =
            new BatchDataGenerator(
                probeFieldInfos,
                getTestAllocator(),
                numOfBatches * batchSize,
                batchSize,
                250,
                10_000 * batchSize)) {
      JoinInfo info = null;

      List<JoinCondition> joinConditions = new ArrayList<>();
      int numOfFixedJoinKeysPerSide = 5;
      for (int index = 0; index < numOfFixedJoinKeysPerSide; index++) {
        joinConditions.add(
            new JoinCondition("EQUALS", f(probeKeyNames.get(index)), f(buildKeyNames.get(index))));
      }
      int memoryLimit = 100_000_000;
      info = getSpillingJoinInfo(joinConditions, null, JoinRelType.INNER, 0, memoryLimit);

      OperatorStats stats;

      // Page size can't be less than 64K based on the following criteria when spilling is involved
      // PAGE_SIZE / HEAD_SIZE (4) needs to be ensured to be power of 2
      // Able to allocate ordinals and BuildMemoryReleaser.MiscBuffers to be allocated in one page
      // Required memory size for MiscBuffers is 34KB

      try (AutoCloseable ac1 = with(HashJoinOperator.PAGE_SIZE, 64 * 1024);
          AutoCloseable ac2 = with(TARGET_BATCH_RECORDS_MAX, 4096); ) {
        try {
          stats =
              validateDual(
                  info.operator, info.clazz, probeGenerator, buildGenerator, batchSize, null, true);
          assertTrue(stats != null);
        } catch (UserException ue) {
          if (ue.getMessage().contains("too large to fit into page size")) {
            assertTrue(true);
          } else {
            throw ue;
          }
        }
      }
    }
  }
}
