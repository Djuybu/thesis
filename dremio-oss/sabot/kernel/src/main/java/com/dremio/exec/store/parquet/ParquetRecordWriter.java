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

import static com.dremio.common.arrow.DremioArrowSchema.DREMIO_ARROW_SCHEMA_2_1;
import static com.dremio.common.map.CaseInsensitiveImmutableBiMap.newImmutableMap;
import static com.dremio.common.util.MajorTypeHelper.getMajorTypeForField;
import static com.dremio.exec.store.SystemSchemas.CLUSTERING_INDEX;
import static com.dremio.exec.store.iceberg.IcebergUtils.convertSchemaMilliToMicro;
import static com.dremio.exec.util.VectorUtil.getVectorFromSchemaPath;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static org.apache.parquet.hadoop.ParquetWriter.DEFAULT_BLOCK_SIZE;
import static org.apache.parquet.hadoop.ParquetWriter.MAX_PADDING_SIZE_DEFAULT;
import static org.apache.parquet.schema.Type.Repetition.OPTIONAL;
import static org.apache.parquet.schema.Type.Repetition.REPEATED;
import static org.apache.parquet.schema.Type.Repetition.REQUIRED;

import com.dremio.common.AutoCloseables;
import com.dremio.common.collections.Tuple;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.map.CaseInsensitiveImmutableBiMap;
import com.dremio.common.types.TypeProtos.MajorType;
import com.dremio.common.types.TypeProtos.MinorType;
import com.dremio.common.util.DremioVersionInfo;
import com.dremio.datastore.LegacyProtobufSerializer;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.catalog.SupportsFsMutablePlugin;
import com.dremio.exec.expr.fn.impl.ByteArrayWrapper;
import com.dremio.exec.hadoop.DremioHadoopUtils;
import com.dremio.exec.physical.base.CombineSmallFileOptions;
import com.dremio.exec.physical.config.copyinto.CopyIntoFileLoadInfo;
import com.dremio.exec.planner.acceleration.IncrementalUpdateUtils;
import com.dremio.exec.planner.acceleration.UpdateIdWrapper;
import com.dremio.exec.planner.physical.WriterPrel;
import com.dremio.exec.proto.ExecProtos.FragmentHandle;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.selection.SelectionVector2;
import com.dremio.exec.store.EventBasedRecordWriter;
import com.dremio.exec.store.EventBasedRecordWriter.FieldConverter;
import com.dremio.exec.store.OperationType;
import com.dremio.exec.store.ParquetOutputRecordWriter;
import com.dremio.exec.store.SVFilteredEventBasedRecordWriter;
import com.dremio.exec.store.SystemSchemas.SystemColumnStatistics;
import com.dremio.exec.store.WritePartition;
import com.dremio.exec.store.dfs.FileLoadInfo;
import com.dremio.exec.store.dfs.IcebergTableProps;
import com.dremio.exec.store.iceberg.FieldIdBroker.SeededFieldIdBroker;
import com.dremio.exec.store.iceberg.IcebergMetadataInformation;
import com.dremio.exec.store.iceberg.IcebergSerDe;
import com.dremio.exec.store.iceberg.IcebergUtils;
import com.dremio.exec.store.iceberg.SchemaConverter;
import com.dremio.exec.store.iceberg.SupportsFsCreation;
import com.dremio.exec.testing.ControlsInjector;
import com.dremio.exec.testing.ControlsInjectorFactory;
import com.dremio.exec.testing.ExecutionControls;
import com.dremio.exec.util.ColumnUtils;
import com.dremio.io.file.FileSystem;
import com.dremio.io.file.Path;
import com.dremio.options.OptionManager;
import com.dremio.parquet.reader.ParquetDirectByteBufferAllocator;
import com.dremio.sabot.exec.context.MetricDef;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.context.OperatorStats;
import com.dremio.sabot.exec.store.iceberg.proto.IcebergProtobuf;
import com.dremio.sabot.op.filter.VectorContainerWithSV;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Streams;
import com.google.protobuf.InvalidProtocolBufferException;
import io.protostuff.ByteString;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.OutOfMemoryException;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.complex.NonNullableStructVector;
import org.apache.arrow.vector.complex.UnionVectorHelper;
import org.apache.arrow.vector.complex.impl.SingleStructReaderImpl;
import org.apache.arrow.vector.complex.impl.UnionMapReader;
import org.apache.arrow.vector.complex.impl.UnionReader;
import org.apache.arrow.vector.complex.reader.FieldReader;
import org.apache.arrow.vector.holders.NullableDateMilliHolder;
import org.apache.arrow.vector.holders.NullableTimeStampMilliHolder;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.FileMetadata;
import org.apache.iceberg.Metrics;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.util.PropertyUtil;
import org.apache.parquet.column.ColumnWriteStore;
import org.apache.parquet.column.ParquetProperties;
import org.apache.parquet.column.ParquetProperties.WriterVersion;
import org.apache.parquet.column.values.factory.DefaultV1ValuesWriterFactory;
import org.apache.parquet.column.values.factory.DefaultV2ValuesWriterFactory;
import org.apache.parquet.column.values.factory.ValuesWriterFactory;
import org.apache.parquet.compression.CompressionCodecFactory;
import org.apache.parquet.hadoop.CodecFactory;
import org.apache.parquet.hadoop.ColumnChunkPageWriteStore;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.ColumnIOFactory;
import org.apache.parquet.io.MessageColumnIO;
import org.apache.parquet.io.api.RecordConsumer;
import org.apache.parquet.schema.GroupType;
import org.apache.parquet.schema.LogicalTypeAnnotation;
import org.apache.parquet.schema.LogicalTypeAnnotation.TimeUnit;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Type.Repetition;

/**
 * Record writer implementation for the Parquet file format using the Apache parquet-java library.
 *
 * <p>Note: The term "block" here is used for "row group" in the Parquet terminology
 */
public class ParquetRecordWriter extends ParquetOutputRecordWriter {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(ParquetRecordWriter.class);

  public enum Metric implements MetricDef {
    NUM_FILES_WRITTEN, // number of files written by the writer
    MIN_FILE_SIZE, // Minimum size of files written
    MAX_FILE_SIZE, // Maximum size of files written
    AVG_FILE_SIZE, // Average size of files written
    MIN_RECORD_COUNT_IN_FILE, // Minimum number of records written in a file
    MAX_RECORD_COUNT_IN_FILE, // Maximum number of records written in a file
    MIN_IO_WRITE_TIME, // Minimum IO write time
    MAX_IO_WRITE_TIME, // Maximum IO write time
    AVG_IO_WRITE_TIME, // Avg IO write time
    NUM_IO_WRITE, // Total Number of IO writes
    ;

    @Override
    public int metricId() {
      return ordinal();
    }
  }

  private static final int MINIMUM_RECORD_COUNT_FOR_CHECK = 100;
  private static final int MAXIMUM_RECORD_COUNT_FOR_CHECK = 10000;
  private static final ControlsInjector injector =
      ControlsInjectorFactory.getInjector(ParquetRecordWriter.class);

  @VisibleForTesting
  public static final String INJECTOR_AFTER_RECORDS_WRITTEN_ERROR =
      "error-after-records-are-written";

  public static final String DRILL_VERSION_PROPERTY = "drill.version";
  public static final String DREMIO_VERSION_PROPERTY = "dremio.version";
  public static final String IS_DATE_CORRECT_PROPERTY = "is.date.correct";
  public static final String WRITER_VERSION_PROPERTY = "drill-writer.version";

  private final BufferAllocator codecAllocator;
  private final BufferAllocator columnEncoderAllocator;

  private final SupportsFsMutablePlugin plugin;

  private ParquetFileWriter parquetFileWriter;
  private MessageType schema;
  private Map<String, String> extraMetaData = new HashMap<>();
  private long desiredBlockSize;
  private long desiredFileSize;
  private long fileSizeLimit;
  private int pageSize;
  private boolean enableDictionary = false;
  private final CompressionCodecName codec;
  private final WriterVersion writerVersion;
  private final CompressionCodecFactory codecFactory;
  private FileSystem fs;
  private Path path;

  private org.apache.hadoop.fs.FileSystem hadoopFs;

  private long blockRecordCount;
  private long fileRecordCount;
  private long blocksWriteTimeMs;
  private long recordCountForNextMemCheck = MINIMUM_RECORD_COUNT_FOR_CHECK;

  private ParquetProperties parquetProperties;
  private ColumnWriteStore store;
  private ColumnChunkPageWriteStore pageStore;

  private RecordConsumer consumer;
  private BatchSchema batchSchema;
  private BatchSchema icebergBatchSchema;
  private UpdateTrackingConverter trackingConverter;

  private final String location;
  private final List<String> dataset;
  private final String prefix;
  private String suffix = "";
  private final String extension;
  private int index = 0;
  private final OperatorContext context;
  private WritePartition partition;
  private final int memoryThreshold;
  private final long maxPartitions;
  private final long minRecordsForFlush;
  private List<String> partitionColumns;
  private boolean isIcebergWriter;
  private org.apache.iceberg.Schema icebergSchema;
  private CaseInsensitiveImmutableBiMap<Integer> icebergColumnIDMap;
  private CaseInsensitiveImmutableBiMap<Integer> staticIcebergColumnIDMap;
  private PartitionSpec partitionSpec;
  private final ExecutionControls executionControls;
  private final String queryUser;
  private final String queryUserId;

  // Collection of distinct valueVectors (compressed as bytes) to optionally send to manifest writer
  private final Set<ByteArrayWrapper> valueVectorCollection = new HashSet<>();

  private final int parquetFileWriteTimeThresholdMilliSecs;
  private final double parquetFileWriteIoRateThresholdMbps;
  private long fileSize;
  private Metrics icebergMetrics;
  private VectorContainerWithSV filteredContainer;
  private VarCharVector copyHistoryRecordVector;

