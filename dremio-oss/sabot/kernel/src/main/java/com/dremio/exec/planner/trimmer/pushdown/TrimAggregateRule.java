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
import java.util.Set;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.tools.RelBuilder;

/**
 * This rule detects a project on top of an aggregate and trims out any aggregate calls not
 * referenced by the project.
 *
 * <p>For example it rewrites:
 *
 * <pre>
 * LogicalProject($f1=[$1])
 *   LogicalAggregate(group=[{7}], agg#0=[COUNT()], agg#1=[MIN($1)])
 *     LogicalTableScan(table=[[EMP]])
 * </pre>
 *
 * to:
 *
 * <pre>
 *  LogicalProject($f1=[$1])
 *    LogicalAggregate(group=[{7}], agg#0=[COUNT()])
 *      LogicalTableScan(table=[[EMP]])
 * </pre>
 *
 * Notice that MIN($1) was trimmed off.
 */
public final class TrimAggregateRule extends TrimRelNodeRule<Aggregate> {
  private TrimAggregateRule(TrimAggregateRule.Config config) {
    super(config);
  }

  @Override
  public Set<Integer> pushTrimmedRelNode(
      Set<Integer> projectRefs, Aggregate aggregate, RelNode input, RelBuilder relBuilder) {
    List<AggregateCall> aggregateCalls = new ArrayList<>();
    Set<Integer> removedIndexes = new HashSet<>();

    for (int i = 0; i < aggregate.getAggCallList().size(); i++) {
      AggregateCall aggregateCall = aggregate.getAggCallList().get(i);
      int index = i + aggregate.getGroupCount();
      if (projectRefs.contains(index)) {
        aggregateCalls.add(aggregateCall);
      } else {
        removedIndexes.add(index);
      }
    }

    relBuilder.aggregate(relBuilder.groupKey(aggregate.getGroupSet()), aggregateCalls);

    return removedIndexes;
  }

  public interface Config extends TrimRelNodeRule.Config {
    TrimAggregateRule.Config DEFAULT =
        TrimRelNodeRule.Config.DEFAULT
            .withDescription(TrimAggregateRule.class.getSimpleName())
            .withOperandSupplier(
                op ->
                    op.operand(Project.class)
                        .oneInput(
                            aggregate ->
                                aggregate
                                    .operand(Aggregate.class)
                                    .oneInput(any -> any.operand(RelNode.class).anyInputs())))
            .as(TrimAggregateRule.Config.class);

    @Override
    default TrimAggregateRule toRule() {
      return new TrimAggregateRule(this);
    }
  }
}
