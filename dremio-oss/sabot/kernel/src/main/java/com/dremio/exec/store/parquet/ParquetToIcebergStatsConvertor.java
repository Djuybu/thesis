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

import static org.apache.iceberg.types.Conversions.toByteBuffer;

import com.dremio.exec.store.SystemSchemas.SystemColumnStatistics;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.DeleteFiles;
import org.apache.iceberg.ExpireSnapshots;
import org.apache.iceberg.HistoryEntry;
import org.apache.iceberg.ManageSnapshots;
import org.apache.iceberg.Metrics;
import org.apache.iceberg.MetricsConfig;
import org.apache.iceberg.MetricsModes;
import org.apache.iceberg.MetricsUtil;
import org.apache.iceberg.OverwriteFiles;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.ReplacePartitions;
import org.apache.iceberg.ReplaceSortOrder;
import org.apache.iceberg.RewriteFiles;
import org.apache.iceberg.RewriteManifests;
import org.apache.iceberg.RowDelta;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotRef;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.StatisticsFile;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.UpdateLocation;
import org.apache.iceberg.UpdatePartitionSpec;
import org.apache.iceberg.UpdateProperties;
import org.apache.iceberg.UpdateSchema;
import org.apache.iceberg.encryption.EncryptionManager;
import org.apache.iceberg.expressions.Literal;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.LocationProvider;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.BinaryUtil;
import org.apache.iceberg.util.UnicodeUtil;
import org.apache.parquet.column.statistics.Statistics;
import org.apache.parquet.hadoop.metadata.BlockMetaData;
import org.apache.parquet.hadoop.metadata.ColumnChunkMetaData;
import org.apache.parquet.hadoop.metadata.ColumnPath;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.schema.PrimitiveType;

/** Class to support stats conversions from Parquet types to Iceberg types. */
public final class ParquetToIcebergStatsConvertor {

  /**
   * Dummy implementation for {@link org.apache.iceberg.Table} only to support creating {@link
   * org.apache.iceberg.MetricsConfig} with schema and table properties.
   */
  private static final class DummyTableForMetricsConfig implements Table {
    private final Schema schema;
    private final Map<String, String> tableProperties;

    private DummyTableForMetricsConfig(Schema schema, Map<String, String> tableProperties) {
      this.schema = schema;
      this.tableProperties = tableProperties;
    }

    @Override
    public Map<String, String> properties() {
      return tableProperties;
    }

    @Override
    public Schema schema() {
      return schema;
    }

    @Override
    public SortOrder sortOrder() {
      return null;
    }

    private RuntimeException notSupported() {
      return new UnsupportedOperationException(
          "Dummy Table implementation; this method is not supported");
    }

    @Override
    public String location() {
      throw notSupported();
    }

    @Override
    public Snapshot currentSnapshot() {
      throw notSupported();
    }

    @Override
    public Snapshot snapshot(long snapshotId) {
      throw notSupported();
    }

    @Override
    public Iterable<Snapshot> snapshots() {
      throw notSupported();
    }

    @Override
    public List<HistoryEntry> history() {
      throw notSupported();
    }

    @Override
    public UpdateSchema updateSchema() {
      throw notSupported();
    }

    @Override
    public UpdatePartitionSpec updateSpec() {
      throw notSupported();
    }

    @Override
    public UpdateProperties updateProperties() {
      throw notSupported();
    }

    @Override
    public ReplaceSortOrder replaceSortOrder() {
      throw notSupported();
    }

    @Override
    public UpdateLocation updateLocation() {
      throw notSupported();
    }

    @Override
    public AppendFiles newAppend() {
      throw notSupported();
    }

    @Override
    public RewriteFiles newRewrite() {
      throw notSupported();
    }

    @Override
    public RewriteManifests rewriteManifests() {
      throw notSupported();
    }

    @Override
    public OverwriteFiles newOverwrite() {
      throw notSupported();
    }

    @Override
    public RowDelta newRowDelta() {
      throw notSupported();
    }

    @Override
    public ReplacePartitions newReplacePartitions() {
      throw notSupported();
    }

    @Override
    public DeleteFiles newDelete() {
      throw notSupported();
    }

    @Override
    public ExpireSnapshots expireSnapshots() {
      throw notSupported();
    }

    @Override
    public ManageSnapshots manageSnapshots() {
      throw notSupported();
    }

    @Override
    public Transaction newTransaction() {
      throw notSupported();
    }

    @Override
    public FileIO io() {
      throw notSupported();
    }

    @Override
    public EncryptionManager encryption() {
      throw notSupported();
    }