  // metrics workspace variables
  int numFilesWritten = 0;
  long minFileSize = Long.MAX_VALUE;
  long maxFileSize = Long.MIN_VALUE;
  long avgFileSize = 0;
  long minRecordCountInFile = Long.MAX_VALUE;
  long maxRecordCountInFile = Long.MIN_VALUE;
  private OperationType operationType = OperationType.ADD_DATAFILE;
  private boolean mustCheckBlockSizeAfterEachWrite = true;
  private final Map<String, String> icebergTableProperties;

  public ParquetRecordWriter(
      OperatorContext context, ParquetWriter writer, ParquetFormatConfig config)
      throws OutOfMemoryException {
    super(context);
    this.context = context;
    this.codecAllocator =
        context.getAllocator().newChildAllocator("ParquetCodecFactory", 0, Long.MAX_VALUE);
    this.columnEncoderAllocator =
        context.getAllocator().newChildAllocator("ParquetColEncoder", 0, Long.MAX_VALUE);
    this.extraMetaData.put(DREMIO_VERSION_PROPERTY, DremioVersionInfo.getVersion());
    this.extraMetaData.put(IS_DATE_CORRECT_PROPERTY, "true");
    this.executionControls = context.getExecutionControls();

    this.plugin = writer.getPlugin();
    this.queryUser = writer.getProps().getUserName();
    this.queryUserId = writer.getProps().getUserId();

    FragmentHandle handle = context.getFragmentHandle();
    String fragmentId =
        String.format("%d_%d", handle.getMajorFragmentId(), handle.getMinorFragmentId());

    this.location = writer.getLocation();
    this.dataset = writer.getDataset();
    this.prefix = fragmentId;
    this.extension = config.outputExtension;
    if (writer.getOptions() != null) {
      this.partitionColumns = writer.getOptions().getPartitionColumns();
      this.isIcebergWriter = writer.getOptions().getTableFormatOptions().isTableFormatWriter();
    } else {
      this.partitionColumns = null;
      this.isIcebergWriter = false;
    }

    if (this.isIcebergWriter) {
      Optional<IcebergTableProps> icebergTableProps =
          Optional.ofNullable(
              writer
                  .getOptions()
                  .getTableFormatOptions()
                  .getIcebergSpecificOptions()
                  .getIcebergTableProps());
      icebergTableProperties =
          icebergTableProps.map(IcebergTableProps::getTableProperties).orElse(Map.of());

      this.partitionSpec =
          icebergTableProps.map(props -> props.getDeserializedPartitionSpec()).orElse(null);

      if (partitionSpec != null) {
        initIcebergColumnIDList(partitionSpec);
      } else if (writer.getOptions().getExtendedProperty() != null) {
        initIcebergColumnIDList(writer.getOptions().getExtendedProperty());
      }
    } else {
      icebergTableProperties = writer.getOptions().getTableProperties();
    }

    memoryThreshold =
        (int) context.getOptions().getOption(ExecConstants.PARQUET_MEMORY_THRESHOLD_VALIDATOR);
    desiredBlockSize = context.getOptions().getOption(ExecConstants.PARQUET_BLOCK_SIZE_VALIDATOR);
    desiredFileSize = desiredBlockSize;

    if (isIcebergWriter) {
      desiredBlockSize =
          PropertyUtil.propertyAsLong(
              icebergTableProperties,
              TableProperties.PARQUET_ROW_GROUP_SIZE_BYTES,
              TableProperties.PARQUET_ROW_GROUP_SIZE_BYTES_DEFAULT);
      desiredFileSize =
          PropertyUtil.propertyAsLong(
              icebergTableProperties,
              TableProperties.WRITE_TARGET_FILE_SIZE_BYTES,
              TableProperties.WRITE_TARGET_FILE_SIZE_BYTES_DEFAULT);
    }

    CombineSmallFileOptions combineSmallFileOptions =
        writer.getOptions().getCombineSmallFileOptions();
    if (combineSmallFileOptions != null && combineSmallFileOptions.getIsSmallFileWriter()) {
      Long combinedSmallFileTargetFileSize =
          writer.getOptions().getCombineSmallFileOptions().getTargetFileSize();
      desiredFileSize = combinedSmallFileTargetFileSize;
    }
    Long targetFileSize = writer.getOptions().getTableFormatOptions().getTargetFileSize();
    if (targetFileSize != null && targetFileSize.longValue() != 0L) {
      desiredFileSize = targetFileSize;
    }

    if (desiredBlockSize > desiredFileSize) {
      desiredBlockSize = desiredFileSize;
    }

    // Use a slightly smaller limit to avoid potentially creating smaller and smaller row groups at
    // the end while approximating the file size.
    fileSizeLimit = desiredFileSize - desiredBlockSize / 4;

    pageSize = (int) context.getOptions().getOption(ExecConstants.PARQUET_PAGE_SIZE_VALIDATOR);

    if (this.isIcebergWriter) {
      pageSize =
          icebergTableProperties.containsKey(TableProperties.PARQUET_PAGE_SIZE_BYTES)
              ? Integer.parseInt(
                  icebergTableProperties.get(TableProperties.PARQUET_PAGE_SIZE_BYTES))
              : TableProperties.PARQUET_PAGE_SIZE_BYTES_DEFAULT;
    }

    Tuple<CompressionCodecName, String> codecAndLevel =
        extractCodecAndLevel(context.getOptions(), icebergTableProperties);
    codec = codecAndLevel.first;
    codecFactory =
        CodecFactory.createDirectCodecFactory(
            createConfigForCodecFactory(codecAndLevel),
            new ParquetDirectByteBufferAllocator(codecAllocator),
            pageSize);

    enableDictionary =
        context
            .getOptions()
            .getOption(ExecConstants.PARQUET_WRITER_ENABLE_DICTIONARY_ENCODING_VALIDATOR);
    maxPartitions =
        context.getOptions().getOption(ExecConstants.PARQUET_MAXIMUM_PARTITIONS_VALIDATOR);
    minRecordsForFlush =
        context.getOptions().getOption(ExecConstants.PARQUET_MIN_RECORDS_FOR_FLUSH_VALIDATOR);
    parquetFileWriteTimeThresholdMilliSecs =
        (int)
            context
                .getOptions()
                .getOption(ExecConstants.PARQUET_WRITE_TIME_THRESHOLD_MILLI_SECS_VALIDATOR);
    parquetFileWriteIoRateThresholdMbps =
        context
            .getOptions()
            .getOption(ExecConstants.PARQUET_WRITE_IO_RATE_THRESHOLD_MBPS_VALIDATOR);
    writerVersion =
        parseWriterVersion(context.getOptions().getOption(ExecConstants.PARQUET_WRITER_VERSION));
  }

  private Tuple<CompressionCodecName, String> extractCodecAndLevel(
      OptionManager options, Map<String, String> icebergTableProperties) {

    CompressionCodecName codec = null;
    String level = null;
    if (this.isIcebergWriter) {
      String codecProperty =
          icebergTableProperties.getOrDefault(
              TableProperties.PARQUET_COMPRESSION,
              TableProperties.PARQUET_COMPRESSION_DEFAULT_SINCE_1_4_0);

      codec =
          parseCodecName(codecProperty)
              .orElseThrow(
                  () ->
                      new UnsupportedOperationException(
                          String.format(
                              "Unsupported compression codec %s in Iceberg table property 'write.parquet.compression-codec'",
                              codecProperty)));

      if (icebergTableProperties.containsKey(TableProperties.PARQUET_COMPRESSION_LEVEL)) {
        level = icebergTableProperties.get(TableProperties.PARQUET_COMPRESSION_LEVEL);
      }
    } else {
      String codecName = options.getOption(ExecConstants.PARQUET_WRITER_COMPRESSION_TYPE_VALIDATOR);
      codec =
          parseCodecName(codecName)
              .orElseThrow(
                  () ->
                      new UnsupportedOperationException(
                          String.format(
                              "Unsupported compression codec %s in support key 'store.parquet.compression'",
                              codecName)));
      level =
          codec == CompressionCodecName.ZSTD
              ? Long.toString(
                  options.getOption(ExecConstants.PARQUET_WRITER_COMPRESSION_ZSTD_LEVEL_VALIDATOR))
              : null;
    }
    return Tuple.of(codec, level);
  }

  private static Optional<CompressionCodecName> parseCodecName(String codecName) {
    switch (codecName.toLowerCase()) {
      case "snappy":
        return Optional.of(CompressionCodecName.SNAPPY);
      case "gzip":
        return Optional.of(CompressionCodecName.GZIP);
      case "zstd":
        return Optional.of(CompressionCodecName.ZSTD);
      case IcebergUtils.DREMIO_PARQUET_NOT_COMPRESSED:
      case IcebergUtils.ICEBERG_PARQUET_NOT_COMPRESSED:
        return Optional.of(CompressionCodecName.UNCOMPRESSED);
      default:
        return Optional.empty();
    }
  }

  private static WriterVersion parseWriterVersion(String name) {
    // name should not be null in production but it is easier to handle here for unit test mocks
    return WriterVersion.fromString(name == null ? "v1" : name.toLowerCase());
  }

  private Configuration createConfigForCodecFactory(
      Tuple<CompressionCodecName, String> codecAndLevel) {
    Configuration conf = new Configuration(false);
    String level = codecAndLevel.second;
    if (level != null) {
      switch (codecAndLevel.first) {
        case GZIP:
          conf.set("zlib.compress.level", level);
          break;
        case BROTLI:
          conf.set("compression.brotli.quality", level);
          break;
        case ZSTD:
          conf.set("parquet.compression.codec.zstd.level", level);
          break;
        default:
          logger.warn("Unsupported level property for compression codec {}", codecAndLevel.first);
      }
    }
    return conf;
  }

