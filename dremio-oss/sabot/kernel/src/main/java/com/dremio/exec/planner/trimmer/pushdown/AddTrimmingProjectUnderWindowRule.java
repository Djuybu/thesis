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
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Window;
import org.apache.calcite.rel.core.Window.RexWinAggCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.ImmutableBitSet;

/**
 * This rule adds a trimming project under a window node that has a project on top of it. For
 * example, it rewrites
 *
 * <pre>
 * LogicalProject(JOB=[$2])
 *   LogicalWindow(window0=[$1])
 *     LogicalTableScan(table=[[EMP]])
 * </pre>
 *
 * To:
 *
 * <pre>
 *  LogicalProject(JOB=[$0])
 *    LogicalWindow(window0=[$1])
 *      LogicalProject(JOB=[$2], ENAME=[$1])
 *        LogicalTableScan(table=[[EMP]])
 * </pre>
 *
 * This is useful, since we can't transpose the top project with the window, but we can add a
 * trimming project that can later be pushed down.
 */
public final class AddTrimmingProjectUnderWindowRule extends AddTrimmingProjectRule<Window> {
  private AddTrimmingProjectUnderWindowRule(AddTrimmingProjectUnderWindowRule.Config config) {
    super(config);
  }

  @Override
  public Set<Integer> extractColumnsUsed(Window window) {
    Set<Integer> groupColumns = new HashSet<>();
    for (Window.Group group : window.groups) {
      groupColumns.addAll(group.keys.asSet());
      groupColumns.addAll(
          group.orderKeys.getFieldCollations().stream()
              .map(RelFieldCollation::getFieldIndex)
              .collect(Collectors.toSet()));
      for (RexWinAggCall aggCall : group.aggCalls) {
        groupColumns.addAll(RelOptUtil.InputFinder.bits(aggCall.operands, null).asSet());
      }
    }

    return groupColumns;
  }

  @Override
  public void addRewrittenNode(
      Window window, RelBuilder relBuilder, Map<Integer, Integer> indexMapping) {
    List<Window.Group> newGroups = rewriteGroups(window, relBuilder, indexMapping);
    WindowRelBuilder.window(relBuilder, window.constants, newGroups);
  }

  private static List<Window.Group> rewriteGroups(
      Window window, RelBuilder relBuilder, Map<Integer, Integer> indexMapping) {
    List<Window.Group> newGroups = new ArrayList<>();
    for (Window.Group group : window.groups) {
      ImmutableBitSet newKeys =
          ImmutableBitSet.of(
              group.keys.asSet().stream().map(indexMapping::get).collect(Collectors.toSet()));
      RelCollation newRelCollation =
          RelCollations.of(
              group.orderKeys.getFieldCollations().stream()
                  .map(
                      collation ->
                          collation.withFieldIndex(indexMapping.get(collation.getFieldIndex())))
                  .collect(Collectors.toList()));
      List<RexWinAggCall> newAggCalls =
          group.aggCalls.stream()
              .map(
                  aggCall ->
                      new RexWinAggCall(
                          (SqlAggFunction) aggCall.getOperator(),
                          aggCall.getType(),
                          aggCall.operands.stream()
                              .map(
                                  x ->
                                      x.accept(
                                          new RexShuttle() {
                                            @Override
                                            public RexNode visitInputRef(RexInputRef inputRef) {
                                              return relBuilder.field(
                                                  indexMapping.get(inputRef.getIndex()));
                                            }
                                          }))
                              .collect(Collectors.toList()),
                          aggCall.ordinal,
                          aggCall.distinct,
                          aggCall.ignoreNulls))
              .collect(Collectors.toList());
      Window.Group newGroup =
          new Window.Group(
              newKeys,
              group.isRows,
              group.lowerBound,
              group.upperBound,
              newRelCollation,
              newAggCalls);
      newGroups.add(newGroup);
    }

    return newGroups;
  }

  public interface Config extends AddTrimmingProjectRule.Config {
    AddTrimmingProjectUnderWindowRule.Config DEFAULT =
        EMPTY
            .withDescription(AddTrimmingProjectUnderWindowRule.class.getSimpleName())
            .withOperandSupplier(
                project ->
                    project
                        .operand(Project.class)
                        .oneInput(
                            window ->
                                window
                                    .operand(Window.class)
                                    .oneInput(any -> any.operand(RelNode.class).anyInputs())))
            .as(AddTrimmingProjectRule.Config.class)
            .withHasTopProject(true)
            .as(AddTrimmingProjectUnderWindowRule.Config.class);

    @Override
    default AddTrimmingProjectUnderWindowRule toRule() {
      return new AddTrimmingProjectUnderWindowRule(this);
    }
  }
}
