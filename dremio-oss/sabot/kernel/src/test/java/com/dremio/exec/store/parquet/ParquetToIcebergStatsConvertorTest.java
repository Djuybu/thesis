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
package com.dremio.exec.store.parquet;

import static com.dremio.common.expression.CompleteType.BIGINT;
import static com.dremio.common.expression.CompleteType.INT;
import static com.dremio.common.expression.CompleteType.VARCHAR;
import static com.dremio.common.expression.CompleteType.struct;
import static org.apache.iceberg.types.Types.NestedField.required;
import static org.apache.parquet.hadoop.metadata.CompressionCodecName.GZIP;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.BINARY;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.BOOLEAN;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.FLOAT;
import static org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName.INT32;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dremio.common.expression.CompleteType;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.iceberg.SchemaConverter;
import com.google.common.io.Resources;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.apache.arrow.vector.complex.MapVector;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.iceberg.Metrics;
import org.apache.iceberg.Schema;
import org.apache.iceberg.types.Types.BinaryType;
import org.apache.iceberg.types.Types.BooleanType;
import org.apache.iceberg.types.Types.FloatType;
import org.apache.iceberg.types.Types.IntegerType;
import org.apache.parquet.column.statistics.BinaryStatistics;
import org.apache.parquet.column.statistics.BooleanStatistics;
import org.apache.parquet.column.statistics.FloatStatistics;
import org.apache.parquet.column.statistics.IntStatistics;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.hadoop.metadata.ColumnPath;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

public class ParquetToIcebergStatsConvertorTest {
  private final SchemaConverter schemaConverter = SchemaConverter.getBuilder().build();

  @Test
  public void testMinMaxWhenFileContainsNoBlocks() {
    ParquetMetadata parquetMetadata = new ParquetMetadata(null, new ArrayList<>());
    assertNoMinMax(parquetMetadata, null);
  }

  @Test
  public void testMinMaxWhenBlocksContainNoColumns() {
    List<BlockMetaData> blocks = new ArrayList<>();
    blocks.add(new BlockMetaData());
    blocks.add(new BlockMetaData());
    ParquetMetadata parquetMetadata = new ParquetMetadata(null, blocks);

    assertNoMinMax(parquetMetadata, null);
  }

  @Test
  public void testMinMaxWhenColumnsHaveNoStats() {
    List<BlockMetaData> blocks = new ArrayList<>();
    blocks.add(floatBlock());
    blocks.add(intBlock());

    ParquetMetadata parquetMetadata = new ParquetMetadata(null, blocks);
    Schema schema =
        new Schema(required(1, "id", IntegerType.get()), required(2, "currency", FloatType.get()));

    assertNoMinMax(parquetMetadata, schema);
  }

  @Test
  public void testMinMaxForBooleanColumn() {
    List<BlockMetaData> blocks = new ArrayList<>();
    blocks.add(booleanBlockWithMinMax());
    blocks.add(booleanBlockWithMinMax2());

    ParquetMetadata parquetMetadata = new ParquetMetadata(null, blocks);
    Schema schema = new Schema(required(1, "eligible", BooleanType.get()));

    Metrics metrics = ParquetToIcebergStatsConvertor.toMetrics(parquetMetadata, schema, Map.of());
    assertEquals(0, metrics.lowerBounds().get(1).get(0));
    assertEquals(1, metrics.upperBounds().get(1).get(0));
  }

  @Test
  public void testMinMaxForIntColumn() {
    List<BlockMetaData> blocks = new ArrayList<>();
    blocks.add(intBlockWithMinMax());
    blocks.add(intBlockWithMinMax2());

    ParquetMetadata parquetMetadata = new ParquetMetadata(null, blocks);
    Schema schema = new Schema(required(1, "id", IntegerType.get()));

    Metrics metrics = ParquetToIcebergStatsConvertor.toMetrics(parquetMetadata, schema, Map.of());
    assertEquals(-1, metrics.lowerBounds().get(1).getInt(0));
    assertEquals(10000, metrics.upperBounds().get(1).getInt(0));
  }

  @Test
  public void testMinMaxForFloatColumn() {
    List<BlockMetaData> blocks = new ArrayList<>();
    blocks.add(floatBlockWithMinMax());
    blocks.add(floatBlockWithMinMax2());

    ParquetMetadata parquetMetadata = new ParquetMetadata(null, blocks);
    Schema schema = new Schema(required(1, "currency", FloatType.get()));

    Metrics metrics = ParquetToIcebergStatsConvertor.toMetrics(parquetMetadata, schema, Map.of());
    assertTrue(metrics.lowerBounds().get(1).getFloat(0) == -0.1f);
    assertTrue(metrics.upperBounds().get(1).getFloat(0) == 10000.1f);
  }

