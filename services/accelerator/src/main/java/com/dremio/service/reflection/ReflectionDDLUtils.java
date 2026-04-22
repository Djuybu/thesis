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
package com.dremio.service.reflection;

import com.dremio.catalog.model.CatalogEntityId;
import com.dremio.catalog.model.VersionedDatasetId;
import com.dremio.exec.catalog.DremioTable;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.reflection.proto.DimensionGranularity;
import com.dremio.service.reflection.proto.MeasureType;
import com.dremio.service.reflection.proto.ReflectionDetails;
import com.dremio.service.reflection.proto.ReflectionDimensionField;
import com.dremio.service.reflection.proto.ReflectionField;
import com.dremio.service.reflection.proto.ReflectionGoal;
import com.dremio.service.reflection.proto.ReflectionMeasureField;
import com.dremio.service.reflection.proto.ReflectionPartitionField;
import com.dremio.service.reflection.proto.ReflectionType;
import com.dremio.service.reflection.proto.Transform;
import java.util.List;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.dialect.CalciteSqlDialect;
import org.apache.calcite.sql.parser.SqlParserPos;

public final class ReflectionDDLUtils {
  private ReflectionDDLUtils() {}

  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(ReflectionDDLUtils.class);

  /**
   * Generates a SQL dataset path from a dataset config. Correctly handles versioned datasets and
   * namespace entities.
   *
   * @return SQL identifier
   */
  public static String generateDatasetPathSQL(DatasetConfig datasetConfig) {

    final CatalogEntityId catalogEntityId =
        CatalogEntityId.fromString(datasetConfig.getId().getId());
    if (catalogEntityId.toVersionedDatasetId().isPresent()) {
      VersionedDatasetId versionedDatasetId = catalogEntityId.toVersionedDatasetId().get();
      StringBuilder builder = new StringBuilder();
      builder.append(toSqlIdentifier(versionedDatasetId.getTableKey()));
      builder.append(" AT ");
      builder.append(versionedDatasetId.getVersionContext().toSql());
      return builder.toString();
    } else {
      return toSqlIdentifier(datasetConfig.getFullPathList());
    }
  }

  private static String toSqlIdentifier(List<String> path) {
    return (new SqlIdentifier(path, SqlParserPos.ZERO))
        .toSqlString(CalciteSqlDialect.DEFAULT)
        .getSql();
  }

  /**
   * Generates a SQL dataset path when datasetId is a versioned dataset. Otherwise, a namespace
   * entity id is returned.
   *
   * @param datasetId
   * @return SQL identifier
   */
  public static String generateDatasetPathSQL(String datasetId) {
    final CatalogEntityId catalogEntityId = CatalogEntityId.fromString(datasetId);
    if (catalogEntityId.toNamespaceEntityId().isPresent()) {
      return "\"NAMESPACE_ENTITY=" + catalogEntityId.toNamespaceEntityId().get() + "\"";
    } else if (catalogEntityId.toVersionedDatasetId().isPresent()) {
      VersionedDatasetId versionedDatasetId = catalogEntityId.toVersionedDatasetId().get();
      StringBuilder builder = new StringBuilder();
      builder.append(toSqlIdentifier(versionedDatasetId.getTableKey()));
      builder.append(" AT ");
      builder.append(versionedDatasetId.getVersionContext().toSql());
      return builder.toString();
    } else {
      return "\"UNKNOWN_ENTITY=" + datasetId + "\"";
    }
  }

  /**
   * Generates an ALTER statement to re-create a reflection given a reflection goal.
   *
   * @param goal
   * @param datasetSQL Must be fully qualified and quoted if necessary
   * @return
   */
  public static String generateDDLfromGoal(ReflectionGoal goal, String datasetSQL) {
    assert (goal.getType() != ReflectionType.EXTERNAL);
    StringBuilder builder = new StringBuilder();
    builder.append("ALTER");
    builder.append(" DATASET ");
    builder.append(datasetSQL);
    builder.append(" CREATE ");
    builder.append(
        goal.getType() == com.dremio.service.reflection.proto.ReflectionType.RAW
            ? "RAW REFLECTION "
            : "AGGREGATE REFLECTION ");
    appendQuoted(builder, goal.getName());
    builder.append(" USING");
    final ReflectionDetails details = goal.getDetails();
    if (details != null) {
      if (goal.getType() == com.dremio.service.reflection.proto.ReflectionType.RAW) {
        builder.append(" DISPLAY ");
        appendFields(builder, details.getDisplayFieldList());
      } else {
        if (details.getDimensionFieldList() != null) {
          builder.append(" DIMENSIONS ");
          appendDimensions(builder, details.getDimensionFieldList());
        }
        if (details.getMeasureFieldList() != null) {
          builder.append(" MEASURES ");
          appendMeasures(builder, details.getMeasureFieldList());
        } else {
          builder.append(" MEASURES () ");
        }
      }
      if (details.getPartitionFieldList() != null) {
        builder.append(" PARTITION BY ");
        appendPartitionFields(builder, details.getPartitionFieldList());
      }
      if (details.getSortFieldList() != null) {
        builder.append(" LOCALSORT BY ");
        appendFields(builder, details.getSortFieldList());
      }
    }
    return builder.toString();
  }

