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
package com.dremio.exec.planner.common;

import static com.dremio.exec.ExecConstants.BATCH_LIST_SIZE_ESTIMATE;
import static com.dremio.exec.ExecConstants.BATCH_MAP_SIZE_ESTIMATE;
import static com.dremio.exec.ExecConstants.BATCH_VARIABLE_FIELD_SIZE_ESTIMATE;
import static com.dremio.exec.ExecConstants.PARQUET_FILES_ESTIMATE_SCALING_FACTOR_VALIDATOR;
import static com.dremio.exec.ExecConstants.PARQUET_SPLIT_SIZE_VALIDATOR;
import static java.lang.Math.ceil;
import static java.lang.Math.max;

import com.dremio.exec.planner.logical.CreateTableEntry;
import com.dremio.exec.planner.physical.PrelUtil;
import com.dremio.exec.planner.sql.CalciteArrowHelper;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.RecordWriter;
import com.dremio.options.OptionResolver;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.SingleRel;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;

/** Base class for logical and physical Writer implemented in Dremio. */
public abstract class WriterRelBase extends SingleRel {

  private final CreateTableEntry createTableEntry;

  public WriterRelBase(
      Convention convention,
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelNode input,
      CreateTableEntry createTableEntry) {
    super(cluster, traitSet, input);
    assert input.getConvention() == convention;
    this.createTableEntry = createTableEntry;

    rowType =
        CalciteArrowHelper.wrap(RecordWriter.SCHEMA)
            .toCalciteRecordType(getCluster().getTypeFactory(), true);
  }

  public CreateTableEntry getCreateTableEntry() {
    return createTableEntry;
  }

  public static double estimateRowCount(
      SingleRel relNode, RelMetadataQuery relMetadataQuery, RelDataType relDataType) {
    // Count estimate from input
    RelOptCluster cluster = relNode.getCluster();
    OptionResolver optionResolver = PrelUtil.getPlannerSettings(cluster.getPlanner()).getOptions();

    long scalingFactor = optionResolver.getOption(PARQUET_FILES_ESTIMATE_SCALING_FACTOR_VALIDATOR);
    long parquetFileSize = optionResolver.getOption(PARQUET_SPLIT_SIZE_VALIDATOR);
    int listEstimate = (int) optionResolver.getOption(BATCH_LIST_SIZE_ESTIMATE);
    int mapEstimate = (int) optionResolver.getOption(BATCH_MAP_SIZE_ESTIMATE);
    int variableField = (int) optionResolver.getOption(BATCH_VARIABLE_FIELD_SIZE_ESTIMATE);

    BatchSchema schema = CalciteArrowHelper.fromCalciteRowType(relDataType);
    double estimateInputRowCount = relMetadataQuery.getRowCount(relNode.getInput());
    int estimatedRowSize = schema.estimateRecordSize(listEstimate, mapEstimate, variableField);

    double numRecords = ceil((double) parquetFileSize / estimatedRowSize) + 1;
    return max(10, estimateInputRowCount / numRecords) * scalingFactor;
  }
}