  @Test
  public void testMinMaxForBinaryColumn() {
    List<BlockMetaData> blocks = new ArrayList<>();
    blocks.add(binaryBlockWithMinMax());
    blocks.add(binaryBlockWithMinMax2());

    ParquetMetadata parquetMetadata = new ParquetMetadata(null, blocks);
    Schema schema = new Schema(required(1, "data", BinaryType.get()));

    Metrics metrics = ParquetToIcebergStatsConvertor.toMetrics(parquetMetadata, schema, Map.of());
    assertEquals('a', metrics.lowerBounds().get(1).get(0));
    assertEquals('z', metrics.upperBounds().get(1).get(0));
  }

  @Test
  public void testMinMax_oneBlockHasStats() {
    List<BlockMetaData> blocks = new ArrayList<>();
    blocks.add(intBlockWithMinMax());
    blocks.add(floatBlock());

    ParquetMetadata parquetMetadata = new ParquetMetadata(null, blocks);
    Schema schema =
        new Schema(required(1, "id", IntegerType.get()), required(2, "currency", FloatType.get()));

    Metrics metrics = ParquetToIcebergStatsConvertor.toMetrics(parquetMetadata, schema, Map.of());
    assertEquals(-1, metrics.lowerBounds().get(1).getInt(0));
    assertEquals(1000, metrics.upperBounds().get(1).getInt(0));
  }

  @Test
  public void testMinMaxErasureIfSameColDoesNotHaveStatsInAllBlocks() {
    List<BlockMetaData> blocks = new ArrayList<>();
    blocks.add(intBlockWithMinMax());
    blocks.add(intBlock());

    ParquetMetadata parquetMetadata = new ParquetMetadata(null, blocks);
    Schema schema = new Schema(required(1, "id", IntegerType.get()));

    assertNoMinMax(parquetMetadata, schema);
  }

  @Test
  public void testMinMaxForDifferentColumnsInDifferentBlocks() {
    List<BlockMetaData> blocks = new ArrayList<>();
    blocks.add(intBlockWithMinMax());
    blocks.add(floatBlockWithMinMax());

    ParquetMetadata parquetMetadata = new ParquetMetadata(null, blocks);
    Schema schema =
        new Schema(required(1, "id", IntegerType.get()), required(2, "currency", FloatType.get()));

    Metrics metrics = ParquetToIcebergStatsConvertor.toMetrics(parquetMetadata, schema, Map.of());
    assertEquals(-1, metrics.lowerBounds().get(1).getInt(0));
    assertEquals(1000, metrics.upperBounds().get(1).getInt(0));

    assertEquals(-0.1f, metrics.lowerBounds().get(2).getFloat(0), 0.0);
    assertEquals(1000.1f, metrics.upperBounds().get(2).getFloat(0), 0.0);
  }

  @Test
  public void testMinMaxOneBlockHasHeterogeneousColumns() {
    List<BlockMetaData> blocks = new ArrayList<>();
    blocks.add(intFloatBlockWithMinMax());

    ParquetMetadata parquetMetadata = new ParquetMetadata(null, blocks);
    Schema schema =
        new Schema(required(1, "id", IntegerType.get()), required(2, "currency", FloatType.get()));

    Metrics metrics = ParquetToIcebergStatsConvertor.toMetrics(parquetMetadata, schema, Map.of());
    assertEquals(-1, metrics.lowerBounds().get(1).getInt(0));
    assertEquals(1000, metrics.upperBounds().get(1).getInt(0));

    assertEquals(-0.1f, metrics.lowerBounds().get(2).getFloat(0), 0.0);
    assertEquals(1000.1f, metrics.upperBounds().get(2).getFloat(0), 0.0);
  }

