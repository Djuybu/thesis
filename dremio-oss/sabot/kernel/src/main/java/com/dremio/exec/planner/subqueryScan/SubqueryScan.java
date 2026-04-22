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
package com.dremio.exec.planner.subqueryScan;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.AbstractRelNode;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;

/**
 * This RelNode represents a subquery of the RelNode tree that we are looking to push into a
 * TableScan operation. Notice that this class is "data only", has no behavior, and no subclassing.
 * This is to keep serialization and the object tree as simple as possible. For any needed behavior
 * we use services like SourceConfigService or stategies like PushFilterIntoSubqueryScanStragies.
 */
public final class SubqueryScan extends AbstractRelNode {
  private final RelNode subquery;
  private final SourceId sourceId;

  private SubqueryScan(
      RelOptCluster cluster, RelTraitSet traitSet, RelNode subquery, SourceId sourceId) {
    super(cluster, traitSet);
    this.subquery = subquery;
    this.sourceId = sourceId;
  }

  public SubqueryScan withSubquery(RelNode relNode) {
    return fromSubquery(relNode, sourceId);
  }

  public static SubqueryScan create(TableScan tableScan, SourceId sourceId) {
    return fromSubquery(tableScan, sourceId);
  }

  private static SubqueryScan fromSubquery(RelNode relNode, SourceId sourceId) {
    return new SubqueryScan(relNode.getCluster(), relNode.getTraitSet(), relNode, sourceId);
  }

  @Override
  protected RelDataType deriveRowType() {
    return subquery.getRowType();
  }

  @Override
  public double estimateRowCount(RelMetadataQuery mq) {
    return subquery.estimateRowCount(mq);
  }

  @Override
  public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq) {
    return subquery.computeSelfCost(planner, mq);
  }

  @Override
  public RelWriter explainTerms(RelWriter pw) {
    super.explainTerms(pw).item("subquery", "{\n" + RelOptUtil.toString(subquery) + "})");
    return pw;
  }

  public RelNode getSubquery() {
    return subquery;
  }

  public SourceId getSourceId() {
    return sourceId;
  }
}