  private static void appendFields(StringBuilder builder, List<ReflectionField> fields) {
    builder.append("(");
    boolean first = true;
    for (ReflectionField field : fields) {
      if (!first) {
        builder.append(",");
      }
      builder.append(System.lineSeparator());
      appendQuoted(builder, field.getName());
      first = false;
    }
    builder.append(System.lineSeparator());
    builder.append(")");
  }

  private static void appendPartitionFields(
      StringBuilder builder, List<ReflectionPartitionField> fields) {
    builder.append("(");
    boolean first = true;
    for (ReflectionPartitionField field : fields) {
      if (!first) {
        builder.append(",");
      }
      builder.append(System.lineSeparator());
      builder.append(toPartitionFieldSQL(field));
      first = false;
    }
    builder.append(System.lineSeparator());
    builder.append(")");
  }

  public static String toPartitionFieldSQL(ReflectionPartitionField field) {
    StringBuilder builder = new StringBuilder();
    if (field.getTransform() != null) {
      builder.append(field.getTransform().getType().toString());
      builder.append("(");
      if (field.getTransform().getType() == Transform.Type.BUCKET) {
        builder.append(field.getTransform().getBucketTransform().getBucketCount());
        builder.append(",");
      } else if (field.getTransform().getType() == Transform.Type.TRUNCATE) {
        builder.append(field.getTransform().getTruncateTransform().getTruncateLength());
        builder.append(",");
      }
      appendQuoted(builder, field.getName());
      builder.append(")");
    } else {
      appendQuoted(builder, field.getName());
    }
    return builder.toString();
  }

  private static void appendDimensions(
      StringBuilder builder, List<ReflectionDimensionField> fields) {
    builder.append("(");
    boolean first = true;
    for (ReflectionDimensionField field : fields) {
      if (!first) {
        builder.append(",");
      }
      builder.append(System.lineSeparator());
      appendQuoted(builder, field.getName());
      if (field.getGranularity() == DimensionGranularity.DATE) {
        builder.append(" BY DAY");
      }
      first = false;
    }
    builder.append(System.lineSeparator());
    builder.append(")");
  }

  private static void appendMeasures(StringBuilder builder, List<ReflectionMeasureField> fields) {
    builder.append("(");
    boolean first = true;
    for (ReflectionMeasureField field : fields) {
      if (!first) {
        builder.append(",");
      }
      builder.append(System.lineSeparator());
      appendQuoted(builder, field.getName());
      builder.append("(");
      boolean firstType = true;
      if (field.getMeasureTypeList() != null) {
        for (MeasureType type : field.getMeasureTypeList()) {
          if (!firstType) {
            builder.append(",");
          }
          builder.append(type.toString().replace("_", " "));
          firstType = false;
        }
      }
      builder.append(")");
      first = false;
    }
    builder.append(System.lineSeparator());
    builder.append(")");
  }

  private static void appendQuoted(StringBuilder build, String value) {
    build.append("\"");
    build.append(value);
    build.append("\"");
  }

  /** Generate DDL for view */
  public static String generateViewDDL(DremioTable viewTable) {
    if (viewTable == null || viewTable.getDatasetConfig().getVirtualDataset() == null) {
      return null;
    }
    StringBuilder builder = new StringBuilder();
    builder.append("CREATE");
    builder.append(" VIEW ");
    builder.append(viewTable.getPath());
    builder.append(" AS ");
    builder.append(viewTable.getDatasetConfig().getVirtualDataset().getSql());
    return builder.toString();
  }
}