    @Override
    public LocationProvider locationProvider() {
      throw notSupported();
    }

    @Override
    public List<StatisticsFile> statisticsFiles() {
      throw notSupported();
    }

    @Override
    public Map<String, SnapshotRef> refs() {
      throw notSupported();
    }

    @Override
    public void refresh() {
      throw notSupported();
    }

    @Override
    public TableScan newScan() {
      throw notSupported();
    }

    @Override
    public Map<Integer, Schema> schemas() {
      throw notSupported();
    }

    @Override
    public PartitionSpec spec() {
      throw notSupported();
    }

    @Override
    public Map<Integer, PartitionSpec> specs() {
      throw notSupported();
    }

    @Override
    public Map<Integer, SortOrder> sortOrders() {
      throw notSupported();
    }
  }

  public static Metrics toMetrics(
      ParquetMetadata metadata, Schema fileSchema, Map<String, String> tableProperties) {
    return toMetrics(metadata, fileSchema, tableProperties, null);
  }

  public static Metrics toMetrics(
      ParquetMetadata metadata,
      Schema fileSchema,
      Map<String, String> tableProperties,
      List<SystemColumnStatistics> systemColumns) {
    MetricsConfig metricsConfig =
        MetricsConfig.forTable(new DummyTableForMetricsConfig(fileSchema, tableProperties));

    long rowCount = 0;
    Map<Integer, Long> columnSizes = new HashMap<>();
    Map<Integer, Long> valueCounts = new HashMap<>();
    Map<Integer, Long> nullValueCounts = new HashMap<>();
    Map<Integer, Literal<?>> lowerBounds = new HashMap<>();
    Map<Integer, Literal<?>> upperBounds = new HashMap<>();
    Set<Integer> missingStats = new HashSet<>();

    List<BlockMetaData> blocks = metadata.getBlocks();
    for (BlockMetaData block : blocks) {
      rowCount += block.getRowCount();
      for (ColumnChunkMetaData column : block.getColumns()) {
        ColumnPath columnPath = column.getPath();

        Types.NestedField field = fileSchema.findField(toIcebergSchemaPath(fileSchema, columnPath));
        int fieldId = field.fieldId();

        MetricsModes.MetricsMode metricsMode =
            MetricsUtil.metricsMode(fileSchema, metricsConfig, fieldId);

        if (metricsMode == MetricsModes.None.get()) {
          continue;
        }

        columnSizes.merge(fieldId, column.getTotalSize(), Long::sum);
        valueCounts.merge(fieldId, column.getValueCount(), Long::sum);

        Statistics<?> stats = column.getStatistics();
        if (stats == null) {
          missingStats.add(fieldId);
        } else if (!stats.isEmpty()) {
          nullValueCounts.merge(fieldId, stats.getNumNulls(), Long::sum);
          // If metrics mode is "truncate(n)" or "full"
          if (metricsMode != MetricsModes.Counts.get() && stats.hasNonNullValue()) {
            updateMin(
                lowerBounds,
                fieldId,
                toLiteral(field.type(), column.getPrimitiveType(), stats.genericGetMin()),
                field.type(),
                metricsMode);
            updateMax(
                upperBounds,
                fieldId,
                toLiteral(field.type(), column.getPrimitiveType(), stats.genericGetMax()),
                field.type(),
                metricsMode);
          }
        }
      }
    }

    // discard accumulated values if any stats were missing
    for (Integer fieldId : missingStats) {
      nullValueCounts.remove(fieldId);
      lowerBounds.remove(fieldId);
      upperBounds.remove(fieldId);
    }

    Map<Integer, ByteBuffer> lowerBoundsBufferMap = toBufferMap(fileSchema, lowerBounds);
    Map<Integer, ByteBuffer> upperBoundsBufferMap = toBufferMap(fileSchema, upperBounds);

    // add system column min/max
    if (systemColumns != null && !systemColumns.isEmpty()) {
      for (SystemColumnStatistics columnStatistics : systemColumns) {
        lowerBoundsBufferMap.put(
            columnStatistics.getColumnId(),
            toByteBuffer(
                columnStatistics.getIcebergType().typeId(),
                columnStatistics.getIcebergLiteralLowerBound().value()));
        upperBoundsBufferMap.put(
            columnStatistics.getColumnId(),
            toByteBuffer(
                columnStatistics.getIcebergType().typeId(),
                columnStatistics.getIcebergLiteralUpperBound().value()));
      }
    }

    // The column's Statistics does not have nan values api. Put empty map for 'nanValueCounts'.
    return new Metrics(
        rowCount,
        columnSizes,
        valueCounts,
        nullValueCounts,
        new HashMap<>(),
        lowerBoundsBufferMap,
        upperBoundsBufferMap);
  }

