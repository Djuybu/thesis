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
import java.util.List;
import java.util.Set;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.rules.TransformationRule;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.tools.RelBuilder;

/**
 * If there is a trimming project on top of a RelNode that can not be transposed we can still remove
 * the unused fields as a performance optimization.
 *
 * <p>For example:
 *
 * <pre>
 * LogicalProject($f1=[$1])
 *   LogicalAggregate(group=[{7}], agg#0=[COUNT()], agg#1=[MIN($1)])
 *     LogicalTableScan(table=[[EMP]])
 * </pre>
 *
 * can be rewritten to:
 *
 * <pre>
 *  LogicalProject($f1=[$1])
 *    LogicalAggregate(group=[{7}], agg#0=[COUNT()])
 *      LogicalTableScan(table=[[EMP]])
 * </pre>
 *
 * Notice that MIN($1) was trimmed off.
 */
public abstract class TrimRelNodeRule<R extends RelNode> extends RelRule<TrimRelNodeRule.Config>
    implements TransformationRule {
  protected TrimRelNodeRule(TrimRelNodeRule.Config config) {
    super(config);
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    Project project = call.rel(0);
    R relNode = call.rel(1);
    RelNode input = call.rel(2);

    Set<Integer> projectRefs = RelOptUtil.InputFinder.bits(project.getProjects(), null).asSet();
    RelBuilder relBuilder = call.builder().push(input);

    Set<Integer> indexesRemoved = pushTrimmedRelNode(projectRefs, relNode, input, relBuilder);
    if (indexesRemoved.isEmpty()) {
      return;
    }

    // Create a new Project to map the input references to the new Aggregate
    List<RexNode> newProjects = new ArrayList<>();
    for (RexNode projectExpr : project.getProjects()) {
      RexNode newProjectExpr =
          projectExpr.accept(
              new RexShuttle() {
                @Override
                public RexNode visitInputRef(RexInputRef inputRef) {
                  int adjustedIndex = inputRef.getIndex();
                  for (int removedIndex : indexesRemoved) {
                    if (removedIndex <= adjustedIndex) {
                      adjustedIndex--;
                    }
                  }

                  return relBuilder.field(adjustedIndex);
                }
              });

      newProjects.add(newProjectExpr);
    }

    // Add the final project on top of the new aggregate
    relBuilder.project(newProjects, project.getRowType().getFieldNames());

    RelNode transformed = relBuilder.build();
    call.transformTo(transformed);
  }

  /**
   * Implemented by the concrete class and pushes on the relnode with all unused fields trimmed off.
   *
   * @param projectRefs the indexes used by the project
   * @param relNode the relnode the trim
   * @param input the input of the relnode
   * @param relBuilder the relbuilder with the input already pushed on
   * @return The set of indexes that were trimmed off, so we can later rewrite the project.
   */
  public abstract Set<Integer> pushTrimmedRelNode(
      Set<Integer> projectRefs, R relNode, RelNode input, RelBuilder relBuilder);

  public interface Config extends RelRule.Config {
    TrimRelNodeRule.Config DEFAULT =
        RelRule.Config.EMPTY
            .withDescription(TrimRelNodeRule.class.getSimpleName())
            .as(TrimRelNodeRule.Config.class);
  }
}
