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

import com.dremio.exec.planner.sql.SqlOperands;
import com.dremio.exec.planner.sql.SqlOperatorBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.rules.TransformationRule;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.ImmutableBitSet;

/**
 * Special rule for pulling up a correlated filter over an aggregate. Normally you can not pull up a
 * filter over an aggregate, but you can in the case of correlated filters.
 *
 * <p>This rule converts:
 *
 * <pre>
 *   LogicalCorrelate(correlation=[$cor0], joinType=[left], requiredColumns=[{0}])
 *    LogicalTableScan(table=[[DEPT]])
 *    LogicalAggregate(group=[{0, 1}], agg#0=[MIN($0)], agg#1=[MAX($1)])
 *      LogicalFilter(condition=[=($cor0.DEPTNO, $7)])
 *        LogicalTableScan(table=[[EMP]])
 * </pre>
 *
 * To:
 *
 * <pre>
 *   LogicalCorrelate(correlation=[$cor0], joinType=[left], requiredColumns=[{0}])
 *    LogicalTableScan(table=[[DEPT]])
 *    LogicalProject(EMPNO=[$0], ENAME=[$1], $f2=[$3], $f3=[$4])
 *      LogicalFilter(condition=[=($cor0.DEPTNO, $2)])
 *        LogicalAggregate(group=[{0, 1, 7}], agg#0=[MIN($0)], agg#1=[MAX($1)])
 *          LogicalTableScan(table=[[EMP]])
 * </pre>
 *
 * Notice we are adding the RexInputRef of the correlated filter into the grouping set, so that we
 * can later add filter on it in the transposed correlated filter (which will eventually go into the
 * correlate to convert to a normal JOIN).
 */
