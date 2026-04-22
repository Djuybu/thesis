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

import com.google.common.base.Preconditions;
import java.util.List;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.AbstractRelNode;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;

public abstract class ClusteringInfoRelBase extends AbstractRelNode {
  private final ClusteringInfoContext context;
  private final ClusteringInfoCatalogMetadata clusteringInfoMetadata;

  protected ClusteringInfoRelBase(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      ClusteringInfoContext context,
      ClusteringInfoCatalogMetadata clusteringInfoMetadata) {
    super(cluster, traitSet);
    this.context = Preconditions.checkNotNull(context, "ClusteringInfo content must be not null");
    this.clusteringInfoMetadata = clusteringInfoMetadata;
  }

  @Override
  public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq) {
    return planner.getCostFactory().makeTinyCost();
  }

  /**
   * Subclasses must override copy to avoid problems where duplicate scan operators are created due
   * to the same (reference-equality) Prel being used multiple times in the plan. The copy
   * implementation in AbstractRelNode just returns a reference to "this".
   */
  @Override
  public abstract RelNode copy(RelTraitSet traitSet, List<RelNode> inputs);

  @Override
  public RelOptTable getTable() {
    return context.getResolvedTargetTable();
  }

  @Override
  protected RelDataType deriveRowType() {
    return clusteringInfoMetadata.getRowType();
  }

  @Override
  public RelWriter explainTerms(RelWriter pw) {
    super.explainTerms(pw);
    if (context.getResolvedTargetTable() != null) {
      pw.item("table", context.getResolvedTargetTable().getQualifiedName());
    }

    // If the Iceberg metadata location is updated, we need to re-run CLUSTERING_INFORMATION
    // table function.
    if (context.getMetadataLocation() != null) {
      pw.item("metadata_location", context.getMetadataLocation());
    }

    // Clustering feature uses sort_order to store fields that used for clustering.
    if (context.getSortOrder() != null) {
      pw.item("sort_order", context.getSortOrder());
    }

    // Iceberg Table properties include info used for clustering.
    if (context.getTableProperties() != null) {
      pw.item("table_properties", context.getTableProperties().toString());
    }
    return pw;
  }

  public ClusteringInfoContext getContext() {
    return context;
  }

  protected ClusteringInfoCatalogMetadata getClusteringInfoMetadata() {
    return clusteringInfoMetadata;
  }
}
