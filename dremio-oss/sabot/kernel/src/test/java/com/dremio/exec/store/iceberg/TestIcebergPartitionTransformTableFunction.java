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

import static com.dremio.exec.planner.physical.TableFunctionUtil.getIcebergPartitionTransformTableFunctionConfig;
import static com.dremio.exec.planner.physical.visitor.WriterUpdater.getColumns;
import static com.dremio.exec.store.iceberg.manifestwriter.TestIcebergCommitOpHelper.getIcebergTableProps;
import static org.assertj.core.api.Assertions.assertThat;

import com.dremio.exec.physical.config.TableFunctionConfig;
import com.dremio.exec.physical.config.TableFunctionPOP;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.RecordBatchData;
import com.dremio.exec.store.dfs.IcebergTableProps;
import com.dremio.exec.store.iceberg.model.IcebergCommandType;
import com.dremio.sabot.BaseTestTableFunction;
import com.dremio.sabot.BatchDataGenerator;
import com.dremio.sabot.FieldInfo;
import com.dremio.sabot.FieldInfo.SortOrder;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestIcebergPartitionTransformTableFunction extends BaseTestTableFunction {

  private static final List<FieldInfo> fieldInfos =
      ImmutableList.of(
          new FieldInfo("IntCol", new ArrowType.Int(32, true), 10_000, SortOrder.ASCENDING),
          new FieldInfo("StringCol", new ArrowType.Utf8(), 10_000, SortOrder.DESCENDING),
          new FieldInfo(
              "TimeCol", new ArrowType.Date(DateUnit.MILLISECOND), 10_000, SortOrder.ASCENDING));

  private static BatchSchema outputSchema;
  private static Schema icebergSchema;
  private static PartitionSpec spec;

  private static List<String> partitionPaths;

  @BeforeClass
  public static void init() {
    // Iceberg schema
    List<Types.NestedField> columns = new ArrayList<>();
    for (int i = 0; i < fieldInfos.size(); i++) {
      Field field = fieldInfos.get(i).getField();
      Type type;
      switch (field.getType().getTypeID()) {
        case Int:
          type = Types.IntegerType.get();
          break;
        case Utf8:
          type = Types.StringType.get();
          break;
        case Date:
          type = Types.DateType.get();
          break;
        default:
          throw new IllegalArgumentException();
      }
      columns.add(Types.NestedField.optional(i + 1, field.getName(), type));
    }
    icebergSchema = new Schema(columns);

    spec = PartitionSpec.builderFor(icebergSchema).day("TimeCol").build();
    partitionPaths = ImmutableList.of("/mock/path/to/table");

    Map<Integer, Field> fieldMap = new HashMap<>();
    for (int i = 0; i < fieldInfos.size(); i++) {
      Field field = fieldInfos.get(i).getField();
      fieldMap.put(i + 1, field);
    }
    outputSchema =
        BatchSchema.newBuilder()
            .addFields(fieldInfos.stream().map(FieldInfo::getField).collect(Collectors.toList()))
            .addFields(
                spec.fields().stream()
                    .map(
                        field ->
                            Field.notNullable(
                                field.name(), fieldMap.get(field.sourceId()).getType()))
                    .collect(Collectors.toList()))
            .build();
  }

  @Test
  public void testPartitionTransformTableFunctionSameInputOutputBatchSizes() throws Exception {
    int inputBatchSize = 1000;
    int outputBatchSize = 1000;

    testPartitionTransformTableFunctionImpl(inputBatchSize, outputBatchSize);
  }

  @Test
  public void testPartitionTransformTableFunctionSameInputBatchSizeIsGreaterThanOutputSizes()
      throws Exception {
    int inputBatchSize = 1000;
    int outputBatchSize = 400;

    testPartitionTransformTableFunctionImpl(inputBatchSize, outputBatchSize);
  }

  @Test
  public void testPartitionTransformTableFunctionSameInputBatchSizeIsLessThanOutputSizes()
      throws Exception {
    int inputBatchSize = 400;
    int outputBatchSize = 1000;

    testPartitionTransformTableFunctionImpl(inputBatchSize, outputBatchSize);
  }

  private void testPartitionTransformTableFunctionImpl(int inputBatchSize, int outputBatchSize)
      throws Exception {
    List<RecordBatchData> output = null;
    int inputRowCount = 10_000;
    try (BatchDataGenerator dg =
        new BatchDataGenerator(
            fieldInfos, getTestAllocator(), inputRowCount, inputBatchSize, 15, Integer.MAX_VALUE)) {
      output =
          getTableFunctionOutput(
              getTableFunctionPop(), dg, inputBatchSize, outputBatchSize, testContext);
      int totalOutputCount = 0;
      for (RecordBatchData outputBatch : output) {
        int batchOutRowCount = outputBatch.getRecordCount();
        assertThat(batchOutRowCount).isLessThanOrEqualTo(outputBatchSize);
        totalOutputCount += batchOutRowCount;
      }
      assertThat(totalOutputCount).isEqualTo(inputRowCount);
    } finally {
      if (output != null) {
        for (RecordBatchData batch : output) {
          batch.close();
        }
      }
    }
  }

  private TableFunctionPOP getTableFunctionPop() {
    IcebergTableProps icebergTableProps =
        getIcebergTableProps(IcebergCommandType.INSERT, icebergSchema, spec, partitionPaths);
    TableFunctionConfig config =
        getIcebergPartitionTransformTableFunctionConfig(
            icebergTableProps, outputSchema, getColumns(outputSchema));

    return new TableFunctionPOP(PROPS, null, config);
  }
}