  @Test
  public void testMetricsForParquetFileWithList() throws Exception {
    Path path = new Path(Resources.getResource("parquet/array_int_bigint.parquet").toURI());
    BatchSchema batchSchema =
        BatchSchema.newBuilder()
            .addField(INT.asList().toField("col1"))
            .addField(BIGINT.asList().toField("col2"))
            .build();

    Metrics metrics =
        ParquetToIcebergStatsConvertor.toMetrics(
            getParquetMetadata(path), schemaConverter.toIcebergSchema(batchSchema), Map.of());

    assertEquals(Long.valueOf(1), metrics.recordCount());
    assertEquals(Map.of(3, 58L, 4, 78L), metrics.columnSizes());
    assertEquals(Map.of(3, 3L, 4, 3L), metrics.valueCounts());
    assertEquals(Map.of(3, 0L, 4, 0L), metrics.nullValueCounts());
    assertEquals(Map.of(), metrics.nanValueCounts());
    assertBoundsEquals(Map.of(3, 1, 4, 7), metrics.lowerBounds());
    assertBoundsEquals(Map.of(3, 5, 4, 11), metrics.upperBounds());
  }

  @Test
  public void testMetricsForParquetFileWithStructOfList() throws Exception {
    Path path = new Path(Resources.getResource("parquet/struct_array_int_bigint.parquet").toURI());
    BatchSchema batchSchema =
        BatchSchema.newBuilder()
            .addField(struct(INT.asList().toField("f1")).toField("col1"))
            .addField(struct(BIGINT.asList().toField("f2")).toField("col2"))
            .build();

    Metrics metrics =
        ParquetToIcebergStatsConvertor.toMetrics(
            getParquetMetadata(path), schemaConverter.toIcebergSchema(batchSchema), Map.of());

    assertEquals(Long.valueOf(1), metrics.recordCount());
    assertEquals(Map.of(4, 51L, 6, 63L), metrics.columnSizes());
    assertEquals(Map.of(4, 1L, 6, 1L), metrics.valueCounts());
    assertEquals(Map.of(4, 0L, 6, 0L), metrics.nullValueCounts());
    assertEquals(Map.of(), metrics.nanValueCounts());
    assertBoundsEquals(Map.of(4, 1, 6, 3), metrics.lowerBounds());
    assertBoundsEquals(Map.of(4, 1, 6, 3), metrics.upperBounds());
  }

  @Test
  public void testMetricsForParquetFileWithStruct() throws Exception {
    Path path = new Path(Resources.getResource("parquet/struct_int_bigint.parquet").toURI());
    BatchSchema batchSchema =
        BatchSchema.newBuilder()
            .addField(struct(INT.toField("f1")).toField("col1"))
            .addField(struct(BIGINT.toField("f2")).toField("col2"))
            .build();

    Metrics metrics =
        ParquetToIcebergStatsConvertor.toMetrics(
            getParquetMetadata(path), schemaConverter.toIcebergSchema(batchSchema), Map.of());

    assertEquals(Long.valueOf(1), metrics.recordCount());
    assertEquals(Map.of(3, 44L, 4, 56L), metrics.columnSizes());
    assertEquals(Map.of(3, 1L, 4, 1L), metrics.valueCounts());
    assertEquals(Map.of(3, 0L, 4, 0L), metrics.nullValueCounts());
    assertEquals(Map.of(), metrics.nanValueCounts());
    assertBoundsEquals(Map.of(3, 1, 4, 3), metrics.lowerBounds());
    assertBoundsEquals(Map.of(3, 1, 4, 3), metrics.upperBounds());
  }

  @Test
  public void testMetricsForParquetFileWithStructOfStruct() throws Exception {
    Path path = new Path(Resources.getResource("parquet/struct_struct_int_bigint.parquet").toURI());
    BatchSchema batchSchema =
        BatchSchema.newBuilder()
            .addField(struct(struct(INT.toField("f1")).toField("f1")).toField("col1"))
            .addField(struct(struct(BIGINT.toField("f2")).toField("f2")).toField("col2"))
            .build();

    Metrics metrics =
        ParquetToIcebergStatsConvertor.toMetrics(
            getParquetMetadata(path), schemaConverter.toIcebergSchema(batchSchema), Map.of());

    assertEquals(Long.valueOf(1), metrics.recordCount());
    assertEquals(Map.of(4, 44L, 6, 56L), metrics.columnSizes());
    assertEquals(Map.of(4, 1L, 6, 1L), metrics.valueCounts());
    assertEquals(Map.of(4, 0L, 6, 0L), metrics.nullValueCounts());
    assertEquals(Map.of(), metrics.nanValueCounts());
    assertBoundsEquals(Map.of(4, 1, 6, 3), metrics.lowerBounds());
    assertBoundsEquals(Map.of(4, 1, 6, 3), metrics.upperBounds());
  }

