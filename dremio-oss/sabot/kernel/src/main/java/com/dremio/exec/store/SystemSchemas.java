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
package com.dremio.exec.store;

import static com.dremio.exec.util.ColumnUtils.FILE_PATH_COLUMN_NAME;
import static com.dremio.exec.util.ColumnUtils.ROW_INDEX_COLUMN_NAME;

import com.dremio.common.map.CaseInsensitiveMap;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.iceberg.IcebergUtils;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.ArrowType.PrimitiveType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.iceberg.MetadataColumns;
import org.apache.iceberg.expressions.Literal;
import org.apache.iceberg.types.Type;

public final class SystemSchemas {
  public static class SystemColumn {
    public String name;
    public Integer id;
    public ArrowType.PrimitiveType arrowType;

    public SystemColumn(String name, PrimitiveType arrowType) {
      this(name, null, arrowType);
    }

    public SystemColumn(String name, Integer id, PrimitiveType arrowType) {
      this.name = name;
      this.id = id;
      this.arrowType = arrowType;
    }

    public String getName() {
      return name;
    }

    public Integer getId() {
      return id;
    }

    public PrimitiveType getArrowType() {
      return arrowType;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      SystemColumn that = (SystemColumn) o;
      return Objects.equals(name, that.name)
          && Objects.equals(id, that.id)
          && Objects.equals(arrowType, that.arrowType);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, id, arrowType);
    }

    public Type toIcebergType() {
      return IcebergUtils.toIcebergType(arrowType);
    }

