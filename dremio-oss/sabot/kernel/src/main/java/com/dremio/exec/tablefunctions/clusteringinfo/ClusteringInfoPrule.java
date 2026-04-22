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

import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.catalog.DremioPrepareTable;
import com.dremio.exec.ops.OptimizerRulesContext;
import com.dremio.exec.planner.OptimizePlanGeneratorBase;
import com.dremio.exec.planner.logical.RelOptHelper;
import com.dremio.exec.planner.physical.Prel;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;

/**
 * Prule for clustering_information table function, convert {@link ClusteringInfoDrel} to its
 * physical plan.
 */
public final class ClusteringInfoPrule extends RelOptRule {

  private final OptimizerRulesContext optimizerRulesContext;

  public ClusteringInfoPrule(OptimizerRulesContext optimizerRulesContext) {
    super(RelOptHelper.any(ClusteringInfoDrel.class), "ClusteringInfoPrule");
    this.optimizerRulesContext = optimizerRulesContext;
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    final ClusteringInfoDrel clusteringInfoRel = call.rel(0);

    Catalog catalog = (Catalog) clusteringInfoRel.getContext().getCatalog();

    OptimizePlanGeneratorBase planGenerator =
        optimizerRulesContext.getClusteringInfoPlanGenerator(
            clusteringInfoRel.getTable(),
            clusteringInfoRel.getCluster(),
            clusteringInfoRel.getTraitSet().plus(Prel.PHYSICAL),
            catalog,
            ((DremioPrepareTable) clusteringInfoRel.getTable()).getTable().getDataset(),
            optimizerRulesContext,
            clusteringInfoRel.getClusteringInfoMetadata());

    call.transformTo(planGenerator.getPlan());
  }
}