  @Test
  public void testMetricsForParquetFileWithDeepMapsAndLists() throws Exception {
    Path path = new Path(Resources.getResource("parquet/mapofmap.parquet").toURI());
    BatchSchema batchSchema =
        BatchSchema.newBuilder()
            .addField(
                map(INT, map(INT, struct(map(INT, map(INT, INT.asList())).toField("f1"))).asList())
                    .toField("col1"))
            .build();

    Metrics metrics =
        ParquetToIcebergStatsConvertor.toMetrics(
            getParquetMetadata(path), schemaConverter.toIcebergSchema(batchSchema), Map.of());

    assertEquals(Long.valueOf(1), metrics.recordCount());
    assertEquals(Map.of(2, 50L, 5, 52L, 8, 54L, 10, 54L, 12, 54L), metrics.columnSizes());
    assertEquals(Map.of(2, 1L, 5, 1L, 8, 1L, 10, 1L, 12, 1L), metrics.valueCounts());
    assertEquals(Map.of(2, 0L, 5, 0L, 8, 0L, 10, 0L, 12, 0L), metrics.nullValueCounts());
    assertEquals(Map.of(), metrics.nanValueCounts());
    assertBoundsEquals(Map.of(2, 1, 5, 1, 8, 1, 10, 1, 12, 1), metrics.lowerBounds());
    assertBoundsEquals(Map.of(2, 1, 5, 1, 8, 1, 10, 1, 12, 1), metrics.upperBounds());
  }

  private enum IcebergTablePropertiesParam {
    // truncate(16) for all columns
    DEFAULT(Map.of()),
    ALL_FULL(Map.of("write.metadata.metrics.default", "full")),
    ALL_COUNTS(Map.of("write.metadata.metrics.default", "counts")),
    ALL_NONE(Map.of("write.metadata.metrics.default", "none")),
    ONLY_2_COLUMNS(Map.of("write.metadata.metrics.max-inferred-column-defaults", "2")),
    MIXED(
        Map.of(
            "write.metadata.metrics.default", "truncate(5)",
            "write.metadata.metrics.column.name", "counts",
            "write.metadata.metrics.column.ssn", "none"));
    public final Map<String, String> tableProperties;

    IcebergTablePropertiesParam(Map<String, String> tableProperties) {
      this.tableProperties = tableProperties;
    }
  }

  @ParameterizedTest
  @EnumSource(IcebergTablePropertiesParam.class)
  public void testIcebergTablePropertiesOnVarCharColumns(IcebergTablePropertiesParam param)
      throws Exception {
    Path path = new Path(Resources.getResource("parquet/varchars.parquet").toURI());
    BatchSchema batchSchema =
        BatchSchema.newBuilder()
            .addField(VARCHAR.toField("name"))
            .addField(VARCHAR.toField("ssn"))
            .addField(VARCHAR.toField("address"))
            .build();

    Metrics metrics =
        ParquetToIcebergStatsConvertor.toMetrics(
            getParquetMetadata(path),
            schemaConverter.toIcebergSchema(batchSchema),
            param.tableProperties);

    assertEquals(Long.valueOf(100), metrics.recordCount());
    assertEquals(Map.of(), metrics.nanValueCounts());
    switch (param) {
      case ALL_NONE:
        assertEquals(Map.of(), metrics.columnSizes());
        assertEquals(Map.of(), metrics.valueCounts());
        assertEquals(Map.of(), metrics.nullValueCounts());
        break;
      case ONLY_2_COLUMNS:
        assertEquals(Map.of(1, 1764L, 2, 1301L), metrics.columnSizes());
        assertEquals(Map.of(1, 100L, 2, 100L), metrics.valueCounts());
        assertEquals(Map.of(1, 5L, 2, 18L), metrics.nullValueCounts());
        break;
      case MIXED:
        assertEquals(Map.of(1, 1764L, 3, 3921L), metrics.columnSizes());
        assertEquals(Map.of(1, 100L, 3, 100L), metrics.valueCounts());
        assertEquals(Map.of(1, 5L, 3, 11L), metrics.nullValueCounts());
        break;
      default:
        assertEquals(Map.of(1, 1764L, 2, 1301L, 3, 3921L), metrics.columnSizes());
        assertEquals(Map.of(1, 100L, 2, 100L, 3, 100L), metrics.valueCounts());
        assertEquals(Map.of(1, 5L, 2, 18L, 3, 11L), metrics.nullValueCounts());
        break;
    }
    switch (param) {
      case DEFAULT:
        assertBoundsEquals(
            Map.of(
                1, "Aaron Thetires",
                2, "014-32-6489",
                3, "00146 Lee Juncti"),
            metrics.lowerBounds());
        assertBoundsEquals(
            Map.of(
                1, "Xavier Money",
                2, "872-17-4657",
                3, "Suite 936 44512!"),
            metrics.upperBounds());
        break;
      case ALL_FULL:
        assertBoundsEquals(
            Map.of(
                1, "Aaron Thetires",
                2, "014-32-6489",
                3, "00146 Lee Junction, Lake Gavinshire, WY 54533"),
            metrics.lowerBounds());
        assertBoundsEquals(
            Map.of(
                1, "Xavier Money",
                2, "872-17-4657",
                3, "Suite 936 44512 Geraldo Cape, Catherynborough, MS 39615"),
            metrics.upperBounds());
        break;
      case ALL_COUNTS:
      case ALL_NONE:
        assertBoundsEquals(Map.of(), metrics.lowerBounds());
        assertBoundsEquals(Map.of(), metrics.upperBounds());
        break;
      case ONLY_2_COLUMNS:
        assertBoundsEquals(
            Map.of(
                1, "Aaron Thetires",
                2, "014-32-6489"),
            metrics.lowerBounds());
        assertBoundsEquals(
            Map.of(
                1, "Xavier Money",
                2, "872-17-4657"),
            metrics.upperBounds());
        break;
      case MIXED:
        assertBoundsEquals(Map.of(3, "00146"), metrics.lowerBounds());
        assertBoundsEquals(Map.of(3, "Suitf"), metrics.upperBounds());
        break;
    }
  }

