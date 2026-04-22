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

import static com.dremio.sabot.PerformanceTestsHelper.getFieldInfos;
import static com.dremio.sabot.PerformanceTestsHelper.getFieldNames;

import com.dremio.common.logical.data.NamedExpression;
import com.dremio.common.logical.data.Order;
import com.dremio.common.util.TestTools;
import com.dremio.exec.physical.base.OpProps;
import com.dremio.exec.physical.config.WindowPOP;
import com.dremio.exec.physical.config.WindowPOP.Bound;
import com.dremio.exec.physical.config.WindowPOP.BoundType;
import com.dremio.sabot.FieldInfo.SortOrder;
import com.dremio.sabot.exec.context.HeapAllocatedMXBeanWrapper;
import com.dremio.sabot.exec.context.OperatorStats;
import com.dremio.sabot.op.windowframe.WindowFrameOperator;
import com.dremio.sabot.op.windowframe.WindowFrameStats;
import com.opencsv.CSVWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.calcite.rel.RelFieldCollation.Direction;
import org.apache.calcite.rel.RelFieldCollation.NullDirection;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class TestWindowFrameOperatorPerformance extends BaseTestOperator {

  private static final String FILE_PATH = "../";
  private static final Set<String> headersWritten = new HashSet<>();
  private List<FieldInfo> fieldInfos;
  private int numberOfRows;
  private int batchSize;
  private int varcharLen;
  private int numOfFixedPartitionCols;
  private int numOfVarPartitionCols;
  private int numOfOrderCols;
  private List<NamedExpression> partitions;
  private List<NamedExpression> windowFunctions;
  private List<Order.Ordering> orderings;
  private int rowNumberCount;
  private int rankCount;
  private int leadCount;
  private int lagCount;
  private int sumCount;
  private int countCount;
  private int numOfUniquePartitions;
  private boolean frameUnitsRows;
  private Bound lowerBound;
  private Bound upperBound;
  private int leadLagOffset;
  private int maxRandomInt = Integer.MAX_VALUE;
  private String fileName;

  @Rule public final TestRule timeoutRule = TestTools.getTimeoutRule(Duration.ofMinutes(60));

  public TestWindowFrameOperatorPerformance(
      List<FieldInfo> fieldInfos,
      int numberOfRows,
      int batchSize,
      int varcharLen,
      int numOfFixedPartitionCols,
      int numOfVarPartitionCols,
      int numOfOrderCols,
      List<NamedExpression> partitions,
      List<NamedExpression> windowFunctions,
      List<Order.Ordering> orderings,
      int rowNumberCount,
      int rankCount,
      int leadCount,
      int lagCount,
      int sumCount,
      int countCount,
      int numOfUniquePartitions,
      boolean frameUnitsRows,
      Bound lowerBound,
      Bound upperBound,
      int leadLagOffset,
      String fileName) {
    this.fieldInfos = fieldInfos;
    this.numberOfRows = numberOfRows;
    this.batchSize = batchSize;
    this.varcharLen = varcharLen;
    this.numOfFixedPartitionCols = numOfFixedPartitionCols;
    this.numOfVarPartitionCols = numOfVarPartitionCols;
    this.numOfOrderCols = numOfOrderCols;
    this.partitions = partitions;
    this.windowFunctions = windowFunctions;
    this.orderings = orderings;
    this.rowNumberCount = rowNumberCount;
    this.rankCount = rankCount;
    this.leadCount = leadCount;
    this.lagCount = lagCount;
    this.sumCount = sumCount;
    this.countCount = countCount;
    this.numOfUniquePartitions = numOfUniquePartitions;
    this.frameUnitsRows = frameUnitsRows;
    this.lowerBound = lowerBound;
    this.upperBound = upperBound;
    this.leadLagOffset = leadLagOffset;
    this.fileName = fileName;
  }

  /*
  Each method called from this function returns an Object[] where each Object is a value for TestWindowFrameOperatorPerformance members in same order as they
  are declared.
  Right now each Object[] returned would contain -
  List<FieldInfo> fieldInfos,
  int numberOfRows;
  int batchSize;
  int varcharLen;
  int numOfFixedPartitionCols;
  int numOfVarPartitionCols;
  int numOfOrderCols;
  List<NamedExpression> partitions;
  List<NamedExpression> windowFunctions;
  List<Order.Ordering> orderings;
  int rowNumberCount;
  int rankCount;
  int leadCount;
  int lagCount;
  int sumCount;
  int countCount;
  int numOfUniquePartitions;
  boolean frameUnitsRows;
  Bound lowerBound;
  Bound upperBound;
  String fileName;

  If more parameters are to be added in the future, they should be added as class members and test values for them would accordingly be added in
  following array.

  https://github.com/junit-team/junit4/wiki/Parameterized-tests
   */
  @Parameterized.Parameters
  public static Collection<Object[]> data() throws Exception {
    List<Object[]> parameters = new ArrayList<>();
    parameters.addAll(scaleLeadLagOffsetAndBatchSize());
    /* parameters.addAll(scaleAllWindowFunctionsBatches());
    parameters.addAll(scaleNumberOfWindowFunctions(true, false, false, false, false, false));
    parameters.addAll(scaleNumberOfWindowFunctions(false, true, false, false, false, false));
    parameters.addAll(scaleNumberOfPartitions(true, false));
    parameters.addAll(scaleBatchSizesWithVariousStringPartitionSizes());
    parameters.addAll(scaleBatchSizesForFixedDataSize());
    parameters.addAll(scaleUniquePartitions());
    parameters.addAll(scaleNumberOfWindowFunctions(false, false, true, false, false, false));
    parameters.addAll(scaleNumberOfPartitions(false, true));*/
    return parameters;
  }

  @Ignore
  @Test
  public void testWindowFrame() throws Exception {
    try (BatchDataGenerator sdg =
        new BatchDataGenerator(
            fieldInfos, getTestAllocator(), numberOfRows, batchSize, varcharLen, maxRandomInt)) {

      OpProps props = PROPS.cloneWithNewReserve(1_000_000).cloneWithMemoryExpensive(true);

      final WindowPOP window =
          new WindowPOP(
              props,
              null,
              partitions,
              windowFunctions,
              orderings,
              frameUnitsRows,
              lowerBound,
              upperBound);

      HeapAllocatedMXBeanWrapper.setFeatureSupported(true);
      OperatorStats stats = validateSingle(window, WindowFrameOperator.class, sdg, null, batchSize);

      writeResultsCSV(
          stats,
          fileName,
          varcharLen,
          numberOfRows / batchSize,
          batchSize,
          numOfFixedPartitionCols,
          numOfVarPartitionCols,
          numOfOrderCols,
          rowNumberCount,
          rankCount,
          leadCount,
          lagCount,
          sumCount,
          countCount,
          numOfUniquePartitions,
          frameUnitsRows,
          "",
          "");

    } catch (Exception e) {
      writeResultsCSV(
          null,
          fileName,
          varcharLen,
          numberOfRows / batchSize,
          batchSize,
          numOfFixedPartitionCols,
          numOfVarPartitionCols,
          numOfOrderCols,
          rowNumberCount,
          rankCount,
          leadCount,
          lagCount,
          sumCount,
          countCount,
          numOfUniquePartitions,
          frameUnitsRows,
          e.toString(),
          Arrays.toString(e.getStackTrace()));
    }
  }

  public static Collection<Object[]> scaleNumberOfPartitions(
      boolean scaleIntPartitions, boolean scaleStringPartitions) throws Exception {
    int batchSize = 4000;
    int numOfBatches = 1000;
    List<Object[]> parameters = new ArrayList<>();

    String fileName = "WindowFrameScaleStringPartitions";
    writeFileHeader(fileName);
    for (int numOfColumns = 1; numOfColumns <= 100; numOfColumns += 3) {
      parameters.add(
          getParameterList(
              batchSize * numOfBatches,
              batchSize,
              15,
              scaleIntPartitions ? numOfColumns : 0,
              scaleStringPartitions ? numOfColumns : 0,
              1,
              1,
              0,
              0,
              0,
              0,
              0,
              3900,
              true,
              fileName));
    }
    return parameters;
  }

  public static Collection<Object[]> scaleUniquePartitions() throws Exception {
    int batchSize = 4000;
    int numOfBatches = 1000;
    List<Object[]> parameters = new ArrayList<>();

    String fileName = "WindowFrameScaleUniquePartitions";
    writeFileHeader(fileName);

    for (int numOfUniqueValues = 1; numOfUniqueValues <= batchSize; numOfUniqueValues++) {
      parameters.add(
          getParameterList(
              batchSize * numOfBatches,
              batchSize,
              15,
              1,
              0,
              1,
              1,
              0,
              0,
              0,
              0,
              0,
              numOfUniqueValues,
              true,
              fileName));
    }
    return parameters;
  }

  private static Object[] getParameterList(
      int numberOfRows,
      int batchSize,
      int varcharLen,
      int numOfFixedPartitionCols,
      int numOfVarPartitionCols,
      int numOfOrderCols,
      int rowNumberCount,
      int rankCount,
      int leadCount,
      int lagCount,
      int sumCount,
      int countCount,
      int numOfUniquePartitions,
      boolean frameUnitsRows,
      String fileName) {
    List<FieldInfo> fieldInfos = new ArrayList<>();
    List<NamedExpression> windowFunctions = new ArrayList<>();

    // Add window function fields and expressions
    if (rowNumberCount > 0) {
      for (int i = 0; i < rowNumberCount; i++) {
        windowFunctions.add(n("row_number()", "row_number_" + i));
      }
    }
    if (rankCount > 0) {
      for (int i = 0; i < rankCount; i++) {
        windowFunctions.add(n("rank()", "rank_" + i));
      }
    }
    if (leadCount > 0) {
      List<FieldInfo> leadFields =
          getFieldInfos(new ArrowType.Int(32, true), batchSize, SortOrder.RANDOM, leadCount);
      fieldInfos.addAll(leadFields);
      List<String> leadFieldNames = getFieldNames(leadFields);
      for (String fieldName : leadFieldNames) {
        windowFunctions.add(n("lead(" + fieldName + ", 1)", "lead_" + fieldName));
      }
    }
    if (lagCount > 0) {
      List<FieldInfo> lagFields =
          getFieldInfos(new ArrowType.Int(32, true), batchSize, SortOrder.RANDOM, lagCount);
      fieldInfos.addAll(lagFields);
      List<String> lagFieldNames = getFieldNames(lagFields);
      for (String fieldName : lagFieldNames) {
        windowFunctions.add(n("lag(" + fieldName + ", 1)", "lag_" + fieldName));
      }
    }
    if (sumCount > 0) {
      List<FieldInfo> sumFields =
          getFieldInfos(new ArrowType.Int(32, true), batchSize, SortOrder.RANDOM, sumCount);
      fieldInfos.addAll(sumFields);
      List<String> sumFieldNames = getFieldNames(sumFields);
      for (String fieldName : sumFieldNames) {
        windowFunctions.add(n("sum(" + fieldName + ")", "sum_" + fieldName));
      }
    }
    if (countCount > 0) {
      List<FieldInfo> countFields =
          getFieldInfos(new ArrowType.Int(32, true), batchSize, SortOrder.RANDOM, countCount);
      fieldInfos.addAll(countFields);
      List<String> countFieldNames = getFieldNames(countFields);
      for (String fieldName : countFieldNames) {
        windowFunctions.add(n("count(" + fieldName + ")", "count_" + fieldName));
      }
    }

    // Add partition fields
    List<FieldInfo> partitionFields = new ArrayList<>();
    if (numOfFixedPartitionCols > 0) {
      partitionFields.addAll(
          getFieldInfos(
              new ArrowType.Int(32, true),
              numOfUniquePartitions,
              SortOrder.ASCENDING,
              numOfFixedPartitionCols));
    }
    if (numOfVarPartitionCols > 0) {
      partitionFields.addAll(
          getFieldInfos(
              new ArrowType.Utf8(),
              numOfUniquePartitions,
              SortOrder.ASCENDING,
              numOfVarPartitionCols));
    }

    List<String> partitionNames = getFieldNames(partitionFields);
    List<NamedExpression> partitions = getPartitionExpressions(partitionNames);
    fieldInfos.addAll(partitionFields);

    // Add order by fields
    List<FieldInfo> orderFields = new ArrayList<>();
    if (numOfOrderCols > 0) {
      orderFields.addAll(
          getFieldInfos(
              new ArrowType.Int(32, true), batchSize, SortOrder.ASCENDING, numOfOrderCols));
    }

    List<String> orderNames = getFieldNames(orderFields);
    List<Order.Ordering> orderings = getOrderExpressions(orderNames);
    fieldInfos.addAll(orderFields);

    // Default frame bounds (unbounded preceding to current row)
    Bound lowerBound = new Bound(true, 0, BoundType.PRECEDING);
    Bound upperBound = new Bound(false, 0, BoundType.CURRENT_ROW);

    return new Object[] {
      fieldInfos,
      numberOfRows,
      batchSize,
      varcharLen,
      numOfFixedPartitionCols,
      numOfVarPartitionCols,
      numOfOrderCols,
      partitions,
      windowFunctions,
      orderings,
      rowNumberCount,
      rankCount,
      leadCount,
      lagCount,
      sumCount,
      countCount,
      numOfUniquePartitions,
      frameUnitsRows,
      lowerBound,
      upperBound,
      0, // leadLagOffset (default for existing tests)
      fileName
    };
  }

  public static Collection<Object[]> scaleBatchSizesForFixedDataSize() throws Exception {
    List<Object[]> parameters = new ArrayList<>();

    String fileName = "WindowFrameScaleBatchSizesForFixedDataSize";
    writeFileHeader(fileName);
    for (int batchSize = 1000; batchSize <= 10_000; batchSize += 500) {

      long dataSize = 2880000000L;
      int numOfBatches = (int) (dataSize / (16 * batchSize));

      parameters.add(
          getParameterList(
              batchSize * numOfBatches,
              batchSize,
              15,
              2,
              0,
              1,
              2,
              0,
              0,
              0,
              0,
              0,
              batchSize,
              true,
              fileName));
    }
    return parameters;
  }

  public static Collection<Object[]> scaleNumberOfWindowFunctions(
      boolean scaleRowNumber,
      boolean scaleRank,
      boolean scaleLead,
      boolean scaleLag,
      boolean scaleSum,
      boolean scaleCount)
      throws Exception {
    int batchSize = 4000;
    int numOfBatches = 1000;
    List<Object[]> parameters = new ArrayList<>();

    String fileName = "WindowFrameScaleWindowFunctions";
    writeFileHeader(fileName);
    for (int numOfFunctions = 1; numOfFunctions <= 100; numOfFunctions += 3) {
      parameters.add(
          getParameterList(
              batchSize * numOfBatches,
              batchSize,
              15,
              1,
              0,
              1,
              scaleRowNumber ? numOfFunctions : 0,
              scaleRank ? numOfFunctions : 0,
              scaleLead ? numOfFunctions : 0,
              scaleLag ? numOfFunctions : 0,
              scaleSum ? numOfFunctions : 0,
              scaleCount ? numOfFunctions : 0,
              batchSize / 10,
              true,
              fileName));
    }
    return parameters;
  }

  public static Collection<Object[]> scaleBatchSizesWithVariousStringPartitionSizes()
      throws Exception {
    List<Object[]> parameters = new ArrayList<>();

    String fileName = "WindowFrameScaleBatchSizes";
    writeFileHeader(fileName);
    int dataSize = 320000000;
    for (int varcharSize = 10; varcharSize <= 1000; varcharSize += 50) {
      for (int batchSize = 100; batchSize <= 8000; batchSize += 200) {
        int numOfBatches = dataSize / batchSize / (varcharSize + 12);
        parameters.add(
            getParameterList(
                batchSize * numOfBatches,
                batchSize,
                varcharSize,
                1,
                1,
                1,
                0,
                0,
                0,
                0,
                2,
                0,
                batchSize / 2,
                true,
                fileName));
      }
    }
    return parameters;
  }

  public static Collection<Object[]> scaleAllWindowFunctionsBatches() throws Exception {
    int batchSize = 4000;
    List<Object[]> parameters = new ArrayList<>();

    String fileName = "WindowFrameScaleAllWindowFunctionsBatches";
    writeFileHeader(fileName);

    // Scale number of batches from 1 to 500
    for (int numOfBatches = 1; numOfBatches <= 500; numOfBatches += 10) {
      parameters.add(
          getParameterListAllWindowFunctions(
              batchSize * numOfBatches,
              batchSize,
              15, // varcharLen
              2, // numOfFixedPartitionCols
              1, // numOfVarPartitionCols
              2, // numOfOrderCols
              batchSize / 20, // numOfUniquePartitions
              true, // frameUnitsRows
              fileName));
    }
    return parameters;
  }

  private static Object[] getParameterListAllWindowFunctions(
      int numberOfRows,
      int batchSize,
      int varcharLen,
      int numOfFixedPartitionCols,
      int numOfVarPartitionCols,
      int numOfOrderCols,
      int numOfUniquePartitions,
      boolean frameUnitsRows,
      String fileName) {
    List<FieldInfo> fieldInfos = new ArrayList<>();
    List<NamedExpression> windowFunctions = new ArrayList<>();

    // Add all possible window functions
    // Ranking functions
    windowFunctions.add(n("row_number()", "row_number"));
    windowFunctions.add(n("rank()", "rank"));
    windowFunctions.add(n("dense_rank()", "dense_rank"));
    windowFunctions.add(n("percent_rank()", "percent_rank"));
    windowFunctions.add(n("cume_dist()", "cume_dist"));

    // Add fields for LEAD/LAG functions
    List<FieldInfo> leadLagFields =
        getFieldInfos(new ArrowType.Int(32, true), batchSize, SortOrder.RANDOM, 2);
    fieldInfos.addAll(leadLagFields);
    List<String> leadLagFieldNames = getFieldNames(leadLagFields);

    // Offset functions
    windowFunctions.add(
        n("lead(" + leadLagFieldNames.get(0) + ", 1)", "lead_" + leadLagFieldNames.get(0)));
    windowFunctions.add(
        n("lag(" + leadLagFieldNames.get(1) + ", 1)", "lag_" + leadLagFieldNames.get(1)));

    // Add fields for aggregate functions
    List<FieldInfo> aggFields =
        getFieldInfos(new ArrowType.Int(32, true), batchSize, SortOrder.RANDOM, 3);
    fieldInfos.addAll(aggFields);
    List<String> aggFieldNames = getFieldNames(aggFields);

    // Aggregate functions
    windowFunctions.add(n("sum(" + aggFieldNames.get(0) + ")", "sum_" + aggFieldNames.get(0)));
    windowFunctions.add(n("count(" + aggFieldNames.get(1) + ")", "count_" + aggFieldNames.get(1)));
    windowFunctions.add(n("avg(" + aggFieldNames.get(2) + ")", "avg_" + aggFieldNames.get(2)));
    windowFunctions.add(n("min(" + aggFieldNames.get(0) + ")", "min_" + aggFieldNames.get(0)));
    windowFunctions.add(n("max(" + aggFieldNames.get(1) + ")", "max_" + aggFieldNames.get(1)));

    // Add fields for first_value/last_value functions
    List<FieldInfo> valueFields =
        getFieldInfos(new ArrowType.Int(32, true), batchSize, SortOrder.RANDOM, 2);
    fieldInfos.addAll(valueFields);
    List<String> valueFieldNames = getFieldNames(valueFields);

    // Value functions
    windowFunctions.add(
        n("first_value(" + valueFieldNames.get(0) + ")", "first_value_" + valueFieldNames.get(0)));
    windowFunctions.add(
        n("last_value(" + valueFieldNames.get(1) + ")", "last_value_" + valueFieldNames.get(1)));

    // Add partition fields
    List<FieldInfo> partitionFields = new ArrayList<>();
    if (numOfFixedPartitionCols > 0) {
      partitionFields.addAll(
          getFieldInfos(
              new ArrowType.Int(32, true),
              numOfUniquePartitions,
              SortOrder.ASCENDING,
              numOfFixedPartitionCols));
    }
    if (numOfVarPartitionCols > 0) {
      partitionFields.addAll(
          getFieldInfos(
              new ArrowType.Utf8(),
              numOfUniquePartitions,
              SortOrder.ASCENDING,
              numOfVarPartitionCols));
    }

    List<String> partitionNames = getFieldNames(partitionFields);
    List<NamedExpression> partitions = getPartitionExpressions(partitionNames);
    fieldInfos.addAll(partitionFields);

    // Add order by fields
    List<FieldInfo> orderFields = new ArrayList<>();
    if (numOfOrderCols > 0) {
      orderFields.addAll(
          getFieldInfos(
              new ArrowType.Int(32, true), batchSize, SortOrder.ASCENDING, numOfOrderCols));
    }

    List<String> orderNames = getFieldNames(orderFields);
    List<Order.Ordering> orderings = getOrderExpressions(orderNames);
    fieldInfos.addAll(orderFields);

    // Default frame bounds (unbounded preceding to current row)
    Bound lowerBound = new Bound(true, 0, BoundType.PRECEDING);
    Bound upperBound = new Bound(false, 0, BoundType.CURRENT_ROW);

    return new Object[] {
      fieldInfos,
      numberOfRows,
      batchSize,
      varcharLen,
      numOfFixedPartitionCols,
      numOfVarPartitionCols,
      numOfOrderCols,
      partitions,
      windowFunctions,
      orderings,
      1, // rowNumberCount
      1, // rankCount
      1, // leadCount
      1, // lagCount
      3, // sumCount
      3, // countCount
      numOfUniquePartitions,
      frameUnitsRows,
      lowerBound,
      upperBound,
      0, // leadLagOffset (default for existing tests)
      fileName
    };
  }

  public static Collection<Object[]> scaleLeadLagOffsetAndBatchSize() throws Exception {
    int numOfBatches = 4000; // Keep constant
    List<Object[]> parameters = new ArrayList<>();

    String fileName = "WindowFrameScaleLeadLagOffsetAndBatchSize";
    writeFileHeader(fileName);

    // Outer loop: Scale batch size from 1 to 100
    for (int batchSize = 100; batchSize <= 500; batchSize += 10) {
      int totalRecords = batchSize * numOfBatches;

      // Inner loop: Scale offset from 0 to (100 * batchSize)
      int maxOffset = 100 * batchSize;
      for (int offset = 0; offset <= maxOffset; offset += 50) { // 20 steps for each batch size
        parameters.add(
            getParameterListLeadLag(
                totalRecords,
                batchSize,
                15, // varcharLen
                1, // numOfFixedPartitionCols
                0, // numOfVarPartitionCols
                1, // numOfOrderCols
                Math.min(batchSize, 10), // numOfUniquePartitions
                offset, // leadLagOffset
                true, // frameUnitsRows
                fileName));
      }
    }
    return parameters;
  }

  private static Object[] getParameterListLeadLag(
      int numberOfRows,
      int batchSize,
      int varcharLen,
      int numOfFixedPartitionCols,
      int numOfVarPartitionCols,
      int numOfOrderCols,
      int numOfUniquePartitions,
      int leadLagOffset,
      boolean frameUnitsRows,
      String fileName) {
    List<FieldInfo> fieldInfos = new ArrayList<>();
    List<NamedExpression> windowFunctions = new ArrayList<>();

    // Add fields for LEAD/LAG functions
    List<FieldInfo> leadLagFields =
        getFieldInfos(new ArrowType.Int(32, true), batchSize, SortOrder.RANDOM, 2);
    fieldInfos.addAll(leadLagFields);
    List<String> leadLagFieldNames = getFieldNames(leadLagFields);

    // Add LEAD and LAG functions with the specified offset
    windowFunctions.add(
        n(
            "lead(" + leadLagFieldNames.get(0) + ", " + leadLagOffset + ")",
            "lead_" + leadLagFieldNames.get(0) + "_" + leadLagOffset));
    windowFunctions.add(
        n(
            "lag(" + leadLagFieldNames.get(1) + ", " + leadLagOffset + ")",
            "lag_" + leadLagFieldNames.get(1) + "_" + leadLagOffset));

    // Add partition fields
    List<FieldInfo> partitionFields = new ArrayList<>();
    if (numOfFixedPartitionCols > 0) {
      partitionFields.addAll(
          getFieldInfos(
              new ArrowType.Int(32, true),
              numOfUniquePartitions,
              SortOrder.ASCENDING,
              numOfFixedPartitionCols));
    }
    if (numOfVarPartitionCols > 0) {
      partitionFields.addAll(
          getFieldInfos(
              new ArrowType.Utf8(),
              numOfUniquePartitions,
              SortOrder.ASCENDING,
              numOfVarPartitionCols));
    }

    List<String> partitionNames = getFieldNames(partitionFields);
    List<NamedExpression> partitions = getPartitionExpressions(partitionNames);
    fieldInfos.addAll(partitionFields);

    // Add order by fields
    List<FieldInfo> orderFields = new ArrayList<>();
    if (numOfOrderCols > 0) {
      orderFields.addAll(
          getFieldInfos(
              new ArrowType.Int(32, true), batchSize, SortOrder.ASCENDING, numOfOrderCols));
    }

    List<String> orderNames = getFieldNames(orderFields);
    List<Order.Ordering> orderings = getOrderExpressions(orderNames);
    fieldInfos.addAll(orderFields);

    // Default frame bounds (unbounded preceding to current row)
    Bound lowerBound = new Bound(true, 0, BoundType.PRECEDING);
    Bound upperBound = new Bound(false, 0, BoundType.CURRENT_ROW);

    return new Object[] {
      fieldInfos,
      numberOfRows,
      batchSize,
      varcharLen,
      numOfFixedPartitionCols,
      numOfVarPartitionCols,
      numOfOrderCols,
      partitions,
      windowFunctions,
      orderings,
      0, // rowNumberCount
      0, // rankCount
      1, // leadCount
      1, // lagCount
      0, // sumCount
      0, // countCount
      numOfUniquePartitions,
      frameUnitsRows,
      lowerBound,
      upperBound,
      leadLagOffset,
      fileName
    };
  }

  private void writeResultsCSV(
      OperatorStats stats,
      String fileName,
      int varcharLen,
      int numOfBatches,
      int batchSize,
      int numOfFixedPartitionColumns,
      int numOfVarPartitionColumns,
      int numOfOrderColumns,
      int rowNumberCount,
      int rankCount,
      int leadCount,
      int lagCount,
      int sumCount,
      int countCount,
      int numOfUniquePartitions,
      boolean frameUnitsRows,
      String exception,
      String stackTrace)
      throws IOException {
    String filePath = FILE_PATH + fileName + ".csv";
    System.out.println("The results will be saved to: " + filePath);
    try (CSVWriter writer = new CSVWriter(new FileWriter(filePath, true))) {
      List<String[]> metrics = new ArrayList<>();

      // Format bounds information
      String lowerBoundStr = formatBound(lowerBound);
      String upperBoundStr = formatBound(upperBound);

      // Get WindowFrame timing metrics
      long setupTime = stats != null ? stats.getLongStat(WindowFrameStats.Metric.SETUP_MILLIS) : -1;
      long consumeTime =
          stats != null ? stats.getLongStat(WindowFrameStats.Metric.CONSUME_MILLIS) : -1;
      long outputTime =
          stats != null ? stats.getLongStat(WindowFrameStats.Metric.OUTPUT_MILLIS) : -1;

      metrics.add(
          new String[] {
            String.valueOf(batchSize),
            String.valueOf(varcharLen),
            String.valueOf(numOfFixedPartitionColumns),
            String.valueOf(numOfVarPartitionColumns),
            String.valueOf(numOfOrderColumns),
            String.valueOf(numOfBatches),
            String.valueOf(rowNumberCount),
            String.valueOf(rankCount),
            String.valueOf(leadCount),
            String.valueOf(lagCount),
            String.valueOf(sumCount),
            String.valueOf(countCount),
            String.valueOf(numOfUniquePartitions),
            String.valueOf(frameUnitsRows),
            lowerBoundStr,
            upperBoundStr,
            String.valueOf(leadLagOffset),
            String.valueOf(setupTime),
            String.valueOf(consumeTime),
            String.valueOf(outputTime),
            String.valueOf(setupTime + consumeTime + outputTime),
            exception,
            stackTrace
          });

      writer.writeAll(metrics);
    }
  }

  private static void writeFileHeader(String fileName) throws IOException {
    // Check if header has already been written for this file
    if (headersWritten.contains(fileName)) {
      return;
    }

    String filePath = FILE_PATH + fileName + ".csv";
    System.out.println("The results will be saved to: " + filePath);
    try (CSVWriter writer =
        new CSVWriter(new FileWriter(filePath, false))) { // Use false to overwrite existing file
      List<String[]> header = new ArrayList<>();
      header.add(
          new String[] {
            "Batch Size",
            "String Col Len",
            "No. of Fixed Partition Cols",
            "No. of Var Partition Cols",
            "No. of Order Cols",
            "No. of Batches",
            "Row Number Functions",
            "Rank Functions",
            "Lead Functions",
            "Lag Functions",
            "Sum Functions",
            "Count Functions",
            "No. of Unique Partitions",
            "Frame Units Rows",
            "Lower Bound",
            "Upper Bound",
            "Lead/Lag Offset",
            "Setup Time (ms)",
            "Consume Time (ms)",
            "Output Time (ms)",
            "Total Time (ms)",
            "Exception",
            "Exception Stack Trace"
          });
      writer.writeAll(header);
      headersWritten.add(fileName); // Mark this file as having its header written
    }
  }

  private static List<NamedExpression> getPartitionExpressions(List<String> fieldNames) {
    List<NamedExpression> expressions = new ArrayList<>();
    for (String fieldName : fieldNames) {
      expressions.add(n(fieldName));
    }
    return expressions;
  }

  private static List<Order.Ordering> getOrderExpressions(List<String> fieldNames) {
    List<Order.Ordering> orderings = new ArrayList<>();
    for (String fieldName : fieldNames) {
      orderings.add(
          new Order.Ordering(Direction.ASCENDING, parseExpr(fieldName), NullDirection.FIRST));
    }
    return orderings;
  }

  private static String formatBound(Bound bound) {
    if (bound == null) {
      return "NULL";
    }

    StringBuilder sb = new StringBuilder();
    if (bound.isUnbounded()) {
      sb.append("UNBOUNDED ");
    } else {
      sb.append(bound.getOffset()).append(" ");
    }

    switch (bound.getType()) {
      case PRECEDING:
        sb.append("PRECEDING");
        break;
      case FOLLOWING:
        sb.append("FOLLOWING");
        break;
      case CURRENT_ROW:
        sb.append("CURRENT ROW");
        break;
      default:
        sb.append("UNKNOWN");
        break;
    }

    return sb.toString();
  }
}
