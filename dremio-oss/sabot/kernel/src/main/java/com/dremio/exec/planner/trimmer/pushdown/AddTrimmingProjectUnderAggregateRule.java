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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.ImmutableBitSet;

/**
 * This rule adds a trimming project under an aggregate.
 *
 * <p>For example it rewrites:
 *
 * <pre>
 * LogicalProject($f1=[$1])
 *   LogicalAggregate(group=[{7}], agg#0=[COUNT()], agg#1=[MIN($1)])
 *     LogicalTableScan(table=[[EMP]])
 * </pre>
 *
 * To:
 *
 * <pre>
 * LogicalProject($f1=[$1])
 *   LogicalAggregate(group=[{0}], agg#0=[COUNT(), agg#1=[MIN($1)])
 *     LogicalProject(DEPTNO=[$7], EMPNO=[$1])
 *        LogicalTableScan(table=[[EMP]])
 * </pre>
 *
 * This is useful, since we can't push the project below the aggregation, but we can add a trimming
 * project under the aggregation, which later can be pushed down.
 */
public final class AddTrimmingProjectUnderAggregateRule extends AddTrimmingProjectRule<Aggregate> {
  private AddTrimmingProjectUnderAggregateRule(AddTrimmingProjectUnderAggregateRule.Config config) {
    super(config);
  }

  @Override
  public Set<Integer> extractColumnsUsed(Aggregate aggregate) {
    Set<Integer> columnsUsed = new HashSet<>(aggregate.getGroupSet().asSet());
    for (AggregateCall aggCall : aggregate.getAggCallList()) {
      columnsUsed.addAll(aggCall.getArgList());
    }

    return columnsUsed;
  }

  @Override
  public void addRewrittenNode(
      Aggregate aggregate, RelBuilder relBuilder, Map<Integer, Integer> indexMapping) {
    // Adjust the Aggregate to reference the new indexes from the Project
    ImmutableBitSet newGroupSet =
        ImmutableBitSet.of(
            aggregate.getGroupSet().asSet().stream()
                .map(indexMapping::get)
                .collect(Collectors.toSet()));
    List<ImmutableBitSet> newGroupSets =
        aggregate.getGroupSets().stream()
            .map(
                groupSet ->
                    ImmutableBitSet.of(
                        groupSet.asSet().stream()
                            .map(indexMapping::get)
                            .collect(Collectors.toSet())))
            .collect(Collectors.toList());

    List<AggregateCall> adjustedAggCalls = new ArrayList<>();
    aggregate
        .getAggCallList()
        .forEach(
            aggCall -> {
              List<Integer> newArgList = new ArrayList<>();
              aggCall.getArgList().forEach(arg -> newArgList.add(indexMapping.get(arg)));
              adjustedAggCalls.add(
                  aggCall.copy(newArgList, aggCall.filterArg, aggCall.getCollation()));
            });

    relBuilder.aggregate(relBuilder.groupKey(newGroupSet, newGroupSets), adjustedAggCalls);
  }

  public interface Config extends AddTrimmingProjectRule.Config {
    AddTrimmingProjectUnderAggregateRule.Config DEFAULT =
        RelRule.Config.EMPTY
            .withDescription(AddTrimmingProjectUnderAggregateRule.class.getSimpleName())
            .withOperandSupplier(
                aggregate ->
                    aggregate
                        .operand(Aggregate.class)
                        .oneInput(any -> any.operand(RelNode.class).anyInputs()))
            .as(AddTrimmingProjectRule.Config.class)
            .withHasTopProject(false)
            .as(AddTrimmingProjectUnderAggregateRule.Config.class);

    @Override
    default AddTrimmingProjectUnderAggregateRule toRule() {
      return new AddTrimmingProjectUnderAggregateRule(this);
    }
  }
}
