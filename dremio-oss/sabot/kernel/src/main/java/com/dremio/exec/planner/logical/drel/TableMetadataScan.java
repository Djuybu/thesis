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
package com.dremio.exec.planner.logical.drel;

import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.planner.common.ScanRelBase;
import com.dremio.exec.planner.logical.Rel;
import com.dremio.exec.store.TableMetadata;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.ReadDefinition;
import com.dremio.service.namespace.dataset.proto.ScanStats;
import java.util.List;
import java.util.Optional;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.metadata.RelMetadataQuery;

/**
 * Logical scan operator for doing special scans on the metadata of a table rather than the data.
 */
public class TableMetadataScan extends ScanRelBase implements Rel {

  private final List<AggregateCall> aggregateFunctions;

  public TableMetadataScan(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelOptTable table,
      StoragePluginId pluginId,
      TableMetadata tableMetadata,
      List<SchemaPath> projectedColumns,
      List<RelHint> hints) {
    this(cluster, traitSet, table, pluginId, tableMetadata, projectedColumns, hints, null);
  }

  public TableMetadataScan(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelOptTable table,
      StoragePluginId pluginId,
      TableMetadata tableMetadata,
      List<SchemaPath> projectedColumns,
      List<RelHint> hints,
      List<AggregateCall> aggregateFunctions) {
    super(cluster, traitSet, table, pluginId, tableMetadata, projectedColumns, 1.0d, hints);
    this.aggregateFunctions = aggregateFunctions;
  }

  @Override
  public RelWriter explainTerms(RelWriter pw) {
    return super.explainTerms(pw)
        .itemIf("aggregates", aggregateFunctions, aggregateFunctions != null);
  }

  @Override
  public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
    return new TableMetadataScan(
        getCluster(),
        getTraitSet(),
        getTable(),
        getPluginId(),
        getTableMetadata(),
        getProjectedColumns(),
        getHints(),
        aggregateFunctions);
  }

  @Override
  public ScanRelBase cloneWithProject(List<SchemaPath> projection) {
    throw new UnsupportedOperationException("cloneWithProject not supported for TableMetadataScan");
  }

  @Override
  public double estimateRowCount(RelMetadataQuery mq) {
    return Optional.of(getTableMetadata())
        .map(TableMetadata::getDatasetConfig)
        .map(DatasetConfig::getReadDefinition)
        .map(ReadDefinition::getManifestScanStats)
        .map(ScanStats::getRecordCount)
        .map(Long::doubleValue)
        .orElseGet(() -> super.estimateRowCount(mq));
  }
}
