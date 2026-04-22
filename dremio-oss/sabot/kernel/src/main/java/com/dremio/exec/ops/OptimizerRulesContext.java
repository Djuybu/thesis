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
package com.dremio.exec.ops;

import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.expr.fn.FunctionImplementationRegistry;
import com.dremio.exec.planner.OptimizePlanGenerator;
import com.dremio.exec.planner.OptimizePlanGeneratorBase;
import com.dremio.exec.planner.logical.CreateTableEntry;
import com.dremio.exec.planner.logical.partition.PruneFilterCondition;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.planner.sql.handlers.query.OptimizeOptions;
import com.dremio.exec.store.CatalogService;
import com.dremio.exec.store.TableMetadata;
import com.dremio.exec.tablefunctions.clusteringinfo.ClusteringInfoCatalogMetadata;
import com.dremio.sabot.exec.context.FunctionContext;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;

public interface OptimizerRulesContext extends FunctionContext {
  /**
   * Method returns the function registry
   *
   * @return FunctionImplementationRegistry
   */
  FunctionImplementationRegistry getFunctionRegistry();

  /**
   * Method returns the allocator
   *
   * @return BufferAllocator
   */
  BufferAllocator getAllocator();

  /**
   * Method returns the planner options
   *
   * @return PlannerSettings
   */
  PlannerSettings getPlannerSettings();

  // TODO(DX-43968): Rework to not expose catalog service; optimization is contextualized to Catalog
  CatalogService getCatalogService();

  default OptimizePlanGeneratorBase getOptimizePlanGenerator(
      RelOptTable table,
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelNode input,
      TableMetadata tableMetadata,
      CreateTableEntry createTableEntry,
      OptimizerRulesContext context,
      OptimizeOptions optimizeOptions,
      PruneFilterCondition partitionFilter) {
    return new OptimizePlanGenerator(
        table,
        cluster,
        traitSet,
        input,
        tableMetadata,
        createTableEntry,
        context,
        optimizeOptions,
        partitionFilter);
  }

  default OptimizePlanGeneratorBase getClusteringInfoPlanGenerator(
      RelOptTable table,
      RelOptCluster cluster,
      RelTraitSet traitSet,
      Catalog catalog,
      TableMetadata tableMetadata,
      OptimizerRulesContext context,
      ClusteringInfoCatalogMetadata clusteringInfoMetadata) {
    throw new UnsupportedOperationException("not supported");
  }
}
