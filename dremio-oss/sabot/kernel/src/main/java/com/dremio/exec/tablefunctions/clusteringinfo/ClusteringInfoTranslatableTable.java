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
package com.dremio.exec.tablefunctions.clusteringinfo;

import com.dremio.common.expression.CompleteType;
import com.dremio.exec.planner.sql.CalciteArrowHelper;
import com.dremio.exec.record.BatchSchema;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.schema.Statistics;
import org.apache.calcite.schema.TranslatableTable;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlNode;

/** TranslatableTable implementation of clustering_information table function */
public class ClusteringInfoTranslatableTable implements TranslatableTable {
  public static final String TABLE_NAME = "table_name";
  public static final String CLUSTERING_KEYS = "clustering_keys";
  public static final String CLUSTERING_DEPTH = "clustering_depth";
  public static final String LAST_CLUSTERING_TIMESTAMP = "last_clustering_timestamp";
  private final ClusteringInfoContext context;

  public static final BatchSchema CLUSTERING_INFORMATION_TABLE_FUNCTION_SCHEMA =
      BatchSchema.newBuilder()
          .addField(Field.nullable(TABLE_NAME, Types.MinorType.VARCHAR.getType()))
          .addField(Field.nullable(CLUSTERING_KEYS, Types.MinorType.VARCHAR.getType()))
          .addField(Field.nullable(CLUSTERING_DEPTH, Types.MinorType.FLOAT4.getType()))
          .addField(Field.nullable(LAST_CLUSTERING_TIMESTAMP, Types.MinorType.VARCHAR.getType()))
          .setSelectionVectorMode(BatchSchema.SelectionVectorMode.NONE)
          .build();

  public ClusteringInfoTranslatableTable(ClusteringInfoContext context) {
    this.context = context;
  }

  @Override
  public RelNode toRel(RelOptTable.ToRelContext toRelContext, RelOptTable relOptTable) {

    return new ClusteringInfoCrel(
        toRelContext.getCluster(),
        toRelContext.getCluster().traitSet(),
        context,
        new ClusteringInfoCatalogMetadata(
            getBatchSchema(), getRowType(toRelContext.getCluster().getTypeFactory())));
  }

  private static BatchSchema getBatchSchema() {
    return CLUSTERING_INFORMATION_TABLE_FUNCTION_SCHEMA;
  }

  @Override
  public RelDataType getRowType(RelDataTypeFactory typeFactory) {
    final RelDataTypeFactory.FieldInfoBuilder builder =
        new RelDataTypeFactory.FieldInfoBuilder(typeFactory);
    for (Field field : CLUSTERING_INFORMATION_TABLE_FUNCTION_SCHEMA) {
      builder.add(
          field.getName(),
          CalciteArrowHelper.wrap(CompleteType.fromField(field)).toCalciteType(typeFactory, true));
    }
    return builder.build();
  }

  @Override
  public Statistic getStatistic() {
    return Statistics.UNKNOWN;
  }

  @Override
  public Schema.TableType getJdbcTableType() {
    return Schema.TableType.TABLE;
  }

  @Override
  public boolean isRolledUp(String column) {
    return false;
  }

  @Override
  public boolean rolledUpColumnValidInsideAgg(
      String column, SqlCall call, SqlNode parent, CalciteConnectionConfig config) {
    return false;
  }
}