public final class AggregateCorrelatedFilterTransposeRule
    extends RelRule<AggregateCorrelatedFilterTransposeRule.Config> implements TransformationRule {
  private AggregateCorrelatedFilterTransposeRule(Config config) {
    super(config);
  }

  /**
   * Special operator for encapsulating the coercion of null to zero for COUNT aggregates. This is
   * to avoid the rule from being simplified out by relbuilder or other rules. We eventually expand
   * it out in the last minute before decorrelation.
   */
  public static final SqlOperator ADJUST_COUNT_OPERATOR =
      SqlOperatorBuilder.name("ADJUST_COUNT")
          .returnType(ReturnTypes.ARG0)
          .operandTypes(SqlOperands.ANY)
          .build();

  @Override
  public void onMatch(RelOptRuleCall call) {
    final Aggregate aggregate = call.rel(0);
    final Filter filter = call.rel(1);

    if (aggregate.getGroupSets().size() > 1) {
      return;
    }

    final RelBuilder relBuilder = call.builder();

    relBuilder.push(filter.getInput());
    pushNewAggregate(relBuilder, aggregate, filter);
    pushNewFilter(relBuilder, aggregate, filter);
    handleCountAggregates(relBuilder, aggregate, filter);
    addTrimmingProjects(relBuilder, aggregate, filter);

    // We have to add this cast due to the group key changing from empty to non empty
    relBuilder.convert(aggregate.getRowType(), true);

    RelNode transformed = relBuilder.build();
    call.transformTo(transformed);
  }

  private static Set<Integer> computeNewGroupKeys(Aggregate aggregate, Filter filter) {
    Set<Integer> refIndexes = new HashSet<>(aggregate.getGroupSet().asSet());
    refIndexes.addAll(RelOptUtil.InputFinder.bits(filter.getCondition()).asList());
    return refIndexes;
  }

  private static RelBuilder pushNewAggregate(
      RelBuilder relBuilder, Aggregate aggregate, Filter filter) {
    // We need to push the aggregate with all the correlated variables added to the grouping fields
    Set<Integer> newGroupKeys = computeNewGroupKeys(aggregate, filter);
    List<AggregateCall> adaptedAggregateCalls =
        aggregate.getAggCallList().stream()
            .map(
                aggregateCall ->
                    aggregateCall.adaptTo(
                        filter.getInput(),
                        aggregateCall.getArgList(),
                        aggregateCall.filterArg,
                        aggregate.getGroupCount(),
                        newGroupKeys.size()))
            .collect(Collectors.toList());
    return relBuilder.aggregate(
        relBuilder.groupKey(ImmutableBitSet.of(newGroupKeys)), adaptedAggregateCalls);
  }

  private static RelBuilder pushNewFilter(
      RelBuilder relBuilder, Aggregate aggregate, Filter filter) {
    // We need to reindex the filter to reference the newly added group keys
    Set<Integer> newGroupKeys = computeNewGroupKeys(aggregate, filter);
    RexNode updatedCondition = computeNewFilterCondition(filter.getCondition(), newGroupKeys);
    return relBuilder.filter(updatedCondition);
  }

  private static RexNode computeNewFilterCondition(RexNode condition, Set<Integer> newGroupKeys) {
    List<Integer> newSortedGroupKeys = newGroupKeys.stream().sorted().collect(Collectors.toList());

    // Update the filter condition to reference new indexes in the aggregate
    final RexNode updatedCondition =
        condition.accept(
            new RexShuttle() {
              @Override
              public RexNode visitInputRef(RexInputRef inputRef) {
                int oldIndex = inputRef.getIndex();
                int newIndex = newSortedGroupKeys.indexOf(oldIndex);
                if (newIndex >= 0) {
                  return new RexInputRef(newIndex, inputRef.getType());
                }
                return inputRef;
              }
            });

    return updatedCondition;
  }

  private static RelBuilder handleCountAggregates(
      RelBuilder relBuilder, Aggregate aggregate, Filter filter) {
    if (!aggregate.getAggCallList().stream()
        .anyMatch(aggCall -> aggCall.getAggregation().kind == SqlKind.COUNT)) {
      return relBuilder;
    }

    Set<Integer> newGroupKeys = computeNewGroupKeys(aggregate, filter);
    // We need to add a case statement to account for the null vs zero change
    // due to decorrelation
    // This logic might be better to have in expansion
    List<RexNode> projects = new ArrayList<>();
    for (int i = 0; i < newGroupKeys.size(); i++) {
      projects.add(relBuilder.field(i));
    }

    for (int i = 0; i < aggregate.getAggCallList().size(); i++) {
      AggregateCall aggregateCall = aggregate.getAggCallList().get(i);
      RexNode field = relBuilder.field(i + newGroupKeys.size());
      if (aggregateCall.getAggregation().kind == SqlKind.COUNT) {
        field = relBuilder.getRexBuilder().makeCall(ADJUST_COUNT_OPERATOR, field);
      }

      projects.add(field);
    }

    return relBuilder.project(projects);
  }

  private static RelBuilder addTrimmingProjects(
      RelBuilder relBuilder, Aggregate aggregate, Filter filter) {
    Set<Integer> newGroupKeys = computeNewGroupKeys(aggregate, filter);
    List<Integer> newSortedGroupKeys = newGroupKeys.stream().sorted().collect(Collectors.toList());

    List<RexNode> trimmingProjects = new ArrayList<>();
    for (Integer oldIndex :
        aggregate.getGroupSet().asList().stream().sorted().collect(Collectors.toList())) {
      int newIndex = newSortedGroupKeys.indexOf(oldIndex);
      trimmingProjects.add(relBuilder.field(newIndex));
    }

    for (int i = newGroupKeys.size(); i < relBuilder.peek().getRowType().getFieldCount(); i++) {
      trimmingProjects.add(relBuilder.field(i));
    }

    return relBuilder.project(trimmingProjects, aggregate.getRowType().getFieldNames());
  }

  public interface Config extends RelRule.Config {
    Config DEFAULT =
        EMPTY
            .withDescription(AggregateCorrelatedFilterTransposeRule.class.getSimpleName())
            .withOperandSupplier(OperandTransformFactory.single(Aggregate.class).correlatedFilter())
            .as(AggregateCorrelatedFilterTransposeRule.Config.class);

    @Override
    default AggregateCorrelatedFilterTransposeRule toRule() {
      return new AggregateCorrelatedFilterTransposeRule(this);
    }
  }
}
