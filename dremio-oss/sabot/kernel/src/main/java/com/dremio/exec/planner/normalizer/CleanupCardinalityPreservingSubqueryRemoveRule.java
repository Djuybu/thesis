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

import java.util.ArrayList;
import java.util.List;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Correlate;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Values;
import org.apache.calcite.rel.rules.TransformationRule;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;

/**
 * SubqueryRemoveRule sometimes emits no-op join / correlate operations. For example:
 *
 * <pre>
 *   LogicalProject(x=[FLATTEN(ARRAY({
 *    LogicalValues(tuples=[[{ 0 }, { 0 }, { 0 }, { 0 }]])
 *   }))])
 *     LogicalValues(tuples=[[{ 0 }]])
 * </pre>
 *
 * Generates:
 *
 * <pre>
 *   LogicalProject(x=[FLATTEN(CASE(IS NULL($1), EMPTY_ARRAY($1), $1))])
 *   LogicalJoin(condition=[true], joinType=[left])
 *     LogicalValues(tuples=[[{ 0 }]])
 *     LogicalAggregate(group=[{}], collect_to_array_agg=[ARRAY_AGG($0)])
 *       LogicalValues(tuples=[[{ 0 }, { 0 }, { 0 }, { 0 }]])
 * </pre>
 *
 * Which could just be:
 *
 * <pre>
 *   LogicalProject(x=[FLATTEN(CASE(IS NULL($0), EMPTY_ARRAY($0), $0))])
 *     LogicalAggregate(group=[{}], collect_to_array_agg=[ARRAY_AGG($0)])
 *       LogicalValues(tuples=[[{ 0 }, { 0 }, { 0 }, { 0 }]])
 * </pre>
 */
public final class CleanupCardinalityPreservingSubqueryRemoveRule
    extends RelRule<CleanupCardinalityPreservingSubqueryRemoveRule.Config>
    implements TransformationRule {

  private CleanupCardinalityPreservingSubqueryRemoveRule(
      CleanupCardinalityPreservingSubqueryRemoveRule.Config config) {
    super(config);
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    Project project = call.rel(0);
    RelNode biRel = call.rel(1);
    Values values = call.rel(2);
    Aggregate right = call.rel(3);

    int leftFieldCount = values.getRowType().getFieldCount();
    if (!projectOnlyReferencesRightChild(project, leftFieldCount)) {
      return;
    }

    List<RexNode> adjustedProjects =
        adjustProjectReferences(
            project.getProjects(), leftFieldCount, call.builder().getRexBuilder(), right);

    RelNode prunedRelNode =
        call.builder()
            .push(right)
            .project(adjustedProjects)
            // The nullability of the aggregate might be off, since we trimmed off the JOIN
            // Also the field names need to be preserved
            .convert(project.getRowType(), true)
            .build();

    call.transformTo(prunedRelNode);
  }

  private boolean projectOnlyReferencesRightChild(Project project, int leftFieldCount) {
    List<RexNode> projectExpressions = project.getProjects();
    for (RexNode expr : projectExpressions) {
      if (!expressionOnlyReferencesRightChild(expr, leftFieldCount)) {
        return false;
      }
    }
    return true;
  }

  private boolean expressionOnlyReferencesRightChild(RexNode expr, int leftFieldCount) {
    List<RexInputRef> inputRefs = RexInputRefCollector.findAllInputRefs(expr);
    return inputRefs.stream().allMatch(inputRef -> inputRef.getIndex() >= leftFieldCount);
  }

  private List<RexNode> adjustProjectReferences(
      List<RexNode> projects, int leftFieldCount, RexBuilder rexBuilder, RelNode right) {
    List<RexNode> adjustedProjects = new ArrayList<>();
    for (RexNode projectExpr : projects) {
      RexNode adjustedExpr =
          projectExpr.accept(
              new RexShuttle() {
                @Override
                public RexNode visitInputRef(RexInputRef inputRef) {
                  // Adjust the index to remove the offset from the left child
                  int newIndex = inputRef.getIndex() - leftFieldCount;
                  return rexBuilder.makeInputRef(right, newIndex);
                }
              });
      adjustedProjects.add(adjustedExpr);
    }
    return adjustedProjects;
  }

  public interface Config extends RelRule.Config {
    Config CORRELATE =
        EMPTY
            .withOperandSupplier(
                b ->
                    b.operand(Project.class)
                        .oneInput(
                            b1 ->
                                b1.operand(Correlate.class)
                                    .predicate(
                                        correlate -> correlate.getJoinType() == JoinRelType.LEFT)
                                    .inputs(
                                        b2 ->
                                            b2.operand(Values.class)
                                                .predicate(values -> values.getTuples().size() == 1)
                                                .noInputs(),
                                        b3 -> b3.operand(Aggregate.class).anyInputs())))
            .as(Config.class);

    Config JOIN =
        EMPTY
            .withOperandSupplier(
                b ->
                    b.operand(Project.class)
                        .oneInput(
                            b1 ->
                                b1.operand(Join.class)
                                    .predicate(
                                        join ->
                                            join.getJoinType() == JoinRelType.LEFT
                                                && join.getCondition().isAlwaysTrue())
                                    .inputs(
                                        b2 ->
                                            b2.operand(Values.class)
                                                .predicate(values -> values.getTuples().size() == 1)
                                                .noInputs(),
                                        b3 -> b3.operand(Aggregate.class).anyInputs())))
            .as(Config.class);

    @Override
    default CleanupCardinalityPreservingSubqueryRemoveRule toRule() {
      return new CleanupCardinalityPreservingSubqueryRemoveRule(this);
    }
  }

  private static class RexInputRefCollector extends RexShuttle {
    private final List<RexInputRef> inputRefs = new ArrayList<>();

    @Override
    public RexNode visitInputRef(RexInputRef inputRef) {
      inputRefs.add(inputRef);
      return inputRef; // Continue the traversal
    }

    public static List<RexInputRef> findAllInputRefs(RexNode rexNode) {
      RexInputRefCollector collector = new RexInputRefCollector();
      rexNode.accept(collector);
      return collector.inputRefs;
    }
  }
}