  @Override
  public void setup() throws IOException {
    this.fs =
        plugin.createFS(
            SupportsFsCreation.builder()
                .filePath(location)
                .userName(queryUser)
                .userId(queryUserId)
                .dataset(dataset)
                .operatorContext(context));

    this.batchSchema = batchSchema == null ? incoming.getSchema() : batchSchema;
    this.batchSchema = batchSchema.removeNullFields();

    if (this.isIcebergWriter) {
      this.icebergBatchSchema = new BatchSchema(convertSchemaMilliToMicro(batchSchema.getFields()));
      SchemaConverter schemaConverter = SchemaConverter.getBuilder().build();
      if (this.icebergColumnIDMap == null) {
        this.icebergSchema =
            partitionSpec != null
                ? partitionSpec.schema()
                : schemaConverter.toIcebergSchema(batchSchema);
        this.icebergColumnIDMap =
            newImmutableMap(IcebergUtils.getIcebergColumnNameToIDMap(icebergSchema));
      } else {
        SeededFieldIdBroker fieldIdBroker = new SeededFieldIdBroker(icebergColumnIDMap);
        if (staticIcebergColumnIDMap != null) {
          this.icebergSchema = schemaConverter.toIcebergSchema(batchSchema, fieldIdBroker);
          return;
        }
        this.icebergSchema =
            partitionSpec != null
                ? partitionSpec.schema()
                : schemaConverter.toIcebergSchema(batchSchema, fieldIdBroker);
      }
    }
    newSchema();
  }

  /** Set IcebergColumnIDMap during instance initialization */
  public ParquetRecordWriter withStaticIcebergColumnIDMap(
      CaseInsensitiveImmutableBiMap<Integer> staticIcebergColumnIDMap) {
    this.staticIcebergColumnIDMap = staticIcebergColumnIDMap;
    this.icebergColumnIDMap = staticIcebergColumnIDMap;
    return this;
  }

  /** Set batchSchema during instance initialization */
  public ParquetRecordWriter withBatchSchema(BatchSchema batchSchema) {
    this.batchSchema = batchSchema;
    return this;
  }

  /**
   * Set parquet-file size during instance initialization.
   *
   * <p>Some configurations handle sizing prior to writing.
   */
  public ParquetRecordWriter withTargetFileSize(
      long fileSize, boolean mustCheckBlockSizeAfterEachRecordWrite) {
    this.fileSizeLimit = fileSize;
    this.mustCheckBlockSizeAfterEachWrite = mustCheckBlockSizeAfterEachRecordWrite;
    return this;
  }

  /** Set operation-Type during instance initialization */
  public ParquetRecordWriter withOperationType(OperationType operationType) {
    this.operationType = operationType;
    return this;
  }

  /** Set suffix for parquet-file. Not required. */
  public ParquetRecordWriter withParquetFileNameSuffix(String suffix) {
    this.suffix = suffix;
    return this;
  }

  /** Add referencedDatafiles to the overall set. */
  public void appendValueVectorCollection(Set<ByteArrayWrapper> referencedDataFiles) {
    this.valueVectorCollection.addAll(referencedDataFiles);
  }

  /**
   * Filter out the "copy into error" column from the incoming VectorContainer and return a new
   * VectorContainer with the filtered data.
   *
   * <p>This method takes a VarCharVector containing serialized {@link FileLoadInfo} objects, a
   * SelectionVector2 (fileLoadEntrySV2) for filtering, an offset, and a length. It filters out the
   * "copy into error" column from the incoming VectorContainer (incoming) and constructs a new
   * VectorContainer (filteredContainer) without the "copy into error" column. The filteredContainer
   * is then returned.
   *
   * @param fileLoadEntryVector The VarCharVector containing serialized {@link FileLoadInfo}
   *     objects.
   * @param fileLoadEntrySV2 The SelectionVector2 used for filtering the incoming VectorContainer.
   * @param offset The offset indicating the starting position of the elements to be filtered.
   * @param length The length of the elements to be filtered.
   * @return A new VectorContainer (filteredContainer) with the "copy into error" column filtered
   *     out.
   */
  private VectorContainerWithSV filterCopyHistoryColumn(
      VarCharVector fileLoadEntryVector,
      SelectionVector2 fileLoadEntrySV2,
      int offset,
      int length) {
    // Create a new VectorContainer (filteredContainer) to hold the filtered data.
    if (filteredContainer == null) {
      // Filter out the "copy into error" column from the incoming VectorContainer.
      Iterator<ValueVector> filteredIterator =
          Streams.stream(incoming)
              .filter(
                  v ->
                      !v.getField()
                          .getName()
                          .equalsIgnoreCase(ColumnUtils.COPY_HISTORY_COLUMN_NAME))
              .map(v -> (ValueVector) v.getValueVector())
              .iterator();

      filteredContainer = new VectorContainerWithSV(context.getAllocator(), fileLoadEntrySV2);

      // Add the filtered data to the filteredContainer.
      filteredContainer.addCollection(() -> filteredIterator);

      // Build the schema for the filteredContainer.
      filteredContainer.buildSchema();
    }

    // Prepare the fileLoadEntrySV2 using the provided offset and length.
    fileLoadEntrySV2(fileLoadEntryVector, fileLoadEntrySV2, offset, length);

    // Set the record count for the filteredContainer based on the fileLoadEntrySV2 count.
    filteredContainer.setRecordCount(fileLoadEntrySV2.getCount());

    // Return the filteredContainer.
    return filteredContainer;
  }

  /**
   * Writes a batch of records to the output stream.
   *
   * <p>This method writes a batch of records to the output stream. It first checks if the incoming
   * records contain a "copy history" column. If such a column is present, it extracts the event
   * records, processes them, and writes them to the history event output. The method then filters
   * out the "copy history" column from the incoming records to create a new filtered container.
   * Finally, it delegates the actual writing of the batch to an event-based record writer, either
   * creating a new one if none exists or using the existing one.
   *
   * @param offset The offset at which to start writing records.
   * @param length The number of records to write.
   * @return The number of records written.
   * @throws IOException If an I/O error occurs during the writing process.
   */
  @Override
  public int writeBatch(int offset, int length) throws IOException {
    // Check if the incoming records contain a "copy into error" column.
    boolean hasCopyHistoryColumn =
        Streams.stream(incoming)
            .anyMatch(
                v -> v.getField().getName().equalsIgnoreCase(ColumnUtils.COPY_HISTORY_COLUMN_NAME));

    // If a "copy history" column is present, process the records and write them to the system
    // history table's output path.
    if (hasCopyHistoryColumn) {
      return copyIntoErrorWriteBatch(offset, length);
    }

    // If not "Copy History" write the batch using traditional route
    // using the superclass method.
    return super.writeBatch(offset, length);
  }

  /**
   * extracts the error records, processes them, and writes them to the error output. <br>
   * The method then filters out the "copy into error" column from the incoming records to create a
   * new filtered container.
   */
  private int copyIntoErrorWriteBatch(int offset, int length) throws IOException {
    try (SelectionVector2 copyIntoErrorSV2 =
        filteredContainer != null
            ? filteredContainer.getSelectionVector2()
            : new SelectionVector2(context.getAllocator())) {

      try {
        if (copyHistoryRecordVector == null) {
          copyHistoryRecordVector =
              (VarCharVector)
                  getVectorFromSchemaPath(incoming, ColumnUtils.COPY_HISTORY_COLUMN_NAME);
        }
        filteredContainer =
            filterCopyHistoryColumn(copyHistoryRecordVector, copyIntoErrorSV2, offset, length);
        processCopyHistoryRecords(copyHistoryRecordVector, offset, length);

        // Create or update the event-based record writer to use the filtered container for
        // writing.
        if (this.eventBasedRecordWriter == null) {
          this.eventBasedRecordWriter =
              new SVFilteredEventBasedRecordWriter(filteredContainer, this);
        } else {
          ((SVFilteredEventBasedRecordWriter) eventBasedRecordWriter).setBatch(filteredContainer);
        }

        // Write the batch using the event-based record writer.
        return eventBasedRecordWriter.write(offset, length);
      } finally {
        if (offset + length == incoming.getRecordCount()) {
          filteredContainer.close();
          filteredContainer = null;
          copyHistoryRecordVector.close();
          copyHistoryRecordVector = null;
        }
      }
    }
  }

  /**
   * Initialize the selection vector using the copy into error value vector. A record will be
   * considered selected if the value in the error vector is null.
   */
  private void fileLoadEntrySV2(
      VarCharVector copyIntoErrorRecordVector,
      SelectionVector2 copyIntoErrorSV2,
      int offset,
      int length) {
    prepareSV2(copyIntoErrorRecordVector, copyIntoErrorSV2, offset, length, Objects::isNull, null);
  }

  /**
   * Build the Selection Vector based on the provided condition. If the value satisfies the
   * condition, that value is added. Otherwise, that row is filtered out, i.e. excluded from the sv2
   * by skipping that index.
   *
   * @param vector the original vector
   * @param selectionVector the selection vector used to acquire only the values satisfying the
   *     condition
   * @param offset the starting index of the vector
   * @param length the number of indices to iterate on the vector
   * @param condition the conditional used to permit a set of values
   * @param valueCollector [optional] abstract collector which, if not null, stores the values for
   *     later purpose. Values ore stored as bytes for compaction purposes.
   */
  public void prepareSV2(
      VarCharVector vector,
      SelectionVector2 selectionVector,
      int offset,
      int length,
      Predicate<byte[]> condition,
      Consumer<ByteArrayWrapper> valueCollector) {
    if (selectionVector.getCount() < length) {
      selectionVector.allocateNew(length);
    }

    int svIndex = 0;
    for (int i = offset; i < offset + length; i++) {
      byte[] bytes = vector.get(i);
      if (condition.test(bytes)) {
        selectionVector.setIndex(svIndex, (char) (i));
        svIndex++;

        // if collector is defined, add value
        if (valueCollector != null) {
          valueCollector.accept(new ByteArrayWrapper(bytes));
        }
      }
    }
    selectionVector.setRecordCount(svIndex);
  }

  private void initIcebergColumnIDList(PartitionSpec partitionSpec) {
    if (partitionSpec != null) {
      this.icebergColumnIDMap =
          newImmutableMap(IcebergUtils.getIcebergColumnNameToIDMap(partitionSpec.schema()));
    }
  }

