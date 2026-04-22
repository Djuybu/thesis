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
package com.dremio.exec.planner.decorrelation.pullup;

import com.dremio.exec.planner.decorrelation.Decorrelator;
import com.dremio.exec.planner.logical.CorrelatedFilterSplitRule;
import com.dremio.exec.planner.transpose.CorrelateProjectTransposeRule;
import com.dremio.exec.planner.transpose.FilterFilterTransposeRule;
import com.dremio.exec.planner.transpose.JoinFilterTransposeRuleBase.Config.NullHandling;
import com.dremio.exec.planner.transpose.JoinFilterTransposeRuleLeft;
import com.dremio.exec.planner.transpose.JoinFilterTransposeRuleRight;
import com.dremio.exec.planner.transpose.JoinProjectTransposeRuleLeft;
import com.dremio.exec.planner.transpose.JoinProjectTransposeRuleRight;
import com.dremio.exec.planner.transpose.ProjectFilterTransposeRule;
import com.dremio.exec.planner.transpose.ProjectProjectRemoveRule;
import com.dremio.exec.planner.transpose.SetOpFilterTransposeRule;
import com.dremio.exec.planner.transpose.SortFilterTransposeRule;
import com.dremio.exec.planner.transpose.SortProjectTransposeRule;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Correlate;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.SetOp;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.rules.AggregateProjectMergeRule;
import org.apache.calcite.rel.rules.FilterMergeRule;
import org.apache.calcite.rel.rules.FilterProjectTransposeRule;
import org.apache.calcite.rel.rules.ProjectMergeRule;
import org.apache.calcite.tools.RelBuilderFactory;

public final class PullupDecorrelator implements Decorrelator {
  private final RelBuilderFactory relBuilderFactory;

  private PullupDecorrelator(RelBuilderFactory relBuilderFactory) {
    this.relBuilderFactory = relBuilderFactory;
  }

  @Override
  public RelNode decorrelate(RelNode relNode) {
    HepProgramBuilder programBuilder = new HepProgramBuilder();
    programBuilder.addRuleCollection(
        Configs.LIST.stream()
            .map(config -> config.withRelBuilderFactory(relBuilderFactory).toRule())
            .collect(Collectors.toList()));
    HepPlanner hepPlanner = new HepPlanner(programBuilder.build());
    hepPlanner.setRoot(relNode);
    RelNode decorrelated = hepPlanner.findBestExp();
    return decorrelated;
  }

  public static PullupDecorrelator create(RelBuilderFactory relBuilderFactory) {
    return new PullupDecorrelator(relBuilderFactory);
  }

  public static class Configs {
    public static final class Decorrelation {
      public static final RelRule.Config DECORRELATION_RULE = DecorrelationRule.Config.DEFAULT;

      public static final List<RelRule.Config> LIST = ImmutableList.of(DECORRELATION_RULE);
    }

    public static final class CorrelatedFilterPullup {
      public static final RelRule.Config CORRELATED_FILTER_MERGE_RULE =
          FilterMergeRule.Config.DEFAULT
              .withDescription("CorrelatedFilterMergeRule")
              .withOperandSupplier(
                  op1 ->
                      op1.operand(Filter.class)
                          .predicate(Utils::isPurelyCorrelatedFilter)
                          .oneInput(
                              op2 ->
                                  op2.operand(Filter.class)
                                      .predicate(Utils::isPurelyCorrelatedFilter)
                                      .anyInputs()));
      public static final RelRule.Config CORRELATED_FILTER_SPLIT_RULE =
          CorrelatedFilterSplitRule.Config.DEFAULT;

      public static final RelRule.Config CORRELATED_JOIN_CONDITION_PULLUP_RULE =
          CorrelatedJoinConditionPullupRule.Config.DEFAULT;
      public static final RelRule.Config AGGREGATE_CORRELATE_FILTER_TRANSPOSE_RULE =
          AggregateCorrelatedFilterTransposeRule.Config.DEFAULT;
      public static final RelRule.Config PROJECT_CORRELATED_FILTER_TRANSPOSE_RULE =
          ProjectFilterTransposeRule.Config.DEFAULT
              .withDescription("BlockingProjectCorrelatedFilterTransposeRule")
              .withOperandSupplier(OperandTransformFactory.BLOCKING_PROJECT);

      public static final RelRule.Config WEAK_PROJECT_FILTER_TRANSPOSE_RULE =
          WeakProjectCorrelatedFilterTransposeRule.Config.DEFAULT;

      public static final RelRule.Config FILTER_CORRELATED_FILTER_TRANSPOSE_RULE =
          FilterFilterTransposeRule.Config.DEFAULT
              .withDescription("FilterCorrelatedFilterTransposeRule")
              .withOperandSupplier(
                  op1 ->
                      op1.operand(Filter.class)
                          .predicate(Utils::isPurelyUncorrelatedFilter)
                          .oneInput(
                              op2 ->
                                  op2.operand(Filter.class)
                                      .predicate(Utils::isCorrelatedFilter)
                                      .anyInputs()));

      public static final RelRule.Config SORT_CORRELATED_FILTER_TRANSPOSE_RULE =
          SortFilterTransposeRule.Config.DEFAULT
              .withDescription("SortCorrelatedFilterTransposeRule")
              .withOperandSupplier(OperandTransformFactory.single(Sort.class).correlatedFilter());

      public static final RelRule.Config JOIN_CORRELATED_FILTER_TRANSPOSE_RULE_LEFT =
          JoinFilterTransposeRuleLeft.Config.DEFAULT
              .withNullHandling(NullHandling.ALWAYS)
              .withOperandSupplier(
                  OperandTransformFactory.bi(Join.class).left().correlatedFilter());