    public <I> Literal<I> toIcebergLiteral(Object value) {
      return IcebergUtils.toIcebergLiteral(value, arrowType);
    }
  }

  public static class SystemColumnStatistics<T> {
    private final SystemColumn systemColumn;
    private T lowerBound;
    private T upperBound;

    public SystemColumnStatistics(SystemColumn systemColumn) {
      this.systemColumn = systemColumn;
    }

    public SystemColumn getSystemColumn() {
      return systemColumn;
    }

    public T getLowerBound() {
      return lowerBound;
    }

    public void setLowerBound(T lowerBound) {
      this.lowerBound = lowerBound;
    }

    public T getUpperBound() {
      return upperBound;
    }

    public void setUpperBound(T upperBound) {
      this.upperBound = upperBound;
    }

    public int getColumnId() {
      return systemColumn.getId();
    }

    public <I> Literal<I> getIcebergLiteralLowerBound() {
      return IcebergUtils.toIcebergLiteral(lowerBound, systemColumn.getArrowType());
    }

    public <I> Literal<I> getIcebergLiteralUpperBound() {
      return IcebergUtils.toIcebergLiteral(upperBound, systemColumn.getArrowType());
    }

    public Type getIcebergType() {
      return systemColumn.toIcebergType();
    }
  }

  public static final String SPLIT_IDENTITY = RecordReader.SPLIT_IDENTITY;
  public static final String SPLIT_INFORMATION = RecordReader.SPLIT_INFORMATION;
  public static final String COL_IDS = RecordReader.COL_IDS;
  public static final String RECORDS = RecordWriter.RECORDS_COLUMN;

  public static final BatchSchema SPLIT_GEN_AND_COL_IDS_SCAN_SCHEMA =
      RecordReader.SPLIT_GEN_AND_COL_IDS_SCAN_SCHEMA;

  public static final String SEGMENT_COMPARATOR_FIELD =
      "D_R_E_M_I_O_S_E_G_M_E_N_T_C_O_M_P_A_R_A_T_O_R";

  public static final String DATAFILE_PATH = "datafilePath";
  public static final String PATH = "path";
  public static final String FILE_GROUP_INDEX = "D_R_E_M_I_O_F_I_L_E_G_R_O_U_P_I_N_D_E_X";
  public static final String CLUSTERING_INDEX = "D_R_E_M_I_O_C_L_U_S_T_E_R_I_N_G_I_N_D_E_X";
  public static final String DATA_FILE_CLUSTERING_INDEX_MIN =
      "D_R_E_M_I_O_D_A_T_A_F_I_L_E_C_L_U_S_T_E_R_I_N_G_I_N_D_E_X_M_I_N";
  public static final String DATA_FILE_CLUSTERING_INDEX_MAX =
      "D_R_E_M_I_O_D_A_T_A_F_I_L_E_C_L_U_S_T_E_R_I_N_G_I_N_D_E_X_M_A_X";
  public static final String DATASET_FIELD = "D_R_E_M_I_O_D_A_T_A_S_E_T";
  public static final String DELETE_FILE = "deleteFile";
  public static final String DELETE_FILES = "deleteFiles";
  public static final String FILE_SIZE = "fileSize";
  public static final String FILE_CONTENT = "fileContent";
  public static final String RECORD_COUNT = "recordCount";
  public static final String SEQUENCE_NUMBER = "sequenceNumber";
  public static final String IMPLICIT_SEQUENCE_NUMBER = "$_dremio_$_sequence_no_$";
  public static final String PARTITION_SPEC_ID = "partitionSpecId";
  public static final String PARTITION_KEY = "partitionKey";
  public static final String PARTITION_INFO = "partitionInfo";
  public static final String EQUALITY_IDS = "equalityIds";
  public static final String ICEBERG_METADATA = "icebergMetadata";
  public static final String INCREMENTAL_REFRESH_JOIN_KEY = "incrementalRefreshJoinKey";
  public static final String DELETE_FILE_PATH = MetadataColumns.DELETE_FILE_PATH.name();
  public static final String POS = MetadataColumns.DELETE_FILE_POS.name();
  public static final String METADATA_FILE_PATH = "metadataFilePath";
  public static final String TABLE_LOCATION = "tableLocation";
  public static final String TABLE_NAME = "tableName";
  public static final String TABLE_NAMESPACE = "tableNamespace";
  public static final String MANIFEST_LIST_PATH = "manifestListPath";
  public static final String SNAPSHOT_ID = "snapshotId";
  public static final String FILE_PATH = "filePath";
  public static final String FILE_TYPE = "fileType";

  public static final String TIMESTAMP = "timestamp";
  public static final String VERSION = "version";
  public static final String OPERATION = "operation";
  public static final Field ICEBERG_METADATA_FIELD =
      Field.nullable(ICEBERG_METADATA, Types.MinorType.VARBINARY.getType());
  public static final Field RECORD_COUNT_FIELD =
      Field.nullable(RECORD_COUNT, Types.MinorType.BIGINT.getType());
  public static final List<String> CARRY_FORWARD_FILE_PATH_TYPE_COLS =
      Lists.newArrayList(FILE_PATH, FILE_TYPE);

  public static final Map<String, SystemColumn> SYSTEM_COLUMNS =
      CaseInsensitiveMap.newImmutableMap(
          ImmutableMap.of(
              FILE_PATH_COLUMN_NAME,
              new SystemColumn(FILE_PATH_COLUMN_NAME, ArrowType.PrimitiveType.Utf8.INSTANCE),
              ROW_INDEX_COLUMN_NAME,
              new SystemColumn(ROW_INDEX_COLUMN_NAME, new ArrowType.PrimitiveType.Int(64, true)),
              FILE_GROUP_INDEX,
              new SystemColumn(FILE_GROUP_INDEX, new ArrowType.PrimitiveType.Int(64, true)),
              CLUSTERING_INDEX,
              new SystemColumn(CLUSTERING_INDEX, -1, new ArrowType.PrimitiveType.Binary())));

  public static final List<SystemColumn> FILE_PATH_AND_ROW_INDEX_COLUMNS =
      ImmutableList.of(
          SYSTEM_COLUMNS.get(FILE_PATH_COLUMN_NAME), SYSTEM_COLUMNS.get(ROW_INDEX_COLUMN_NAME));

  public static final List<SystemColumn> FILE_GROUP_INDEX_COLUMN =
      ImmutableList.of(SYSTEM_COLUMNS.get(FILE_GROUP_INDEX));
  public static final BatchSchema ALL_FIELDS_BATCH_SCHEMA =
      BatchSchema.newBuilder()
          .addField(Field.nullable(DATAFILE_PATH, Types.MinorType.VARCHAR.getType()))
          .addField(Field.nullable(FILE_SIZE, Types.MinorType.BIGINT.getType()))
          .addField(Field.nullable(SEQUENCE_NUMBER, Types.MinorType.BIGINT.getType()))
          .addField(Field.nullable(PARTITION_SPEC_ID, Types.MinorType.INT.getType()))
          .addField(Field.nullable(PARTITION_KEY, Types.MinorType.VARBINARY.getType()))
          .addField(Field.nullable(PARTITION_INFO, Types.MinorType.VARBINARY.getType()))
          .addField(Field.nullable(COL_IDS, Types.MinorType.VARBINARY.getType()))
          .addField(Field.nullable(FILE_CONTENT, Types.MinorType.VARCHAR.getType()))
          .addField(Field.nullable(DELETE_FILE_PATH, Types.MinorType.VARCHAR.getType()))
          .addField(Field.nullable(IMPLICIT_SEQUENCE_NUMBER, Types.MinorType.BIGINT.getType()))
          .addField(Field.nullable(POS, Types.MinorType.BIGINT.getType()))
          .addField(buildDeleteFileStruct(DELETE_FILE))
          .addField(
              new Field(
                  DELETE_FILES,
                  FieldType.nullable(Types.MinorType.LIST.getType()),
                  ImmutableList.of(buildDeleteFileStruct(ListVector.DATA_VECTOR_NAME))))
          .addField(ICEBERG_METADATA_FIELD)
          .setSelectionVectorMode(BatchSchema.SelectionVectorMode.NONE)
          .build();

  public static final BatchSchema ICEBERG_MANIFEST_SCAN_SCHEMA =
      BatchSchema.newBuilder()
          .addField(Field.nullable(DATAFILE_PATH, Types.MinorType.VARCHAR.getType()))
          .addField(Field.nullable(FILE_SIZE, Types.MinorType.BIGINT.getType()))
          .addField(Field.nullable(SEQUENCE_NUMBER, Types.MinorType.BIGINT.getType()))
          .addField(Field.nullable(PARTITION_SPEC_ID, Types.MinorType.INT.getType()))
          .addField(Field.nullable(PARTITION_KEY, Types.MinorType.VARBINARY.getType()))
          .addField(Field.nullable(PARTITION_INFO, Types.MinorType.VARBINARY.getType()))
          .addField(Field.nullable(COL_IDS, Types.MinorType.VARBINARY.getType()))
          .addField(Field.nullable(FILE_CONTENT, Types.MinorType.VARCHAR.getType()))
          .setSelectionVectorMode(BatchSchema.SelectionVectorMode.NONE)
          .build();

  /*
   * Delete file path, File size, Partition Info and Column IDs are being projected for delete manifest scans
   * to follow SPLIT_GEN schema and allow delete files to be read using DATA_FILE_SCAN table function.
   */
  public static final BatchSchema ICEBERG_DELETE_MANIFEST_SCAN_SCHEMA =
      BatchSchema.newBuilder()
          .addField(buildDeleteFileStruct(DELETE_FILE))
          .addField(Field.nullable(DATAFILE_PATH, Types.MinorType.VARCHAR.getType()))
          .addField(Field.nullable(FILE_SIZE, Types.MinorType.BIGINT.getType()))
          .addField(Field.nullable(SEQUENCE_NUMBER, Types.MinorType.BIGINT.getType()))
          .addField(Field.nullable(PARTITION_SPEC_ID, Types.MinorType.INT.getType()))
          .addField(Field.nullable(PARTITION_KEY, Types.MinorType.VARBINARY.getType()))
          .addField(Field.nullable(PARTITION_INFO, Types.MinorType.VARBINARY.getType()))
          .addField(Field.nullable(COL_IDS, Types.MinorType.VARBINARY.getType()))
          .addField(Field.nullable(FILE_CONTENT, Types.MinorType.VARCHAR.getType()))
          .setSelectionVectorMode(BatchSchema.SelectionVectorMode.NONE)
          .build();

  public static final BatchSchema ICEBERG_POS_DELETE_FILE_SCHEMA =
      BatchSchema.newBuilder()
          .addField(Field.nullable(DELETE_FILE_PATH, Types.MinorType.VARCHAR.getType()))
          .addField(Field.nullable(IMPLICIT_SEQUENCE_NUMBER, Types.MinorType.BIGINT.getType()))
          .addField(Field.nullable(POS, Types.MinorType.BIGINT.getType()))
          .setSelectionVectorMode(BatchSchema.SelectionVectorMode.NONE)
          .build();

  public static final BatchSchema ICEBERG_DELETE_FILE_AGG_SCHEMA =
      BatchSchema.newBuilder()
          .addField(Field.nullable(DATAFILE_PATH, Types.MinorType.VARCHAR.getType()))
          .addField(Field.nullable(FILE_SIZE, Types.MinorType.BIGINT.getType()))
          .addField(Field.nullable(PARTITION_INFO, Types.MinorType.VARBINARY.getType()))
          .addField(Field.nullable(COL_IDS, Types.MinorType.VARBINARY.getType()))
          .addField(
              new Field(
                  DELETE_FILES,
                  FieldType.nullable(Types.MinorType.LIST.getType()),
                  ImmutableList.of(buildDeleteFileStruct(ListVector.DATA_VECTOR_NAME))))
          .addField(Field.nullable(PARTITION_SPEC_ID, Types.MinorType.INT.getType()))
          .setSelectionVectorMode(BatchSchema.SelectionVectorMode.NONE)
          .build();

  public static final BatchSchema ICEBERG_SNAPSHOTS_SCAN_SCHEMA =
      BatchSchema.newBuilder()
          .addField(Field.nullable(METADATA_FILE_PATH, Types.MinorType.VARCHAR.getType()))
          .addField(Field.nullable(SNAPSHOT_ID, Types.MinorType.BIGINT.getType()))
          .addField(Field.nullable(MANIFEST_LIST_PATH, Types.MinorType.VARCHAR.getType()))
          .setSelectionVectorMode(BatchSchema.SelectionVectorMode.NONE)
          .build();

  public static final BatchSchema METADATA_PATH_SCAN_SCHEMA =
      BatchSchema.newBuilder()
          .addField(Field.nullable(DATASET_FIELD, Types.MinorType.VARCHAR.getType()))
          .addField(Field.nullable(METADATA_FILE_PATH, Types.MinorType.VARCHAR.getType()))
          .build();

  public static final BatchSchema TABLE_LOCATION_SCHEMA =
      BatchSchema.newBuilder()
          .addField(Field.nullable(TABLE_LOCATION, Types.MinorType.VARCHAR.getType()))
          .build();

  public static final BatchSchema CARRY_FORWARD_FILE_PATH_TYPE_SCHEMA =
      BatchSchema.newBuilder()
          .addField(Field.nullable(FILE_PATH, Types.MinorType.VARCHAR.getType()))
          .addField(Field.nullable(FILE_TYPE, Types.MinorType.VARCHAR.getType()))
          .setSelectionVectorMode(BatchSchema.SelectionVectorMode.NONE)
          .build();

  public static final BatchSchema CARRY_FORWARD_FILE_PATH_TYPE_WITH_DATASET_SCHEMA =
      BatchSchema.newBuilder()
          .addField(Field.nullable(DATASET_FIELD, Types.MinorType.VARCHAR.getType()))
          .addField(Field.nullable(FILE_PATH, Types.MinorType.VARCHAR.getType()))
          .addField(Field.nullable(FILE_TYPE, Types.MinorType.VARCHAR.getType()))
          .setSelectionVectorMode(BatchSchema.SelectionVectorMode.NONE)
          .build();

  public static final BatchSchema PATH_SCHEMA =
      BatchSchema.newBuilder()
          .addField(Field.nullable(PATH, Types.MinorType.VARCHAR.getType()))
          .setSelectionVectorMode(BatchSchema.SelectionVectorMode.NONE)
          .build();

  public static final BatchSchema ICEBERG_SPLIT_GEN_WITH_DELETES_SCHEMA =
      SPLIT_GEN_AND_COL_IDS_SCAN_SCHEMA.addColumn(
          new Field(
              DELETE_FILES,
              FieldType.nullable(Types.MinorType.LIST.getType()),
              ImmutableList.of(buildDeleteFileStruct(ListVector.DATA_VECTOR_NAME))));

  public static final BatchSchema DELTALAKE_HISTORY_SCAN_SCHEMA =
      BatchSchema.newBuilder()
          .addField(Field.notNullable(TIMESTAMP, Types.MinorType.TIMESTAMPMILLI.getType()))
          .addField(Field.notNullable(VERSION, Types.MinorType.BIGINT.getType()))
          .addField(Field.nullable(OPERATION, Types.MinorType.VARCHAR.getType()))
          .setSelectionVectorMode(BatchSchema.SelectionVectorMode.NONE)
          .build();

  public static Field buildDeleteFileStruct(String fieldName) {
    return new Field(
        fieldName,
        FieldType.nullable(Types.MinorType.STRUCT.getType()),
        ImmutableList.of(
            Field.nullable(PATH, Types.MinorType.VARCHAR.getType()),
            Field.nullable(FILE_CONTENT, Types.MinorType.INT.getType()),
            Field.nullable(RECORD_COUNT, Types.MinorType.BIGINT.getType()),
            new Field(
                EQUALITY_IDS,
                FieldType.nullable(Types.MinorType.LIST.getType()),
                ImmutableList.of(
                    Field.nullable(ListVector.DATA_VECTOR_NAME, Types.MinorType.INT.getType())))));
  }
}