  // Converts the column path in the Parquet schema to a path in the Iceberg schema in the
  // "dot-string" format.
  private static String toIcebergSchemaPath(Schema schema, ColumnPath path) {
    StringBuilder icebergPath = new StringBuilder();
    Type type = schema.asStruct();
    Iterator<String> pathIt = path.iterator();
    while (pathIt.hasNext()) {
      String elementName = pathIt.next();
      if (type.isStructType()) {
        icebergPath.append(elementName).append('.');
        type = type.asStructType().fieldType(elementName);
      } else if (type.isListType()) {
        // We assume we use the proper 3-level list in Parquet; Meanwhile the Iceberg schema only
        // has a simple list type and an element type under it. So skip a path element.
        pathIt.next();
        // Iceberg always use the name "element" for list elements
        icebergPath.append("element").append('.');
        type = type.asListType().elementType();
      } else if (type.isMapType()) {
        // Parquet map is always 3-level while Iceberg schema has a simple map type with the key and
        // value children in it. So skip a path element.
        elementName = pathIt.next();
        icebergPath.append(elementName).append('.');
        type = type.asMapType().fieldType(elementName);
      } else {
        throw new IllegalArgumentException(
            String.format("Column path %s cannot be found in the Iceberg schema %s", path, schema));
      }
    }
    // Remove the trailing '.'
    icebergPath.setLength(icebergPath.length() - 1);
    return icebergPath.toString();
  }

  private static Literal<Object> toLiteral(
      Type type, PrimitiveType primitiveType, Comparable<?> comparable) {
    return ParquetToIcebergLiteralConvertor.fromParquetPrimitive(type, primitiveType, comparable);
  }

  @SuppressWarnings("unchecked")
  private static <T> void updateMin(
      Map<Integer, Literal<?>> lowerBounds,
      int id,
      Literal<T> min,
      Type type,
      MetricsModes.MetricsMode metricsMode) {
    Literal<T> currentMin = (Literal<T>) lowerBounds.get(id);
    if (currentMin == null || min.comparator().compare(min.value(), currentMin.value()) < 0) {
      if (metricsMode instanceof MetricsModes.Truncate) {
        int length = ((MetricsModes.Truncate) metricsMode).length();
        switch (type.typeId()) {
          case STRING:
            lowerBounds.put(id, UnicodeUtil.truncateStringMin((Literal<CharSequence>) min, length));
            break;
          case FIXED:
          case BINARY:
            Literal<ByteBuffer> truncatedMinBinary =
                BinaryUtil.truncateBinaryMin((Literal<ByteBuffer>) min, length);
            if (truncatedMinBinary != null) {
              lowerBounds.put(id, truncatedMinBinary);
            }
            break;
          default:
            lowerBounds.put(id, min);
        }
      } else {
        lowerBounds.put(id, min);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> void updateMax(
      Map<Integer, Literal<?>> upperBounds,
      int id,
      Literal<T> max,
      Type type,
      MetricsModes.MetricsMode metricsMode) {
    Literal<T> currentMax = (Literal<T>) upperBounds.get(id);
    if (currentMax == null || max.comparator().compare(max.value(), currentMax.value()) > 0) {
      if (metricsMode instanceof MetricsModes.Truncate) {
        int length = ((MetricsModes.Truncate) metricsMode).length();
        switch (type.typeId()) {
          case STRING:
            upperBounds.put(id, UnicodeUtil.truncateStringMax((Literal<CharSequence>) max, length));
            break;
          case FIXED:
          case BINARY:
            Literal<ByteBuffer> truncatedMaxBinary =
                BinaryUtil.truncateBinaryMin((Literal<ByteBuffer>) max, length);
            if (truncatedMaxBinary != null) {
              upperBounds.put(id, truncatedMaxBinary);
            }
            break;
          default:
            upperBounds.put(id, max);
        }
      } else {
        upperBounds.put(id, max);
      }
    }
  }

  private static Map<Integer, ByteBuffer> toBufferMap(Schema schema, Map<Integer, Literal<?>> map) {
    Map<Integer, ByteBuffer> bufferMap = new HashMap<>();
    for (Map.Entry<Integer, Literal<?>> entry : map.entrySet()) {
      bufferMap.put(
          entry.getKey(),
          toByteBuffer(schema.findType(entry.getKey()).typeId(), entry.getValue().value()));
    }
    return bufferMap;
  }

  private ParquetToIcebergStatsConvertor() {}
}
