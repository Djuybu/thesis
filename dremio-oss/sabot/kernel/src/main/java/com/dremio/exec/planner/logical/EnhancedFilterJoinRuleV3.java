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
package com.dremio.exec.planner.logical;

import com.dremio.exec.planner.common.MoreRelOptUtil;
import java.util.List;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelShuttleImpl;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.tools.RelBuilder;

/**
 * Rule that pushes predicates from a Filter and a Join into the Join below, and pushes the join
 * condition into the Join. This is extended version of EnhancedFilterJoinRule, which applies guard
 * rail on filter condition and on join condition to stop infinite loop issues and also removes the
 * legacy code to stop infinite loop which was using RelMdPredicates to pull up predicates which was
 * really expensive for the rel trees with number of joins.
 */
public final class EnhancedFilterJoinRuleV3 extends EnhancedFilterJoinRule {

  public static final EnhancedFilterJoinRule WITH_FILTER =
      new EnhancedFilterJoinRuleV3(
          RelRule.Config.EMPTY
              .withDescription("EnhancedFilterJoinRuleV3:withFilter")
              .withOperandSupplier(
                  op ->
                      op.operand(Filter.class)
                          .oneInput(join -> join.operand(Join.class).anyInputs()))
              .as(Config.class));

  public static final EnhancedFilterJoinRule NO_FILTER =
      new EnhancedFilterJoinRuleV3(
          RelRule.Config.EMPTY
              .withDescription("EnhancedFilterJoinRuleV3:withoutFilter")
              .withOperandSupplier(op -> op.operand(Join.class).anyInputs())
              .as(Config.class));

  private EnhancedFilterJoinRuleV3(Config config) {
    super(config);
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    Filter filter;
    Join join;

    if (call.rel(0) instanceof Filter) {
      // With Filter Configuration
      filter = call.rel(0);
      join = call.rel(1);
    } else {
      filter = null;
      join = call.rel(0);
    }

    RelNode rewrite = doMatch(filter, join, call.builder());
    if (rewrite != null) {
      call.transformTo(
          rewrite.accept(
              new RelShuttleImpl() {
                @Override
                public RelNode visit(LogicalJoin join) {
                  LogicalJoin newJoin =
                      DremioJoinPushTransitivePredicatesRule.findAndApplyTransitivePredicates(join);
                  if (newJoin == null) {
                    newJoin = join;
                  }
                  // Wrap the top filter in a special node that tells the rule not to push it down
                  // further
                  return newJoin.copy(
                      newJoin.getTraitSet(),
                      newJoin
                          .getCluster()
                          .getRexBuilder()
                          .makeCall(DO_NOT_PUSH_DOWN_OPERATOR, newJoin.getCondition()),
                      newJoin.getLeft(),
                      newJoin.getRight(),
                      newJoin.getJoinType(),
                      newJoin.isSemiJoinDone());
                }
              }));
    }
  }

  @Override
  RelNode doMatch(Filter filterRel, Join joinRel, RelBuilder relBuilder) {
    if (((filterRel != null && containsDoNotPushdownOperator(filterRel.getCondition()))
        || (filterRel == null && containsDoNotPushdownOperator(joinRel.getCondition())))) {
      return null;
    }

    if (filterRel != null && containsDoNotPushdownOperator(joinRel.getCondition())) {
      joinRel =
          joinRel.copy(
              joinRel.getTraitSet(),
              joinRel
                  .getCondition()
                  .accept(
                      new RexShuttle() {
                        @Override
                        public RexNode visitCall(final RexCall call) {
                          if (!call.getOperator().equals(DO_NOT_PUSH_DOWN_OPERATOR)) {
                            return super.visitCall(call);
                          }
                          return call.getOperands().get(0);
                        }
                      }),
              joinRel.getLeft(),
              joinRel.getRight(),
              joinRel.getJoinType(),
              joinRel.isSemiJoinDone());
    }

    // Extract the join condition and pushdown predicates, also simplify the remaining filter
    EnhancedFilterJoinExtraction extraction =
        new EnhancedFilterJoinExtractor(
                filterRel, joinRel, FilterJoinRulesUtil.EQUAL_IS_NOT_DISTINCT_FROM)
            .extract();
    RexNode inputFilterConditionPruned = extraction.getInputFilterConditionPruned();
    RexNode inputJoinConditionPruned = extraction.getInputJoinConditionPruned();
    RexNode newJoinCondition = extraction.getJoinCondition();
    RexNode leftPushdownPredicate = extraction.getLeftPushdownPredicate();
    RexNode rightPushdownPredicate = extraction.getRightPushdownPredicate();
    RexNode remainingFilterCondition = extraction.getRemainingFilterCondition();
    JoinRelType simplifiedJoinType = extraction.getSimplifiedJoinType();

    // Shift filters
    List<RelDataTypeField> joinFields = joinRel.getRowType().getFieldList();
    List<RelDataTypeField> leftFields = joinRel.getInputs().get(0).getRowType().getFieldList();
    List<RelDataTypeField> rightFields = joinRel.getInputs().get(1).getRowType().getFieldList();
    RexBuilder rexBuilder = joinRel.getCluster().getRexBuilder();
    RexNode leftPushdownPredicateShifted =
        MoreRelOptUtil.shiftFilter(
            0,
            leftFields.size(),
            0,
            rexBuilder,
            joinFields,
            joinFields.size(),
            leftFields,
            leftPushdownPredicate);
    RexNode rightPushdownPredicateShifted =
        MoreRelOptUtil.shiftFilter(
            leftFields.size(),
            joinFields.size(),
            -leftFields.size(),
            rexBuilder,
            joinFields,
            joinFields.size(),
            rightFields,
            rightPushdownPredicate);

    // If nothing is changed, then no pushdown happens
    if (leftPushdownPredicateShifted.isAlwaysTrue()
        && rightPushdownPredicateShifted.isAlwaysTrue()
        && (newJoinCondition.isAlwaysTrue()
            || (newJoinCondition.equals(inputJoinConditionPruned)
                && remainingFilterCondition.equals(inputFilterConditionPruned)))) {
      return null;
    }

    // Wrap the top filter in a special node that tells the rule not to push it down further
    if (!remainingFilterCondition.isAlwaysTrue()) {
      remainingFilterCondition =
          rexBuilder.makeCall(DO_NOT_PUSH_DOWN_OPERATOR, remainingFilterCondition);
    }

    // Construct the rewritten result
    return relBuilder
        .push(joinRel.getLeft())
        .filter(leftPushdownPredicateShifted) // left child of join
        .push(joinRel.getRight())
        .filter(rightPushdownPredicateShifted) // right child of join
        .join(simplifiedJoinType, newJoinCondition) // join
        .convert(joinRel.getRowType(), false) // project if needed
        .filter(remainingFilterCondition) // remaining filter
        .build();
  }
}