  /**
   * Seal the parquet file write output and prepare for new write-out path
   *
   * @throws IOException
   */
  public void flushAndPrepareForNextWritePath() throws IOException {
    flushAndClose();
    newSchema();
  }

  private void initIcebergColumnIDList(ByteString extendedProperty) {
    try {
      IcebergProtobuf.IcebergDatasetXAttr icebergDatasetXAttr =
          LegacyProtobufSerializer.parseFrom(
              IcebergProtobuf.IcebergDatasetXAttr.parser(), extendedProperty.toByteArray());
      List<IcebergProtobuf.IcebergSchemaField> icebergColumnIDs =
          icebergDatasetXAttr.getColumnIdsList();
      Map<String, Integer> icebergColumns = new HashMap<>();
      icebergColumnIDs.forEach(field -> icebergColumns.put(field.getSchemaPath(), field.getId()));
      this.icebergColumnIDMap = newImmutableMap(icebergColumns);
    } catch (InvalidProtocolBufferException e) {
      throw new RuntimeException("Could not deserialize Parquet dataset info", e);
    }
  }

  protected void setPartition(WritePartition partition) {
    this.partition = partition;
  }

  protected WritePartition getPartition() {
    return partition;
  }

  private org.apache.hadoop.fs.FileSystem getHadoopFs(Path path) throws IOException {
    if (this.hadoopFs == null) {
      org.apache.hadoop.fs.Path fsPath = new org.apache.hadoop.fs.Path(path.toString());
      try {
        this.hadoopFs =
            org.apache.hadoop.fs.FileSystem.get(fsPath.toUri(), plugin.getFsConfCopy(), queryUser);
      } catch (InterruptedException e) {
        Throwable cause = e.getCause();
        Throwables.propagateIfPossible(cause, IOException.class);
        throw new RuntimeException(cause != null ? cause : e);
      }
    }
    return this.hadoopFs;
  }

  /**
   * Helper method to create a new {@link ParquetFileWriter} as impersonated user.
   *
   * @throws IOException
   */
  private void initRecordWriter() throws IOException {
    initRecordWriter(partition.getQualifiedPath(location, buildBaseParquetFileName()));
  }

  private String buildBaseParquetFileName() {
    return prefix + "_" + index + suffix + "." + extension;
  }

  public void initRecordWriter(Path dataFilePath) throws IOException {
    this.path = fs.canonicalizePath(dataFilePath);
    parquetFileWriter =
        new ParquetFileWriter(
            OutputFile.of(fs, path),
            checkNotNull(schema),
            ParquetFileWriter.Mode.CREATE,
            DEFAULT_BLOCK_SIZE,
            MAX_PADDING_SIZE_DEFAULT);
    parquetFileWriter.start();
  }

  /**
   * Gets the written file size. It should be called after the {@link ParquetRecordWriter#close()}
   * was called.
   *
   * @return parquet file size
   */
  public long getFileSize() {
    return fileSize;
  }

  public Metrics getIcebergMetrics() {
    return icebergMetrics;
  }

  @VisibleForTesting
  long getDesiredFileSize() {
    return desiredFileSize;
  }

  private MessageType getParquetMessageTypeWithIds(BatchSchema batchSchema, String name) {
    List<Type> types = Lists.newArrayList();
    for (Field field : batchSchema) {
      if (field.getName().equalsIgnoreCase(WriterPrel.PARTITION_COMPARATOR_FIELD)) {
        continue;
      }
      if (field.getName().equalsIgnoreCase(IncrementalUpdateUtils.UPDATE_COLUMN)) {
        continue;
      }
      if (field.getName().equalsIgnoreCase(ColumnUtils.COPY_HISTORY_COLUMN_NAME)) {
        continue;
      }
      if (field.getName().equalsIgnoreCase(CLUSTERING_INDEX)) {
        continue;
      }
      Type childType = getTypeWithId(field, field.getName(), OPTIONAL);
      if (childType != null) {
        types.add(childType);
      }
    }
    Preconditions.checkState(types.size() > 0, "No types for parquet schema");
    return new MessageType(name, types);
  }

  private MessageType getParquetMessageType(BatchSchema batchSchema, String name) {
    if (isIcebergWriter) {
      return getParquetMessageTypeWithIds(batchSchema, name);
    }

    List<Type> types = Lists.newArrayList();
    for (Field field : batchSchema) {
      if (field.getName().equalsIgnoreCase(WriterPrel.PARTITION_COMPARATOR_FIELD)) {
        continue;
      }
      Type childType = getType(field, OPTIONAL);
      if (childType != null) {
        types.add(childType);
      }
    }
    Preconditions.checkState(types.size() > 0, "No types for parquet schema");
    return new MessageType(name, types);
  }

  private void newSchema() throws IOException {
    // Reset it to half of current number and bound it within the limits
    recordCountForNextMemCheck =
        min(
            max(MINIMUM_RECORD_COUNT_FOR_CHECK, recordCountForNextMemCheck / 2),
            MAXIMUM_RECORD_COUNT_FOR_CHECK);

    String json = new Schema(isIcebergWriter ? icebergBatchSchema : batchSchema).toJson();
    extraMetaData.put(DREMIO_ARROW_SCHEMA_2_1, json);
    schema = getParquetMessageType(batchSchema, "root");

    int dictionarySize =
        (int) context.getOptions().getOption(ExecConstants.PARQUET_DICT_PAGE_SIZE_VALIDATOR);

    int rowCountLimit = Integer.MAX_VALUE;

    if (isIcebergWriter) {
      if (icebergTableProperties.containsKey(
          (org.apache.iceberg.TableProperties.PARQUET_DICT_SIZE_BYTES))) {
        dictionarySize =
            Integer.parseInt(
                icebergTableProperties.get(
                    org.apache.iceberg.TableProperties.PARQUET_DICT_SIZE_BYTES));
      } else {
        dictionarySize = TableProperties.PARQUET_DICT_SIZE_BYTES_DEFAULT;
      }

      if (icebergTableProperties.containsKey(TableProperties.PARQUET_PAGE_ROW_LIMIT)) {
        rowCountLimit =
            Integer.parseInt(icebergTableProperties.get(TableProperties.PARQUET_PAGE_ROW_LIMIT));
      } else {
        rowCountLimit = TableProperties.PARQUET_PAGE_ROW_LIMIT_DEFAULT;
      }
    }

    parquetProperties =
        ParquetProperties.builder()
            .withDictionaryPageSize(dictionarySize)
            .withWriterVersion(writerVersion)
            // Creating a new ValuesWriterFactory for each ParquetRecordWriter because parquet-mr
            // would share the same static
            // instance that leads to memory leakage
            .withValuesWriterFactory(createValuesWriterFactory())
            .withDictionaryEncoding(enableDictionary)
            .withAllocator(new ParquetDirectByteBufferAllocator(columnEncoderAllocator))
            .withPageSize(pageSize)
            .withPageRowCountLimit(rowCountLimit)
            .build();
    initNewBlock();
  }

  private void initNewBlock() {
    pageStore =
        new ColumnChunkPageWriteStore(
            codecFactory.getCompressor(codec),
            schema,
            parquetProperties.getAllocator(),
            parquetProperties.getColumnIndexTruncateLength(),
            parquetProperties.getPageWriteChecksumEnabled());
    store = parquetProperties.newColumnWriteStore(schema, pageStore);
    MessageColumnIO columnIO = new ColumnIOFactory(false).getColumnIO(this.schema);
    consumer = columnIO.getRecordWriter(store);
    setUp(schema, consumer, isIcebergWriter);
  }

  private ValuesWriterFactory createValuesWriterFactory() {
    switch (writerVersion) {
      case PARQUET_1_0:
        return new DefaultV1ValuesWriterFactory();
      case PARQUET_2_0:
        return new DefaultV2ValuesWriterFactory();
      default:
        throw new IllegalArgumentException("Unknown parquet writer version: " + writerVersion);
    }
  }

  private PrimitiveType getPrimitiveType(
      Field field, boolean convertMillisToMicros, Repetition repetition) {
    MajorType majorType = getMajorTypeForField(field);
    MinorType minorType = majorType.getMinorType();
    String name = field.getName();
    PrimitiveTypeName primitiveTypeName =
        ParquetTypeHelper.getPrimitiveTypeNameForMinorType(minorType);
    if (primitiveTypeName == null) {
      return null;
    }
    LogicalTypeAnnotation logicalTypeAnnotation;
    int length = 0;
    if (convertMillisToMicros
        && (MinorType.TIME.equals(minorType)
            || MinorType.TIMESTAMPMILLI.equals(minorType)
            || MinorType.TIMETZ.equals(minorType)
            || MinorType.TIMESTAMPTZ.equals(minorType))) {
      if (minorType == MinorType.TIME) {
        logicalTypeAnnotation = LogicalTypeAnnotation.timeType(false, TimeUnit.MICROS);
      } else if (minorType == MinorType.TIMESTAMPMILLI) {
        // in case of iceberg tables the TIMESTAMPMILLI minor type is mapped to TIMESTAMPTZ
        // iceberg type (SchemaConverter#visit(Timestamp timestamp))
        // therefore we need to set isAdjustedToUTC to true
        logicalTypeAnnotation =
            LogicalTypeAnnotation.timestampType(isIcebergWriter, TimeUnit.MICROS);
      } else if (minorType == MinorType.TIMETZ) {
        logicalTypeAnnotation = LogicalTypeAnnotation.timeType(true, TimeUnit.MICROS);
      } else {
        logicalTypeAnnotation = LogicalTypeAnnotation.timestampType(true, TimeUnit.MICROS);
      }
      primitiveTypeName = PrimitiveTypeName.INT64;
    } else {
      length = ParquetTypeHelper.getLengthForMinorType(minorType);
      Integer scale;
      Integer precision;
      switch (minorType) {
        case DECIMAL:
        case DECIMAL9:
        case DECIMAL18:
        case DECIMAL28SPARSE:
        case DECIMAL28DENSE:
        case DECIMAL38SPARSE:
        case DECIMAL38DENSE:
          scale = majorType.getScale();
          precision = majorType.getPrecision();
          break;
        default:
          scale = null;
          precision = null;
          break;
      }
      logicalTypeAnnotation =
          ParquetTypeHelper.getLogicalTypeForMinorType(
              minorType, scale, precision, isIcebergWriter);
    }
    org.apache.parquet.schema.Types.PrimitiveBuilder<PrimitiveType> fieldBuilder;
    fieldBuilder =
        org.apache.parquet.schema.Types.primitive(primitiveTypeName, repetition)
            .length(length)
            .as(logicalTypeAnnotation);
    return fieldBuilder.named(name);
  }

