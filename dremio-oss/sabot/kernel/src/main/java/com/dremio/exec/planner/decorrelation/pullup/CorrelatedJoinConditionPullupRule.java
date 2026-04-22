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

import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.rules.TransformationRule;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.tools.RelBuilder;

/**
 * When decorrelating mixed correlated variables we end up with correlated variables inside the join
 * condition like so:
 *
 * <pre>
 *   LogicalCorrelate(correlation=[$cor0], joinType=[left], requiredColumns=[{0}])
 *    LogicalTableScan(table=[[DEPT]])
 *    LogicalJoin(condition=[=($cor0.DEPTNO, $7)], joinType=[left])
 *       LogicalTableScan(table=[[DEPT]])
 *       LogicalTableScan(table=[[EMP]])
 * </pre>
 *
 * And in order to decorrelate this plan we rewrite it like so:
 *
 * <pre>
 *   LogicalCorrelate(correlation=[$cor0], joinType=[left], requiredColumns=[{0}])
 *    LogicalTableScan(table=[[DEPT]])
 *    LogicalFilter(condition=[=($cor0.DEPTNO, $7))
 *    LogicalJoin(condition=[=true], joinType=[left])
 *       LogicalTableScan(table=[[DEPT]])
 *       LogicalTableScan(table=[[EMP]])
 * </pre>
 */
public final class CorrelatedJoinConditionPullupRule
    extends RelRule<CorrelatedJoinConditionPullupRule.Config> implements TransformationRule {
  private CorrelatedJoinConditionPullupRule(Config config) {
    super(config);
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    final Join join = call.rel(0);
    RelBuilder relBuilder = call.builder();
    RexBuilder rexBuilder = relBuilder.getRexBuilder();
    RelNode transformed =
        relBuilder
            .push(join.getLeft())
            .push(join.getRight())
            .join(join.getJoinType(), rexBuilder.makeLiteral(true))
            .filter(join.getCondition())
            .build();
    call.transformTo(transformed);
  }

  public interface Config extends RelRule.Config {
    Config DEFAULT =
        EMPTY
            .withDescription(CorrelatedJoinConditionPullupRule.class.getSimpleName())
            .withOperandSupplier(
                op ->
                    op.operand(Join.class)
                        .predicate(join -> RexUtil.containsCorrelation(join.getCondition()))
                        .anyInputs())
            .as(Config.class);

    @Override
    default CorrelatedJoinConditionPullupRule toRule() {
      return new CorrelatedJoinConditionPullupRule(this);
    }
  }
}