  private static void assertBoundsEquals(
      Map<Integer, ?> expected, Map<Integer, ByteBuffer> actual) {
    assertEquals(expected.size(), actual.size());
    for (Map.Entry<Integer, ?> expEntry : expected.entrySet()) {
      Object expValue = expEntry.getValue();
      ByteBuffer actValue = actual.get(expEntry.getKey());
      if (expValue != actValue) {
        assertNotNull(actValue);
        if (expValue instanceof Integer) {
          assertEquals(((Integer) expValue).intValue(), actValue.getInt(0));
        } else if (expValue instanceof String) {
          assertEquals(expValue, new String(actValue.array(), 0, actValue.limit()));
        } else {
          throw new UnsupportedOperationException("Not supported type: " + expValue.getClass());
        }
      }
    }
  }

  private static CompleteType map(CompleteType key, CompleteType value) {
    return new CompleteType(
        new ArrowType.Map(false),
        struct(key.toField("key"), value.toField("value")).toField(MapVector.DATA_VECTOR_NAME));
  }

  private ParquetMetadata getParquetMetadata(Path path) throws IOException {
    return ParquetFileReader.readFooter(new Configuration(), path);
  }

  private BlockMetaData intFloatBlockWithMinMax() {
    BlockMetaData blockMetaData = new BlockMetaData();
    IntStatistics intStatistics = new IntStatistics();
    intStatistics.setMinMax(-1, 1000);
    blockMetaData.addColumn(
        ColumnChunkMetaData.get(
            ColumnPath.get("id"), INT32, GZIP, new HashSet<>(), intStatistics, 1000, 0, 0, 0, 0));

    FloatStatistics floatStatistics = new FloatStatistics();
    floatStatistics.setMinMax(-0.1f, 1000.1f);
    blockMetaData.addColumn(
        ColumnChunkMetaData.get(
            ColumnPath.get("currency"),
            FLOAT,
            GZIP,
            new HashSet<>(),
            floatStatistics,
            1000,
            0,
            0,
            0,
            0));
    return blockMetaData;
  }

  private BlockMetaData intBlock() {
    BlockMetaData blockMetaData = new BlockMetaData();
    blockMetaData.addColumn(
        ColumnChunkMetaData.get(
            ColumnPath.get("id"), INT32, GZIP, new HashSet<>(), null, 1000, 0, 0, 0, 0));
    return blockMetaData;
  }

  private BlockMetaData intBlockWithMinMax() {
    BlockMetaData blockMetaData = new BlockMetaData();
    IntStatistics statistics = new IntStatistics();
    statistics.setMinMax(-1, 1000);
    blockMetaData.addColumn(
        ColumnChunkMetaData.get(
            ColumnPath.get("id"), INT32, GZIP, new HashSet<>(), statistics, 1000, 0, 0, 0, 0));
    return blockMetaData;
  }

