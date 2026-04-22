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

import static com.dremio.sabot.Fixtures.t;
import static com.dremio.sabot.Fixtures.th;
import static com.dremio.sabot.Fixtures.tr;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.physical.config.TableFunctionConfig;
import com.dremio.exec.physical.config.TableFunctionContext;
import com.dremio.exec.physical.config.TableFunctionPOP;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.RecordBatchData;
import com.dremio.exec.util.ColumnUtils;
import com.dremio.sabot.BaseTestTableFunction;
import com.dremio.sabot.BatchDataGenerator;
import com.dremio.sabot.FieldInfo;
import com.dremio.sabot.Fixtures.Table;
import com.dremio.sabot.Generator;
import com.dremio.sabot.op.spi.SingleInputOperator;
import com.dremio.sabot.op.tablefunction.TableFunctionOperator;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.junit.Test;

/**
 * Test class that verifies IcebergDmlMergeDuplicateCheckTableFunction handles cases where input
 * batch size differs from output batch size. This tests the row-by-row processing logic to ensure
 * duplicate detection works correctly across different batch boundaries.
 */
public class TestIcebergDmlMergeDuplicateCheckTableFunctionVariantTargetBatchSize
    extends BaseTestTableFunction {

  private static final String ID = "id";
  private static final String DATA = "data";

  private static final Table SMALL_CUSTOM_TABLE = createSmallTestTable();
  private static final Table SMALL_CUSTOM_TABLE_WITH_DUPLICATES = createTestTableWithDuplicates();

  // Field definitions for BatchDataGenerator - now supports BIGINT!
  // Note: We need data sorted by FILE_PATH first, then ROW_INDEX within each file
  // Cardinality calculation: 100 file paths * 10,000 row indices = 1,000,000 unique combinations
  // This ensures we can generate up to 1M unique rows without duplicates
  private static final List<FieldInfo> FIELD_INFOS =
      ImmutableList.of(
          new FieldInfo(ID, new ArrowType.Int(32, true), 1000, FieldInfo.SortOrder.RANDOM),
          new FieldInfo(DATA, new ArrowType.Utf8(), 500, FieldInfo.SortOrder.RANDOM),
          new FieldInfo(
              ColumnUtils.FILE_PATH_COLUMN_NAME,
              new ArrowType.Utf8(),
              1,
              FieldInfo.SortOrder.ASCENDING),
          new FieldInfo(
              ColumnUtils.ROW_INDEX_COLUMN_NAME,
              new ArrowType.Int(64, true),
              10001,
              FieldInfo.SortOrder.ASCENDING));

  private static Table createSmallTestTable() {
    return t(
        th(ID, DATA, ColumnUtils.FILE_PATH_COLUMN_NAME, ColumnUtils.ROW_INDEX_COLUMN_NAME),
        tr(0, "zero", "file_path_1", 0L),
        tr(1, "one", "file_path_1", 1L),
        tr(2, "two", "file_path_1", 2L));
  }

  private static Table createTestTableWithDuplicates() {
    // Create a table with consecutive duplicates that the function can detect
    // The duplicate check function only detects consecutive duplicates
    return t(
        th(ID, DATA, ColumnUtils.FILE_PATH_COLUMN_NAME, ColumnUtils.ROW_INDEX_COLUMN_NAME),
        tr(0, "data_0", "file_path_0", 0L),
        tr(1, "data_1", "file_path_0", 1L),
        tr(2, "data_2", "file_path_0", 2L),
        tr(3, "data_3", "file_path_0", 2L), // Consecutive duplicate!
        tr(4, "data_4", "file_path_1", 0L));
  }

  // Schema for the Iceberg table. FilePath and RowIndex are necessary for Duplicate Check TF.
  private static final BatchSchema ICEBERG_TEST_SCHEMA =
      BatchSchema.newBuilder()
          .addField(Field.nullable(ID, Types.MinorType.INT.getType()))
          .addField(Field.nullable(DATA, Types.MinorType.VARCHAR.getType()))
          .addField(
              Field.nullable(ColumnUtils.FILE_PATH_COLUMN_NAME, Types.MinorType.VARCHAR.getType()))
          .addField(
              Field.nullable(ColumnUtils.ROW_INDEX_COLUMN_NAME, Types.MinorType.BIGINT.getType()))
          .build();

  // equal input and output batch sizes
  @Test
  public void testSameInputOutputBatchSizes() throws Exception {
    validateTableFunctionWithLargeDataset(10_000, 4000, 4000, false);
  }

  // input batch size is greater than output batch size
  @Test
  public void testInputBatchSizeGreaterThanOutputBatchSize() throws Exception {
    validateTableFunctionWithLargeDataset(10_000, 4000, 100, false);
  }

  // input batch size is less than output batch size
  @Test
  public void testInputBatchSizeLessThanOutputBatchSize() throws Exception {
    validateTableFunctionWithLargeDataset(10_000, 100, 4000, false);
  }

  // very small output batch size
  @Test
  public void testVerySmallOutputBatchSize() throws Exception {
    validateTableFunctionWithLargeDataset(5_000, 4000, 100, false);
  }

  // very small input batch size
  @Test
  public void testVerySmallInputBatchSize() throws Exception {
    validateTableFunctionWithLargeDataset(5_000, 100, 4000, false);
  }

  // large dataset with mismatched batch sizes
  @Test
  public void testLargeDatasetWithMismatchedBatchSizes() throws Exception {
    validateTableFunctionWithLargeDataset(10_000, 398, 783, false);
  }

  // single row batches
  @Test
  public void testSingleRowBatches() throws Exception {
    Table input = SMALL_CUSTOM_TABLE;
    validateTableFunctionWithBatchSizes(input, 1, 1, false);
  }

  // duplicate detection test 1
  @Test
  public void testDuplicateDetectionAcrossBatchBoundaries() throws Exception {
    // Test that duplicates are detected even when they span across different output batches
    Table input = SMALL_CUSTOM_TABLE_WITH_DUPLICATES;
    validateTableFunctionWithBatchSizes(input, 1000, 100, true);
  }

  // duplicate detection test 2
  @Test
  public void testDuplicateDetectionWithSmallInputBatches() throws Exception {
    // Test that duplicates are detected when input comes in small batches
    Table input = SMALL_CUSTOM_TABLE_WITH_DUPLICATES;
    validateTableFunctionWithBatchSizes(input, 50, 1000, true);
  }

  // duplicate detection test 3
  @Test
  public void testDuplicateDetectionWithMismatchedBatchSizes() throws Exception {
    // Test duplicate detection with completely mismatched batch sizes
    Table input = SMALL_CUSTOM_TABLE_WITH_DUPLICATES;
    validateTableFunctionWithBatchSizes(input, 333, 777, true);
  }

  /**
   * Helper method to validate the table function with custom bathes defined in the constants. If
   * expectDuplicateError is true, test should throw a dups exception.
   */
  private void validateTableFunctionWithBatchSizes(
      Table input, int inputBatchSize, int outputBatchSize, boolean expectDuplicateError)
      throws Exception {
    if (expectDuplicateError) {
      // Test should throw an exception for duplicates
      assertThatThrownBy(
              () ->
                  validateTableFunctionWithCustomBatchSizes(
                      getTableFunctionPop(), input, inputBatchSize, outputBatchSize))
          .isInstanceOf(Exception.class)
          .hasMessageContaining("A target row matched more than once. Please update your query.");
    } else {
      // Test should succeed without duplicates - just verify it doesn't throw
      validateTableFunctionWithCustomBatchSizes(
          getTableFunctionPop(), input, inputBatchSize, outputBatchSize);
    }
  }

  /**
   * Helper method to validate the table function with large dataset created by the BatchGenerator.
   * If expectDuplicateError is true, test should throw a dups exception.
   */
  private void validateTableFunctionWithLargeDataset(
      int inputRowCount, int inputBatchSize, int outputBatchSize, boolean expectDuplicateError)
      throws Exception {
    if (expectDuplicateError) {
      // Test should throw an exception for duplicates
      assertThatThrownBy(
              () ->
                  validateTableFunctionWithLargeDatasetImpl(
                      inputRowCount, inputBatchSize, outputBatchSize))
          .isInstanceOf(Exception.class)
          .hasMessageContaining("A target row matched more than once. Please update your query.");
    } else {
      // Test should succeed without duplicates - just verify it doesn't throw
      validateTableFunctionWithLargeDatasetImpl(inputRowCount, inputBatchSize, outputBatchSize);
    }
  }

  /**
   * Helper method to validate the table function with large dataset created by the BatchGenerator
   * with custom batch sizes
   */
  private void validateTableFunctionWithLargeDatasetImpl(
      int inputRowCount, int inputBatchSize, int outputBatchSize) throws Exception {
    List<RecordBatchData> output = null;
    try (BatchDataGenerator dg =
        new BatchDataGenerator(
            FIELD_INFOS,
            getTestAllocator(),
            inputRowCount,
            inputBatchSize,
            15,
            Integer.MAX_VALUE,
            true)) { // deterministic=true

      output =
          getTableFunctionOutput(
              getTableFunctionPop(), dg, inputBatchSize, outputBatchSize, testContext);

      int totalOutputCount = 0;
      for (RecordBatchData outputBatch : output) {
        int batchOutRowCount = outputBatch.getRecordCount();
        totalOutputCount += batchOutRowCount;
      }

      // Verify we got the expected number of output rows
      assertThat(totalOutputCount).isEqualTo(inputRowCount);

      // Verify schema is preserved
      if (!output.isEmpty()) {
        assertThat(output.get(0).getSchema()).isEqualTo(ICEBERG_TEST_SCHEMA);
      }
    } finally {
      if (output != null) {
        for (RecordBatchData batch : output) {
          batch.close();
        }
      }
    }
  }

  /**
   * sets up the Dups Check Tablefunction on a custom batch declared in our static classes above.
   * i.e. Not generated by BatchCreator
   */
  private void validateTableFunctionWithCustomBatchSizes(
      TableFunctionPOP pop, Table input, int inputBatchSize, int outputBatchSize) throws Exception {
    // Use a custom validation method that tests different batch sizes
    // This is similar to validateSingle but allows us to specify different input/output batch sizes
    try (TableFunctionOperator op = newOperator(TableFunctionOperator.class, pop, outputBatchSize);
        Generator generator = input.toGenerator(getTestAllocator())) {

      op.setup(generator.getOutput());
      int totalOutputCount = 0;
      int count;

      while (op.getState() != SingleInputOperator.State.DONE
          && (count = generator.next(inputBatchSize)) != 0) {
        assertState(op, SingleInputOperator.State.CAN_CONSUME);
        op.consumeData(count);

        while (op.getState() == SingleInputOperator.State.CAN_PRODUCE) {
          int recordsOutput = op.outputData();
          assertThat(recordsOutput).isLessThanOrEqualTo(outputBatchSize);
          totalOutputCount += recordsOutput;
        }
      }

      if (op.getState() == SingleInputOperator.State.CAN_CONSUME) {
        op.noMoreToConsume();
      }

      while (op.getState() == SingleInputOperator.State.CAN_PRODUCE) {
        int recordsOutput = op.outputData();
        totalOutputCount += recordsOutput;
      }

      assertState(op, SingleInputOperator.State.DONE);

      // Verify we got some output
      assertThat(totalOutputCount).isGreaterThan(0);
    }
  }

  private TableFunctionPOP getTableFunctionPop() {
    return new TableFunctionPOP(
        PROPS,
        null,
        new TableFunctionConfig(
            TableFunctionConfig.FunctionType.ICEBERG_DML_MERGE_DUPLICATE_CHECK,
            true,
            new TableFunctionContext(
                null,
                ICEBERG_TEST_SCHEMA,
                null,
                null,
                null,
                null,
                null,
                null,
                ICEBERG_TEST_SCHEMA.getFields().stream()
                    .map(f -> SchemaPath.getSimplePath(f.getName()))
                    .collect(Collectors.toList()),
                null,
                null,
                false,
                false,
                false,
                null)));
  }
}