  @Nullable
  private Type getType(Field field, Repetition repetition) {
    MinorType minorType = getMajorTypeForField(field).getMinorType();
    switch (minorType) {
      case STRUCT:
        {
          List<Type> types = Lists.newArrayList();
          for (Field childField : field.getChildren()) {
            Type childType = getType(childField, repetition);
            if (childType != null) {
              types.add(childType);
            }
          }
          if (types.size() == 0) {
            return null;
          }
          return new GroupType(OPTIONAL, field.getName(), types);
        }
      case LIST:
        {
          /**
           * We are going to build the following schema
           *
           * <pre>
           * optional group <name> (LIST) {
           *   repeated group list {
           *     <element-repetition> <element-type> element;
           *   }
           * }
           * </pre>
           *
           * see <a
           * href="https://github.com/apache/parquet-format/blob/master/LogicalTypes.md#lists">logical
           * lists</a>
           */
          Field child = field.getChildren().get(0);
          Type childType = getType(child, repetition);
          if (childType == null) {
            return null;
          }
          childType = renameChildTypeToElement(getType(child, repetition));
          GroupType groupType = new GroupType(Repetition.REPEATED, "list", childType);
          org.apache.parquet.schema.Types.GroupBuilder<GroupType> groupBuilder;
          groupBuilder =
              org.apache.parquet.schema.Types.optionalGroup()
                  .as(LogicalTypeAnnotation.listType())
                  .addFields(groupType);
          return groupBuilder.named(field.getName());
        }
      case UNION:
        {
          List<Type> types = Lists.newArrayList();
          for (Field childField : field.getChildren()) {
            Type childType = getType(childField, repetition);
            if (childType != null) {
              types.add(childType);
            }
          }
          if (types.size() == 0) {
            return null;
          }
          return new GroupType(OPTIONAL, field.getName(), types);
        }

      case MAP:
        {
          Field child = field.getChildren().get(0);
          if (child == null) {
            return null;
          }
          // Schema for map in Drmeio and Iceberg is same expect the map child's name.
          // Dremio uses "entries" and Iceberg uses "key_value"

          List<Type> types = new ArrayList<>();
          types.add(getType(child.getChildren().get(0), REQUIRED)); // key Type
          types.add(getType(child.getChildren().get(1), OPTIONAL)); // value Type
          GroupType groupType = new GroupType(Repetition.REPEATED, "key_value", types);
          org.apache.parquet.schema.Types.GroupBuilder<GroupType> groupBuilder;
          groupBuilder =
              org.apache.parquet.schema.Types.optionalGroup()
                  .as(LogicalTypeAnnotation.mapType())
                  .addFields(groupType);
          return groupBuilder.named(field.getName());
        }

      default:
        return getPrimitiveType(field, false, repetition);
    }
  }

  @Nullable
  private Type getTypeWithId(Field field, String icebergFieldName, Repetition repetition) {
    MinorType minorType = getMajorTypeForField(field).getMinorType();
    int column_id = this.icebergColumnIDMap.get(icebergFieldName);
    switch (minorType) {
      case STRUCT:
        {
          List<Type> types = Lists.newArrayList();
          for (Field childField : field.getChildren()) {
            String childName = toIcebergFieldName(icebergFieldName, childField.getName());
            Type childType = getTypeWithId(childField, childName, repetition);
            if (childType != null) {
              types.add(childType);
            }
          }
          if (types.size() == 0) {
            return null;
          }
          Type groupType = new GroupType(OPTIONAL, field.getName(), types);
          if (column_id != -1) {
            groupType = groupType.withId(column_id);
          }

          return groupType;
        }
      case LIST:
        {
          /**
           * We are going to build the following schema
           *
           * <pre>
           * optional group <name> (LIST) {
           *   repeated group list {
           *     <element-repetition> <element-type> element;
           *   }
           * }
           * </pre>
           *
           * see <a
           * href="https://github.com/apache/parquet-format/blob/master/LogicalTypes.md#lists">logical
           * lists</a>
           */
          Field child = field.getChildren().get(0);

          // Dremio schema for list:
          // LIST:
          //   child = $data$ (single child)
          //      children defining the schema of the list elements
          // Iceberg schema for list is the same except that the single child's name is 'element'
          // instead of '$data$'
          // Dremio renames the name to 'element' later by invoking renameChildTypeToElement()
          // Using 'element' as the field name since this is the name of the child node in the
          // iceberg schema
          String childName = toIcebergFieldName(icebergFieldName, "list.element");
          Type childType = getTypeWithId(child, childName, repetition);
          if (childType == null) {
            return null;
          }
          childType = renameChildTypeToElement(childType);
          GroupType groupType = new GroupType(Repetition.REPEATED, "list", childType);
          org.apache.parquet.schema.Types.GroupBuilder<GroupType> groupBuilder;
          groupBuilder =
              org.apache.parquet.schema.Types.optionalGroup()
                  .as(LogicalTypeAnnotation.listType())
                  .addFields(groupType);
          Type listType = groupBuilder.named(field.getName());
          if (column_id != -1) {
            listType = listType.withId(column_id);
          }

          return listType;
        }
      case UNION:
        {
          List<Type> types = Lists.newArrayList();
          for (Field childField : field.getChildren()) {
            String childName = toIcebergFieldName(icebergFieldName, childField.getName());
            Type childType = getTypeWithId(childField, childName, repetition);
            if (childType != null) {
              types.add(childType);
            }
          }
          if (types.size() == 0) {
            return null;
          }
          return new GroupType(OPTIONAL, field.getName(), types);
        }

      case MAP:
        {
          /**
           * Building Map with following schema
           *
           * <pre>
           * <map-repetition> group <name> (MAP) {
           *   repeated group key_value {
           *     required <key-type> key;
           *     <value-repetition> <value-type> value;
           *   }
           * }
           * </pre>
           *
           * see <a
           * href="https://github.com/apache/parquet-format/blob/master/LogicalTypes.md#maps">logical
           * Maps</a>
           */
          Field child = field.getChildren().get(0);
          if (child == null) {
            return null;
          }
          // Schema for map in Drmeio and Iceberg is same expect the map child's name.
          // Dremio uses "entries" and Iceberg uses "key_value"

          Field keyField = child.getChildren().get(0);
          Field valueField = child.getChildren().get(1);
          String keyName = toIcebergFieldName(icebergFieldName, keyField.getName());
          String valueName = toIcebergFieldName(icebergFieldName, valueField.getName());
          List<Type> types = new ArrayList<>();
          types.add(getTypeWithId(keyField, keyName, REQUIRED)); // key Type
          types.add(getTypeWithId(valueField, valueName, OPTIONAL)); // value Type
          GroupType groupType = new GroupType(REPEATED, "key_value", types);
          org.apache.parquet.schema.Types.GroupBuilder<GroupType> groupBuilder;
          groupBuilder =
              org.apache.parquet.schema.Types.optionalGroup()
                  .as(LogicalTypeAnnotation.mapType())
                  .addFields(groupType);
          Type mapType = groupBuilder.named(field.getName());
          if (column_id != -1) {
            mapType = mapType.withId(column_id);
          }

          return mapType;
        }

      default:
        PrimitiveType primitiveType = getPrimitiveType(field, true, repetition);
        if (column_id != -1) {
          primitiveType = primitiveType.withId(column_id);
        }
        return primitiveType;
    }
  }

  /** Changes the list inner '$data$' vector name to 'element' in the schema */
  private Type renameChildTypeToElement(Type childType) {
    if (childType.isPrimitive()) {
      PrimitiveType childPrimitiveType = childType.asPrimitiveType();
      org.apache.parquet.schema.Types.PrimitiveBuilder<PrimitiveType> fieldBuilder;
      fieldBuilder =
          org.apache.parquet.schema.Types.primitive(
                  childPrimitiveType.getPrimitiveTypeName(), childType.getRepetition())
              .as(childPrimitiveType.getLogicalTypeAnnotation())
              .length(childPrimitiveType.getTypeLength());
      PrimitiveType primitiveType = fieldBuilder.named("element");
      Type.ID id = childPrimitiveType.getId();
      if (id != null) {
        primitiveType = primitiveType.withId(id.hashCode());
      }
      return primitiveType;
    } else {
      GroupType childGroupType = childType.asGroupType();
      Type.ID id = childGroupType.getId();
      org.apache.parquet.schema.Types.GroupBuilder<GroupType> groupBuilder;
      groupBuilder =
          org.apache.parquet.schema.Types.buildGroup(childType.getRepetition())
              .as(childType.getLogicalTypeAnnotation());
      for (Type field : childGroupType.getFields()) {
        groupBuilder = groupBuilder.addField(field);
      }
      GroupType groupType = groupBuilder.named("element");
      if (id != null) {
        groupType = groupType.withId(id.hashCode());
      }
      return groupType;
    }
  }

  private void flushBlock() throws IOException {
    if (parquetFileWriter == null) {
      return;
    }

    if (blockRecordCount > 0) {
      try {
        long blockWriteStartMs = System.currentTimeMillis();
        parquetFileWriter.startBlock(blockRecordCount);
        consumer.flush();
        store.flush();
        pageStore.flushToFileWriter(parquetFileWriter);
        parquetFileWriter.endBlock();
        blocksWriteTimeMs += System.currentTimeMillis() - blockWriteStartMs;
        fileRecordCount += blockRecordCount;
        blockRecordCount = 0;
      } catch (IOException | RuntimeException e) {
        AutoCloseables.close(IOException.class, store, pageStore, parquetFileWriter);
        store = null;
        pageStore = null;
        parquetFileWriter = null;
        throw e;
      } finally {
        AutoCloseables.close(IOException.class, store, pageStore);
        store = null;
        pageStore = null;
      }
    }
  }