  private BlockMetaData intBlockWithMinMax2() {
    BlockMetaData blockMetaData = new BlockMetaData();
    IntStatistics statistics = new IntStatistics();
    statistics.setMinMax(0, 10000);
    blockMetaData.addColumn(
        ColumnChunkMetaData.get(
            ColumnPath.get("id"), INT32, GZIP, new HashSet<>(), statistics, 1000, 0, 0, 0, 0));
    return blockMetaData;
  }

  private BlockMetaData binaryBlockWithMinMax() {
    BlockMetaData blockMetaData = new BlockMetaData();
    BinaryStatistics statistics = new BinaryStatistics();
    statistics.setMinMaxFromBytes("a".getBytes(), "h".getBytes());
    blockMetaData.addColumn(
        ColumnChunkMetaData.get(
            ColumnPath.get("data"), BINARY, GZIP, new HashSet<>(), statistics, 1000, 0, 0, 0, 0));
    return blockMetaData;
  }

  private BlockMetaData binaryBlockWithMinMax2() {
    BlockMetaData blockMetaData = new BlockMetaData();
    BinaryStatistics statistics = new BinaryStatistics();
    statistics.setMinMaxFromBytes("c".getBytes(), "z".getBytes());
    blockMetaData.addColumn(
        ColumnChunkMetaData.get(
            ColumnPath.get("data"), BINARY, GZIP, new HashSet<>(), statistics, 1000, 0, 0, 0, 0));
    return blockMetaData;
  }

  private BlockMetaData booleanBlockWithMinMax() {
    BlockMetaData blockMetaData = new BlockMetaData();
    BooleanStatistics statistics = new BooleanStatistics();
    statistics.setMinMax(false, false);
    blockMetaData.addColumn(
        ColumnChunkMetaData.get(
            ColumnPath.get("eligible"),
            BOOLEAN,
            GZIP,
            new HashSet<>(),
            statistics,
            1000,
            0,
            0,
            0,
            0));
    return blockMetaData;
  }

  private BlockMetaData booleanBlockWithMinMax2() {
    BlockMetaData blockMetaData = new BlockMetaData();
    BooleanStatistics statistics = new BooleanStatistics();
    statistics.setMinMax(true, true);
    blockMetaData.addColumn(
        ColumnChunkMetaData.get(
            ColumnPath.get("eligible"),
            BOOLEAN,
            GZIP,
            new HashSet<>(),
            statistics,
            1000,
            0,
            0,
            0,
            0));
    return blockMetaData;
  }

  private BlockMetaData floatBlock() {
    BlockMetaData blockMetaData = new BlockMetaData();
    blockMetaData.addColumn(
        ColumnChunkMetaData.get(
            ColumnPath.get("currency"), FLOAT, GZIP, new HashSet<>(), null, 1000, 0, 0, 0, 0));
    return blockMetaData;
  }

  private BlockMetaData floatBlockWithMinMax() {
    BlockMetaData blockMetaData = new BlockMetaData();
    FloatStatistics statistics = new FloatStatistics();
    statistics.setMinMax(-0.1f, 1000.1f);
    blockMetaData.addColumn(
        ColumnChunkMetaData.get(
            ColumnPath.get("currency"),
            FLOAT,
            GZIP,
            new HashSet<>(),
            statistics,
            1000,
            0,
            0,
            0,
            0));
    return blockMetaData;
  }

  private BlockMetaData floatBlockWithMinMax2() {
    BlockMetaData blockMetaData = new BlockMetaData();
    FloatStatistics statistics = new FloatStatistics();
    statistics.setMinMax(0.1f, 10000.1f);
    blockMetaData.addColumn(
        ColumnChunkMetaData.get(
            ColumnPath.get("currency"),
            FLOAT,
            GZIP,
            new HashSet<>(),
            statistics,
            1000,
            0,
            0,
            0,
            0));
    return blockMetaData;
  }

  private void assertNoMinMax(ParquetMetadata parquetMetadata, Schema schema) {
    Metrics metrics = ParquetToIcebergStatsConvertor.toMetrics(parquetMetadata, schema, Map.of());
    assertTrue(metrics.lowerBounds().isEmpty());
    assertTrue(metrics.upperBounds().isEmpty());
  }
}
