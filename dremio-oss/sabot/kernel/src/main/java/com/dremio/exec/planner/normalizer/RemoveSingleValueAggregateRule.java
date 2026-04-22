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
package com.dremio.exec.planner.normalizer;

import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.rules.TransformationRule;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.fun.SqlSingleValueAggFunction;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.Pair;

/**
 * Copied from Calcite's RemoveSingleAggregateRule in:
 *
 * @see com.dremio.exec.planner.logical.CalciteRelDecorrelator
 *     <p>Rule to remove an Aggregate with SINGLE_VALUE. For cases like:
 *     <pre>{@code
 * Aggregate(SINGLE_VALUE)
 *   Project(single expression)
 *     Aggregate
 * }</pre>
 *     <p>For instance, the following subtree from TPCH query 17:
 *     <pre>{@code
 * LogicalAggregate(group=[{}], agg#0=[SINGLE_VALUE($0)])
 *   LogicalProject(EXPR$0=[*(0.2:DECIMAL(2, 1), $0)])
 *     LogicalAggregate(group=[{}], agg#0=[AVG($0)])
 *       LogicalProject(L_QUANTITY=[$4])
 *         LogicalFilter(condition=[=($1, $cor0.P_PARTKEY)])
 *           LogicalTableScan(table=[[TPCH_01, LINEITEM]])
 * }</pre>
 *     <p>will be converted into:
 *     <pre>{@code
 * LogicalProject($f0=[*(0.2:DECIMAL(2, 1), $0)])
 *   LogicalAggregate(group=[{}], agg#0=[AVG($0)])
 *     LogicalProject(L_QUANTITY=[$4])
 *       LogicalFilter(condition=[=($1, $cor0.P_PARTKEY)])
 *         LogicalTableScan(table=[[TPCH_01, LINEITEM]])
 * }</pre>
 *     Note: This rule could be expanded to handle the following cases: 1) no project in-between the
 *     aggregate 2) multiple projects in-between the aggregates 3) As many cardinality preserving
 *     nodes inbetween the aggregates 4) Other aggregates like MIN and MAX
 */
public final class RemoveSingleValueAggregateRule
    extends RelRule<RemoveSingleValueAggregateRule.Config> implements TransformationRule {

  private RemoveSingleValueAggregateRule(RemoveSingleValueAggregateRule.Config config) {
    super(config);
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    final Aggregate singleAggregate = call.rel(0);
    final Project project = call.rel(1);
    final Aggregate aggregate = call.rel(2);

    // check the top aggregate is a single value agg function
    if (!singleAggregate.getGroupSet().isEmpty()
        || (singleAggregate.getAggCallList().size() != 1)
        || !(singleAggregate.getAggCallList().get(0).getAggregation()
            instanceof SqlSingleValueAggFunction)) {
      return;
    }

    // check the project only projects one expression, i.e. scalar sub-queries.
    final List<RexNode> projExprs = project.getProjects();
    if (projExprs.size() != 1) {
      return;
    }

    // check the input to project is an aggregate on the entire input
    if (!aggregate.getGroupSet().isEmpty()) {
      return;
    }

    // ensure we keep the same type after removing the SINGLE_VALUE Aggregate
    final RelBuilder relBuilder = call.builder();
    relBuilder
        .push(aggregate)
        .project(getAliasedProjects(relBuilder, project))
        .convert(singleAggregate.getRowType(), false);
    call.transformTo(relBuilder.build());
  }

  public interface Config extends RelRule.Config {
    Config DEFAULT =
        EMPTY
            .withOperandSupplier(
                b ->
                    b.operand(Aggregate.class)
                        .oneInput(
                            b1 ->
                                b1.operand(Project.class)
                                    .oneInput(b2 -> b2.operand(Aggregate.class).anyInputs())))
            .as(Config.class);

    @Override
    default RemoveSingleValueAggregateRule toRule() {
      return new RemoveSingleValueAggregateRule(this);
    }
  }

  private static List<RexNode> getAliasedProjects(RelBuilder b, Project project) {
    final ImmutableList.Builder<RexNode> builder = ImmutableList.builder();
    Pair.forEach(
        project.getChildExps(),
        project.getRowType().getFieldList(),
        (e, f) -> {
          builder.add(b.alias(e, f.getName()));
        });
    return builder.build();
  }
}