  private void flushAndClose() throws IOException {
    if (parquetFileWriter == null) {
      return;
    }

    flushBlock();

    try {
      if (fileRecordCount > 0) {

        long footerWriteStartTimeMs = System.currentTimeMillis();
        parquetFileWriter.end(extraMetaData);
        fileSize = parquetFileWriter.getPos();

        logSlowIoWrite(
            blocksWriteTimeMs,
            System.currentTimeMillis() - footerWriteStartTimeMs,
            fileSize,
            fileRecordCount,
            path);

        if (isIcebergWriter) {
          icebergMetrics =
              ParquetToIcebergStatsConvertor.toMetrics(
                  parquetFileWriter.getFooter(), icebergSchema, icebergTableProperties);
        }
        if (listener != null) {
          byte[] metadata = this.trackingConverter == null ? null : trackingConverter.getMetadata();

          /** TODO: add parquet footer */
          listener.recordsWritten(
              fileRecordCount,
              fileSize,
              path.toString(),
              metadata,
              partition.getBucketNumber(),
              getIcebergMetaData(),
              null,
              null,
              operationType.value,
              partition.getPartitionValues(),
              0L,
              IcebergSerDe.serializeToByteArray(valueVectorCollection));

          valueVectorCollection.clear();
        }

        if (executionControls != null) {
          injector.injectChecked(
              executionControls,
              INJECTOR_AFTER_RECORDS_WRITTEN_ERROR,
              UnsupportedOperationException.class);
        }
        updateStats(fileSize, fileRecordCount);

        fileRecordCount = 0;
        blocksWriteTimeMs = 0;
      }
    } finally {
      AutoCloseables.close(IOException.class, parquetFileWriter);
      parquetFileWriter = null;
    }
    index++;
  }

  /**
   * Process copy history records from a VarCharVector containing serialized {@link FileLoadInfo}
   * objects.
   *
   * <p>This method takes a VarCharVector containing serialized {@link FileLoadInfo} objects and
   * processes each record. For each record, it calls the listener's `recordsWritten` method to
   * notify the listener about the event. The `recordsWritten` method is called with information
   * about the number of records rejected, the file path, and the serialized {@link FileLoadInfo}
   * object associated with the event record.
   *
   * @param fileLoadInfoRecordVector The VarCharVector containing serialized {@link FileLoadInfo}
   *     objects.
   * @param offset The offset at which to start writing records.
   * @param length The number of records to write.
   * @throws IOException If an I/O error occurs while processing the error records or notifying the
   *     listener.
   */
  private void processCopyHistoryRecords(
      VarCharVector fileLoadInfoRecordVector, int offset, int length) {
    Map<String, Pair<Long, CopyIntoFileLoadInfo>> aggregatedEvents =
        aggregateCopyHistoryRecords(fileLoadInfoRecordVector, offset, length);
    for (Map.Entry<String, Pair<Long, CopyIntoFileLoadInfo>> entry : aggregatedEvents.entrySet()) {
      String filePath = entry.getKey();
      Pair<Long, CopyIntoFileLoadInfo> fileLoadInfoPair = entry.getValue();
      long recordsRejectedCount = fileLoadInfoPair.getLeft();
      CopyIntoFileLoadInfo fileLoadInfo = fileLoadInfoPair.getRight();

      // Convert the serialized FileLoadInfo object to a byte array.
      byte[] fileLoadInfoBytes = FileLoadInfo.Util.getJson(fileLoadInfo).getBytes();

      // Call the listener's recordsWritten method to notify about the event.
      // The listener will receive information about the number of rejected records, the file path,
      // and the serialized event info.
      listener.recordsWritten(
          0L,
          0L,
          filePath,
          fileLoadInfoBytes,
          null,
          null,
          null,
          null,
          OperationType.COPY_HISTORY_EVENT.value,
          null,
          recordsRejectedCount,
          null);
    }
  }

  /**
   * Aggregates error records from a VarCharVector containing serialized {@link FileLoadInfo}
   * objects.
   *
   * <p>This method takes a VarCharVector containing serialized {@link FileLoadInfo} objects, and
   * aggregates the event records based on the file path. It creates a map where the key is the file
   * path, and the value is a Pair consisting of the total number of records rejected for that file
   * path and the latest {@link FileLoadInfo} object associated with the file path.
   *
   * @param fileLoadInfoRecordVector The VarCharVector containing serialized {@link FileLoadInfo}
   *     objects.
   * @param offset The offset at which to start writing records.
   * @param length The number of records to write.
   * @return A Map where the key is the file path and the value is a Pair consisting of the total
   *     number of records rejected for that file path and the latest {@link FileLoadInfo} object
   *     associated with the file path.
   */
  private Map<String, Pair<Long, CopyIntoFileLoadInfo>> aggregateCopyHistoryRecords(
      VarCharVector fileLoadInfoRecordVector, int offset, int length) {
    Map<String, Pair<Long, CopyIntoFileLoadInfo>> aggregatedEvents = new HashMap<>();
    for (int i = offset; i < offset + length; i++) {
      if (i < fileLoadInfoRecordVector.getValueCount()) {
        byte[] bytes = fileLoadInfoRecordVector.get(i);
        if (bytes != null && bytes.length > 0) {
          CopyIntoFileLoadInfo fileLoadInfo =
              FileLoadInfo.Util.getInfo(new String(bytes), CopyIntoFileLoadInfo.class);
          aggregatedEvents.compute(
              fileLoadInfo.getFilePath(),
              (k, v) ->
                  v == null
                      ? Pair.of(fileLoadInfo.getRecordsRejectedCount(), fileLoadInfo)
                      : Pair.of(
                          v.getLeft() + fileLoadInfo.getRecordsRejectedCount(), v.getValue()));
        }
      }
    }
    return aggregatedEvents;
  }

  private void logSlowIoWrite(
      long writeBlockDeltaTime,
      long footerWriteAndFlushDeltaTime,
      long size,
      long recordsWritten,
      Path path) {

    long totalTime = writeBlockDeltaTime + footerWriteAndFlushDeltaTime;
    double writeIoRateMbps = Double.MAX_VALUE;
    if (totalTime > 0) {
      writeIoRateMbps = ((double) size / (1024 * 1024)) / ((double) totalTime / 1000);
    }

    if ((totalTime) > parquetFileWriteTimeThresholdMilliSecs
        && writeIoRateMbps < parquetFileWriteIoRateThresholdMbps) {
      logger.warn(
          "DHL: ParquetFileWriter took too long (writeBlockDeltaTime {} and footerWriteAndFlushDeltaTime {} milli secs) "
              + "for writing {} records ({} bytes) to file {} at {} Mbps",
          writeBlockDeltaTime,
          footerWriteAndFlushDeltaTime,
          recordsWritten,
          size,
          path,
          String.format("%.3f", writeIoRateMbps));
    }
  }

  private byte[] getIcebergMetaData() throws IOException {
    if (!this.isIcebergWriter) {
      return null;
    }

    final long fileSize = parquetFileWriter.getPos();
    String datafileLocation =
        IcebergUtils.getValidIcebergPath(
            DremioHadoopUtils.toHadoopPath(path), plugin.getFsConfCopy(), fs.getScheme());
    PartitionSpec datafilePartitionSpec =
        partitionSpec != null
            ? partitionSpec
            : IcebergUtils.getIcebergPartitionSpec(
                this.batchSchema, this.partitionColumns, this.icebergSchema);

    DataFiles.Builder dataFileBuilder;
    FileMetadata.Builder deleteFileBuilder;
    IcebergMetadataInformation icebergMetadata;

    if (operationType == OperationType.ADD_DELETEFILE) {
      deleteFileBuilder =
          FileMetadata.deleteFileBuilder(datafilePartitionSpec)
              .ofPositionDeletes()
              .withPath(datafileLocation)
              .withFileSizeInBytes(fileSize)
              .withRecordCount(blockRecordCount)
              .withFormat(FileFormat.PARQUET);

      // add partition info
      if (partitionColumns != null && partition.getIcebergPartitionData() != null) {
        deleteFileBuilder = deleteFileBuilder.withPartition(partition.getIcebergPartitionData());
      }

      // add column level metrics
      Metrics metrics =
          ParquetToIcebergStatsConvertor.toMetrics(
              parquetFileWriter.getFooter(), icebergSchema, icebergTableProperties);
      deleteFileBuilder = deleteFileBuilder.withMetrics(metrics);
      icebergMetadata =
          new IcebergMetadataInformation(
              IcebergSerDe.serializeDeleteFile(deleteFileBuilder.build()));
    } else {
      dataFileBuilder =
          DataFiles.builder(datafilePartitionSpec)
              .withPath(datafileLocation)
              .withFileSizeInBytes(fileSize)
              .withRecordCount(blockRecordCount)
              .withFormat(FileFormat.PARQUET);

      // add partition info
      if (partitionColumns != null && partition.getIcebergPartitionData() != null) {
        dataFileBuilder = dataFileBuilder.withPartition(partition.getIcebergPartitionData());
      }

      // add column level metrics
      List<SystemColumnStatistics> systemColumnsStatistics = new ArrayList<>();
      if (writeStatsListener != null) {
        systemColumnsStatistics = writeStatsListener.getSystemColumnStatistics();
      }
      Metrics metrics =
          ParquetToIcebergStatsConvertor.toMetrics(
              parquetFileWriter.getFooter(),
              icebergSchema,
              icebergTableProperties,
              systemColumnsStatistics);
      dataFileBuilder = dataFileBuilder.withMetrics(metrics);
      icebergMetadata =
          new IcebergMetadataInformation(IcebergSerDe.serializeDataFile(dataFileBuilder.build()));
    }

    return IcebergSerDe.serializeToByteArray(icebergMetadata);
  }