      public static final RelRule.Config JOIN_CORRELATED_FILTER_TRANSPOSE_RULE_RIGHT =
          JoinFilterTransposeRuleRight.Config.DEFAULT
              .withNullHandling(NullHandling.ALWAYS)
              .withOperandSupplier(
                  OperandTransformFactory.bi(Join.class).right().correlatedFilter());

      public static final RelRule.Config SET_OP_FILTER_TRANSPOSE_RULE =
          SetOpFilterTransposeRule.Config.DEFAULT.withOperandSupplier(
              OperandTransformFactory.single(SetOp.class).correlatedFilter());

      public static final List<RelRule.Config> LIST =
          ImmutableList.of(
              CORRELATED_FILTER_MERGE_RULE,
              CORRELATED_FILTER_SPLIT_RULE,
              // DECORRELATION_RULE replaces the need for CORRELATE_CORRELATED_FILTER_TRANSPOSE_RULE
              CORRELATED_JOIN_CONDITION_PULLUP_RULE,
              AGGREGATE_CORRELATE_FILTER_TRANSPOSE_RULE,
              PROJECT_CORRELATED_FILTER_TRANSPOSE_RULE,
              WEAK_PROJECT_FILTER_TRANSPOSE_RULE,
              FILTER_CORRELATED_FILTER_TRANSPOSE_RULE,
              SORT_CORRELATED_FILTER_TRANSPOSE_RULE,
              JOIN_CORRELATED_FILTER_TRANSPOSE_RULE_LEFT,
              JOIN_CORRELATED_FILTER_TRANSPOSE_RULE_RIGHT,
              SET_OP_FILTER_TRANSPOSE_RULE);
    }

    public static final class BlockingProjectPullup {
      public static final RelRule.Config PROJECT_REMOVE_RULE =
          ProjectProjectRemoveRule.Config.DEFAULT;
      public static final RelRule.Config PROJECT_MERGE_RULE = ProjectMergeRule.Config.DEFAULT;

      public static final RelRule.Config CORRELATE_ADJUST_COUNT_PROJECT_RULE =
          CorrelateAdjustCountProjectRule.Config.DEFAULT;
      public static final RelRule.Config CORRELATE_BLOCKING_PROJECT_TRANSPOSE_RULE =
          CorrelateProjectTransposeRule.Config.RIGHT.withOperandSupplier(
              OperandTransformFactory.bi(Correlate.class).right().blockingProject());
      public static final RelRule.Config AGGREGATE_BLOCKING_PROJECT_MERGE_RULE =
          AggregateProjectMergeRule.Config.DEFAULT
              .withDescription("AggregateBlockingProjectMergeRule")
              .withOperandSupplier(
                  OperandTransformFactory.single(Aggregate.class).blockingProject());
      public static final RelRule.Config FILTER_BLOCKING_PROJECT_TRANSPOSE_RULE =
          FilterProjectTransposeRule.Config.DEFAULT
              .withDescription("FilterBlockingProjectTransposeRule")
              .withOperandSupplier(OperandTransformFactory.single(Filter.class).blockingProject());
      public static final RelRule.Config FILTER_ADJUST_COUNT_PROJECT_RULE =
          FilterAdjustCountProjectRule.Config.DEFAULT;
      public static final RelRule.Config SORT_BLOCKING_PROJECT_TRANSPOSE_RULE =
          SortProjectTransposeRule.Config.DEFAULT
              .withDescription("SortBlockingProjectTransposeRule")
              .withOperandSupplier(OperandTransformFactory.single(Sort.class).blockingProject());
      public static final RelRule.Config JOIN_BLOCKING_PROJECT_TRANSPOSE_RULE_LEFT =
          JoinProjectTransposeRuleLeft.Config.DEFAULT
              .withDescription("JoinBlockingProjectTransposeRule(Project-Other)")
              .withOperandSupplier(OperandTransformFactory.bi(Join.class).left().blockingProject());
      public static final RelRule.Config JOIN_BLOCKING_PROJECT_TRANSPOSE_RULE_RIGHT =
          JoinProjectTransposeRuleRight.Config.DEFAULT
              .withDescription("JoinBlockingProjectTransposeRule(Other-Project)")
              .withOperandSupplier(
                  OperandTransformFactory.bi(Join.class).right().blockingProject());

      public static final List<RelRule.Config> LIST =
          ImmutableList.of(
              PROJECT_REMOVE_RULE,
              PROJECT_MERGE_RULE,
              CORRELATE_ADJUST_COUNT_PROJECT_RULE,
              CORRELATE_BLOCKING_PROJECT_TRANSPOSE_RULE,
              AGGREGATE_BLOCKING_PROJECT_MERGE_RULE,
              FILTER_BLOCKING_PROJECT_TRANSPOSE_RULE,
              FILTER_ADJUST_COUNT_PROJECT_RULE,
              SORT_BLOCKING_PROJECT_TRANSPOSE_RULE,
              JOIN_BLOCKING_PROJECT_TRANSPOSE_RULE_LEFT,
              JOIN_BLOCKING_PROJECT_TRANSPOSE_RULE_RIGHT);
    }

    public static final List<RelRule.Config> LIST =
        new ImmutableList.Builder<RelRule.Config>()
            .addAll(Decorrelation.LIST)
            .addAll(CorrelatedFilterPullup.LIST)
            .addAll(BlockingProjectPullup.LIST)
            .build();
  }
}
