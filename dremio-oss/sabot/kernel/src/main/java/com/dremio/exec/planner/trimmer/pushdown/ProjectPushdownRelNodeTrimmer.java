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
package com.dremio.exec.planner.trimmer.pushdown;

import com.dremio.exec.planner.normalizer.DremioProjectMergeRule;
import com.dremio.exec.planner.transpose.ProjectCorrelateTransposeRule;
import com.dremio.exec.planner.transpose.ProjectFilterTransposeRule;
import com.dremio.exec.planner.transpose.ProjectProjectRemoveRule;
import com.dremio.exec.planner.trimmer.RelNodeTrimmer;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.plan.hep.HepMatchOrder;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.rules.ProjectJoinTransposeRule;
import org.apache.calcite.rel.rules.ProjectSetOpTransposeRule;
import org.apache.calcite.tools.RelBuilderFactory;

/** Implementation of RelNodeTrimmer that works by running project pushdown on the query tree. */
public final class ProjectPushdownRelNodeTrimmer implements RelNodeTrimmer {
  public static final ProjectPushdownRelNodeTrimmer INSTANCE = new ProjectPushdownRelNodeTrimmer();

  private ProjectPushdownRelNodeTrimmer() {}

  // Missing TableScan, Flatten ...
  private static final List<RelRule.Config> TRANSPOSE_RULES =
      ImmutableList.of(
          ProjectFilterTransposeRule.Config.DEFAULT,
          ProjectJoinTransposeRule.Config.DEFAULT,
          ProjectCorrelateTransposeRule.Config.DEFAULT,
          ProjectSetOpTransposeRule.Config.DEFAULT);

  private static final List<RelRule.Config> TRIM_RULES =
      ImmutableList.of(TrimAggregateRule.Config.DEFAULT, TrimWindowRule.Config.DEFAULT);

  private static final List<RelRule.Config> ADD_TRIMMING_PROJECT_RULES =
      ImmutableList.of(
          AddTrimmingProjectUnderAggregateRule.Config.DEFAULT,
          AddTrimmingProjectUnderSortRule.Config.DEFAULT,
          AddTrimmingProjectUnderWindowRule.Config.DEFAULT);

  public static final List<RelRule.Config> CONFIGS =
      new ImmutableList.Builder<RelRule.Config>()
          .add(DremioProjectMergeRule.Config.DEFAULT)
          .add(ProjectProjectRemoveRule.Config.DEFAULT)
          .addAll(TRANSPOSE_RULES)
          .addAll(TRIM_RULES)
          .addAll(ADD_TRIMMING_PROJECT_RULES)
          .build();

  @Override
  public RelNode trim(RelNode relNode, RelBuilderFactory relBuilderFactory) {
    HepProgramBuilder programBuilder = new HepProgramBuilder();
    programBuilder
        .addRuleCollection(
            CONFIGS.stream()
                .map(config -> config.withRelBuilderFactory(relBuilderFactory).toRule())
                .collect(Collectors.toList()))
        .addMatchOrder(HepMatchOrder.TOP_DOWN);
    HepPlanner hepPlanner = new HepPlanner(programBuilder.build());
    hepPlanner.setRoot(relNode);
    RelNode trimmed = hepPlanner.findBestExp();
    return trimmed;
  }
}