  private interface UpdateTrackingConverter {
    public byte[] getMetadata();
  }

  private static class UpdateIdTrackingConverter extends FieldConverter
      implements UpdateTrackingConverter {

    private UpdateIdWrapper updateIdWrapper;
    private final NullableTimeStampMilliHolder timeStampHolder = new NullableTimeStampMilliHolder();
    private final NullableDateMilliHolder dateHolder = new NullableDateMilliHolder();

    public UpdateIdTrackingConverter(
        int fieldId, String fieldName, FieldReader reader, com.dremio.common.types.MinorType type) {
      super(fieldId, fieldName, reader);
      this.updateIdWrapper = new UpdateIdWrapper(type);
    }

    @Override
    public void writeField() throws IOException {
      if (!reader.isSet()) {
        return;
      }
      switch (updateIdWrapper.getType()) {
        case FLOAT4:
          updateIdWrapper.update(reader.readFloat());
          break;
        case FLOAT8:
          updateIdWrapper.update(reader.readDouble());
          break;
        case VARCHAR:
          updateIdWrapper.update(reader.readText().toString());
          break;
        case TIMESTAMPMILLI:
          reader.read(timeStampHolder);
          updateIdWrapper.update(
              timeStampHolder.value, com.dremio.common.types.MinorType.TIMESTAMPMILLI);
          break;
        case DECIMAL:
          updateIdWrapper.update(reader.readBigDecimal());
          break;
        case INT:
          updateIdWrapper.update(reader.readInteger(), com.dremio.common.types.MinorType.INT);
          break;
        case BIGINT:
          updateIdWrapper.update(reader.readLong(), com.dremio.common.types.MinorType.BIGINT);
          break;
        case DATE:
          reader.read(dateHolder);
          int daysFromEpoch = (int) (dateHolder.value / 1000 / 60 / 60 / 24);
          updateIdWrapper.update(daysFromEpoch, com.dremio.common.types.MinorType.DATE);
          break;
        default:
      }
    }

    @Override
    public byte[] getMetadata() {
      if (updateIdWrapper.getUpdateId() != null) {
        return updateIdWrapper.serialize();
      }
      return null;
    }
  }

  @Override
  public FieldConverter getNewNullableBigIntConverter(
      int fieldId, String fieldName, FieldReader reader) { // bigint
    if (IncrementalUpdateUtils.UPDATE_COLUMN.equals(fieldName)) {
      UpdateIdTrackingConverter c =
          new UpdateIdTrackingConverter(
              fieldId, fieldName, reader, com.dremio.common.types.MinorType.BIGINT);
      Preconditions.checkArgument(
          this.trackingConverter == null, "More than one update field found.");
      this.trackingConverter = c;
      return c;
    }
    return super.getNewNullableBigIntConverter(fieldId, fieldName, reader);
  }

  @Override
  public FieldConverter getNewNullableTimeStampMilliConverter(
      int fieldId, String fieldName, FieldReader reader) { // timstamp
    if (IncrementalUpdateUtils.UPDATE_COLUMN.equals(fieldName)) {
      UpdateIdTrackingConverter c =
          new UpdateIdTrackingConverter(
              fieldId, fieldName, reader, com.dremio.common.types.MinorType.TIMESTAMPMILLI);
      Preconditions.checkArgument(
          this.trackingConverter == null, "More than one update field found.");
      this.trackingConverter = c;
      return c;
    }
    return super.getNewNullableTimeStampMilliConverter(fieldId, fieldName, reader);
  }

  @Override
  public FieldConverter getNewNullableIntConverter(
      int fieldId, String fieldName, FieldReader reader) { // int
    if (IncrementalUpdateUtils.UPDATE_COLUMN.equals(fieldName)) {
      UpdateIdTrackingConverter c =
          new UpdateIdTrackingConverter(
              fieldId, fieldName, reader, com.dremio.common.types.MinorType.INT);
      Preconditions.checkArgument(
          this.trackingConverter == null, "More than one update field found.");
      this.trackingConverter = c;
      return c;
    }
    return super.getNewNullableIntConverter(fieldId, fieldName, reader);
  }

  @Override
  public FieldConverter getNewNullableFloat4Converter(
      int fieldId, String fieldName, FieldReader reader) { // float
    if (IncrementalUpdateUtils.UPDATE_COLUMN.equals(fieldName)) {
      UpdateIdTrackingConverter c =
          new UpdateIdTrackingConverter(
              fieldId, fieldName, reader, com.dremio.common.types.MinorType.FLOAT4);
      Preconditions.checkArgument(
          this.trackingConverter == null, "More than one update field found.");
      this.trackingConverter = c;
      return c;
    }
    return super.getNewNullableFloat4Converter(fieldId, fieldName, reader);
  }

  @Override
  public FieldConverter getNewNullableFloat8Converter(
      int fieldId, String fieldName, FieldReader reader) { // double
    if (IncrementalUpdateUtils.UPDATE_COLUMN.equals(fieldName)) {
      UpdateIdTrackingConverter c =
          new UpdateIdTrackingConverter(
              fieldId, fieldName, reader, com.dremio.common.types.MinorType.FLOAT8);
      Preconditions.checkArgument(
          this.trackingConverter == null, "More than one update field found.");
      this.trackingConverter = c;
      return c;
    }
    return super.getNewNullableFloat8Converter(fieldId, fieldName, reader);
  }

  @Override
  public FieldConverter getNewNullableDecimalConverter(
      int fieldId, String fieldName, FieldReader reader) { // decimal
    if (IncrementalUpdateUtils.UPDATE_COLUMN.equals(fieldName)) {
      UpdateIdTrackingConverter c =
          new UpdateIdTrackingConverter(
              fieldId, fieldName, reader, com.dremio.common.types.MinorType.DECIMAL);
      Preconditions.checkArgument(
          this.trackingConverter == null, "More than one update field found.");
      this.trackingConverter = c;
      return c;
    }
    return super.getNewNullableDecimalConverter(fieldId, fieldName, reader);
  }

  @Override
  public FieldConverter getNewNullableVarCharConverter(
      int fieldId, String fieldName, FieldReader reader) { // varchar
    if (IncrementalUpdateUtils.UPDATE_COLUMN.equals(fieldName)) {
      UpdateIdTrackingConverter c =
          new UpdateIdTrackingConverter(
              fieldId, fieldName, reader, com.dremio.common.types.MinorType.VARCHAR);
      Preconditions.checkArgument(
          this.trackingConverter == null, "More than one update field found.");
      this.trackingConverter = c;
      return c;
    }
    return super.getNewNullableVarCharConverter(fieldId, fieldName, reader);
  }

  @Override
  public FieldConverter getNewNullableDateMilliConverter(
      int fieldId, String fieldName, FieldReader reader) { // date
    if (IncrementalUpdateUtils.UPDATE_COLUMN.equals(fieldName)) {
      UpdateIdTrackingConverter c =
          new UpdateIdTrackingConverter(
              fieldId, fieldName, reader, com.dremio.common.types.MinorType.DATE);
      Preconditions.checkArgument(
          this.trackingConverter == null, "More than one update field found.");
      this.trackingConverter = c;
      return c;
    }
    return super.getNewNullableDateMilliConverter(fieldId, fieldName, reader);
  }

  @Override
  public void startPartition(WritePartition partition) throws Exception {
    if (partitionColumns != null && partitionColumns.size() > 0 && index >= maxPartitions) {
      logger.error(
          String.format(
              "Throwing dataWriteError() from startPartition() because the index of %d is greater than or equal to the limit of %d set by store.max_partitions.",
              index, maxPartitions));
      throw UserException.dataWriteError()
          .message(
              "CTAS query cancelled because it will generate more than the limit of %d partitions. "
                  + "You can retry the query using a different column for PARTITION BY.",
              maxPartitions)
          .build(logger);
    }

    flushAndClose();
    this.partition = partition;
    newSchema();
  }

  private void checkBlockSizeReached() throws IOException {
    if (blockRecordCount >= recordCountForNextMemCheck
        && blockRecordCount
            >= minRecordsForFlush) { // checking the memory size is relatively expensive, so let's
      // not do it for every record.
      long memSize = store.getBufferedSize();
      if (context.getAllocator().getHeadroom() < memoryThreshold || memSize >= desiredBlockSize) {
        logger.debug("Reached block size {}", desiredBlockSize);
        flushBlock();
        long pos = parquetFileWriter.getPos();
        if (pos > fileSizeLimit) {
          if (writeStatsListener != null) {
            writeStatsListener.updateSystemColumnUpperBounds();
          }
          flushAndPrepareForNextWritePath();
        } else {
          desiredBlockSize = Math.min(desiredFileSize - pos, desiredBlockSize);
          initNewBlock();
        }
      } else {
        // Find the average record size for encoded records so far
        float recordSize = ((float) memSize) / blockRecordCount;

        final long recordsCouldFitInRemainingSpace =
            (long) ((desiredBlockSize - memSize) / recordSize);

        // try to check again when reached half of the number of records that could potentially fit
        // in remaining space.
        recordCountForNextMemCheck =
            blockRecordCount
                +
                // Upper bound by the max count check. There is no lower bound, as it could cause
                // files bigger than
                // blockSize if the remaining records that could fit is very few (usually when we
                // are close to the goal).
                min(MAXIMUM_RECORD_COUNT_FOR_CHECK, recordsCouldFitInRemainingSpace / 2);
      }
    }
  }

  @Override
  public FieldConverter getNewUnionConverter(int fieldId, String fieldName, FieldReader reader) {
    return new UnionParquetConverter(fieldId, fieldName, reader);
  }

  public class UnionParquetConverter extends ParquetFieldConverter {
    private UnionReader unionReader = null;
    Map<String, FieldConverter> converterMap = Maps.newHashMap();

    public UnionParquetConverter(int fieldId, String fieldName, FieldReader reader) {
      super(fieldId, fieldName, reader);
      unionReader = (UnionReader) reader;
      NonNullableStructVector internalMap =
          new UnionVectorHelper(unionReader.data).getInternalMap();
      SingleStructReaderImpl mapReader = new SingleStructReaderImpl(internalMap);
      int i = 0;
      for (String name : mapReader) {
        FieldReader fieldReader = mapReader.reader(name);
        FieldConverter converter =
            EventBasedRecordWriter.getFieldConverter(
                ParquetRecordWriter.this, i, name, fieldReader.getMinorType(), unionReader);
        if (converter != null) {
          converterMap.put(name, converter);
          i++;
        }
      }
    }

    @Override
    public void writeValue() throws IOException {
      consumer.startGroup();
      int type = unionReader.data.getTypeValue(unionReader.getPosition());
      Types.MinorType minorType = Types.MinorType.values()[type];
      EventBasedRecordWriter.FieldConverter converter =
          converterMap.get(minorType.name().toLowerCase());
      if (converter != null) {
        converter.writeField();
      }
      consumer.endGroup();
    }

    @Override
    public void writeField() throws IOException {
      if (!reader.isSet()) {
        return;
      }
      consumer.startField(fieldName, fieldId);
      writeValue();
      consumer.endField(fieldName, fieldId);
    }
  }

  @Override
  public FieldConverter getNewStructConverter(int fieldId, String fieldName, FieldReader reader) {
    StructParquetConverter converter = new StructParquetConverter(fieldId, fieldName, reader);
    if (converter.converters.size() == 0) {
      return null;
    }
    return converter;
  }

  public class StructParquetConverter extends ParquetFieldConverter {
    List<FieldConverter> converters = Lists.newArrayList();

    public StructParquetConverter(int fieldId, String fieldName, FieldReader reader) {
      super(fieldId, fieldName, reader);
      int i = 0;
      for (String name : reader) {
        FieldReader fieldReader = reader.reader(name);
        FieldConverter converter =
            EventBasedRecordWriter.getConverter(
                ParquetRecordWriter.this, i, name, fieldReader.getMinorType(), fieldReader);
        if (converter != null) {
          converters.add(converter);
          i++;
        }
      }
    }

    @Override
    public void writeValue() throws IOException {
      consumer.startGroup();
      for (FieldConverter converter : converters) {
        converter.writeField();
      }
      consumer.endGroup();
    }

    @Override
    public void writeField() throws IOException {
      if (!reader.isSet()) {
        return;
      }
      consumer.startField(fieldName, fieldId);
      writeValue();
      consumer.endField(fieldName, fieldId);
    }
  }

  @Override
  public FieldConverter getNewMapConverter(int fieldId, String fieldName, FieldReader reader) {
    MapParquetConverter converter = new MapParquetConverter(fieldId, fieldName, reader);
    if (converter.keyConverter == null || converter.valueConverter == null) {
      return null;
    }
    return converter;
  }

  public class MapParquetConverter extends ParquetFieldConverter {
    FieldConverter keyConverter;
    FieldConverter valueConverter;

    public MapParquetConverter(int fieldId, String fieldName, FieldReader reader) {
      super(fieldId, fieldName, reader);
      Preconditions.checkState(reader instanceof UnionMapReader);
      UnionMapReader unionMapReader = (UnionMapReader) reader;
      keyConverter =
          EventBasedRecordWriter.getConverter(
              ParquetRecordWriter.this,
              0,
              "key",
              unionMapReader.key().getMinorType(),
              unionMapReader.key());
      valueConverter =
          EventBasedRecordWriter.getConverter(
              ParquetRecordWriter.this,
              1,
              "value",
              unionMapReader.value().getMinorType(),
              unionMapReader.value());
    }

    @Override
    public void writeValue() throws IOException {
      consumer.startGroup();
      keyConverter.writeField();
      valueConverter.writeField();
      consumer.endGroup();
    }

    @Override
    public void writeField() throws IOException {
      if (!reader.isSet()) {
        return; // null field
      }
      consumer.startField(fieldName, fieldId);
      consumer.startGroup();
      if (reader.size() != 0) {
        consumer.startField("key_value", 0);
        while (reader.next()) {
          writeValue();
        }
        consumer.endField("key_value", 0);
      }
      consumer.endGroup();
      consumer.endField(fieldName, fieldId);
    }
  }

  @Override
  public FieldConverter getNewListConverter(int fieldId, String fieldName, FieldReader reader) {
    if (isNullField(reader.reader().getField())) {
      return null;
    }
    return new ListParquetConverter(fieldId, fieldName, reader);
  }

  private boolean isNullField(Field field) {
    ArrowType.ArrowTypeID typeId = field.getFieldType().getType().getTypeID();
    if (typeId == ArrowType.ArrowTypeID.List) {
      return isNullField(field.getChildren().get(0));
    } else {
      return typeId == ArrowType.ArrowTypeID.Null;
    }
  }

  public class ListParquetConverter extends ParquetFieldConverter {
    ParquetFieldConverter innerConverter;

    public ListParquetConverter(int fieldId, String fieldName, FieldReader reader) {
      super(fieldId, fieldName, reader);
      int i = 0;
      FieldReader fieldReader = reader.reader();
      innerConverter =
          (ParquetFieldConverter)
              EventBasedRecordWriter.getConverter(
                  ParquetRecordWriter.this,
                  i++,
                  "element",
                  fieldReader.getMinorType(),
                  fieldReader);
    }

    @Override
    public void writeValue() throws IOException {
      throw new UnsupportedOperationException("List of list not supported in ParquetWriter");
    }

    @Override
    public void writeField() throws IOException {
      if (!reader.isSet()) {
        return; // null field
      }
      consumer.startField(fieldName, fieldId);
      consumer.startGroup(); // field group

      // without this check we get the following exception when the list is empty:
      // ParquetEncodingException: empty fields are illegal, the field should be omitted completely
      // instead
      if (reader.size() != 0) {

        consumer.startField("list", 0);
        while (reader.next()) {
          consumer.startGroup(); // list group
          innerConverter.writeField(); // element
          consumer.endGroup();
        }
        consumer.endField("list", 0);
      }

      consumer.endGroup();
      consumer.endField(fieldName, fieldId);
    }
  }

  @Override
  public void startRecord() throws IOException {
    consumer.startMessage();
  }

  @Override
  public void endRecord() throws IOException {
    consumer.endMessage();

    // we wait until there is at least one record before creating the parquet file
    if (parquetFileWriter == null) {
      initRecordWriter();
    }

    blockRecordCount++;

    if (blockRecordCount == 1 && fileRecordCount == 0 && writeStatsListener != null) {
      writeStatsListener.updateSystemColumnLowerBounds();
    }

    if (mustCheckBlockSizeAfterEachWrite) {
      checkBlockSizeReached();
    }
  }

  @Override
  public void abort() throws IOException {
    if (path == null) {
      return;
    }

    fs.delete(path, true);
  }

  private void updateStats(long memSize, long recordCount) {
    minFileSize = min(minFileSize, memSize);
    maxFileSize = max(maxFileSize, memSize);
    avgFileSize = (avgFileSize * numFilesWritten + memSize) / (numFilesWritten + 1);
    minRecordCountInFile = min(minRecordCountInFile, recordCount);
    maxRecordCountInFile = max(maxRecordCountInFile, recordCount);
    numFilesWritten++;

    final OperatorStats stats = context.getStats();
    stats.setLongStat(Metric.NUM_FILES_WRITTEN, numFilesWritten);
    stats.setLongStat(Metric.MIN_FILE_SIZE, minFileSize);
    stats.setLongStat(Metric.MAX_FILE_SIZE, maxFileSize);
    stats.setLongStat(Metric.AVG_FILE_SIZE, avgFileSize);
    stats.setLongStat(Metric.MIN_RECORD_COUNT_IN_FILE, minRecordCountInFile);
    stats.setLongStat(Metric.MAX_RECORD_COUNT_IN_FILE, maxRecordCountInFile);
  }

  @Override
  public void close() throws Exception {
    try {
      flushAndClose();
      OperatorStats operatorStats = context.getStats();
      OperatorStats.IOStats ioStats = operatorStats.getWriteIOStats();

      if (ioStats != null) {
        long minIOWriteTime =
            ioStats.minIOTime.longValue() <= ioStats.maxIOTime.longValue()
                ? ioStats.minIOTime.longValue()
                : 0;
        operatorStats.setLongStat(Metric.MIN_IO_WRITE_TIME, minIOWriteTime);
        operatorStats.setLongStat(Metric.MAX_IO_WRITE_TIME, ioStats.maxIOTime.longValue());
        operatorStats.setLongStat(
            Metric.AVG_IO_WRITE_TIME,
            ioStats.numIO.get() == 0 ? 0 : ioStats.totalIOTime.longValue() / ioStats.numIO.get());
        operatorStats.addLongStat(Metric.NUM_IO_WRITE, ioStats.numIO.longValue());

        operatorStats.setProfileDetails(
            UserBitShared.OperatorProfileDetails.newBuilder()
                .addAllSlowIoInfos(ioStats.slowIOInfoList)
                .build());
      }
    } finally {
      AutoCloseables.close(
          store,
          pageStore,
          parquetFileWriter,
          codecFactory::release,
          codecAllocator,
          columnEncoderAllocator);
    }
  }

  @Override
  public FieldConverter getNewNullConverter(int fieldId, String fieldName, FieldReader reader) {
    return new NullParquetConverter(fieldId, fieldName, reader);
  }

  public class NullParquetConverter extends ParquetFieldConverter {

    public NullParquetConverter(int fieldId, String fieldName, FieldReader reader) {
      super(fieldId, fieldName, reader);
    }

    @Override
    public void writeValue() throws IOException {
      /* NO-OP */
    }

    @Override
    public void writeField() throws IOException {
      writeValue();
    }
  }

  @Override
  public FileSystem getFs() {
    return fs;
  }

  @Override
  public Path getLocation() {
    return Path.of(location);
  }

  private String toIcebergFieldName(String parentField, String childField) {
    return parentField + "." + childField;
  }
}
