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

import java.util.ArrayList;
import java.util.List;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.plan.Strong;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.rules.TransformationRule;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.ImmutableBitSet;

/**
 * Sometimes a filter can not be pulled over a project, since the project does not include all the
 * fields needed to evaluate the condition:
 *
 * <pre>
 *   Project(true, $0, $1, $2)
 *      Filter(foo($3))
 *         RelNode
 * </pre>
 *
 * What we can do is add the missing terms to the project and add a trimming project:
 *
 * <pre>
 *   Project($0, $1, $2, $3)
 *    Filter(foo($4))
 *      Project(true, $0, $1, $2, $3)
 *        RelNode
 * </pre>
 *
 * The reason this is useful is that the top project is now a "trimming project", which has the
 * additional property of being "strong", which is useful for
 *
 * @see com.dremio.exec.planner.transpose.CorrelateProjectTransposeRule
 *     <p>For simplicity, we say a project is "weak" if it can't be pulled over a JOIN or Correlate
 *     relnode.
 */
public final class WeakProjectCorrelatedFilterTransposeRule
    extends RelRule<WeakProjectCorrelatedFilterTransposeRule.Config> implements TransformationRule {
  private WeakProjectCorrelatedFilterTransposeRule(Config config) {
    super(config);
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    Project project = call.rel(0);
    Filter filter = call.rel(1);

    if (Strong.allStrong(project.getProjects())) {
      return;
    }

    ImmutableBitSet projectBits = RelOptUtil.InputFinder.bits(project.getProjects(), null);
    ImmutableBitSet filterBits = RelOptUtil.InputFinder.bits(filter.getCondition());
    ImmutableBitSet missingBits = filterBits.except(projectBits);

    RelBuilder relBuilder = call.builder().push(filter.getInput());
    List<RexNode> projectWithMissingBits = new ArrayList<>(project.getProjects());
    for (int missingBit : missingBits.asSet()) {
      projectWithMissingBits.add(relBuilder.field(missingBit));
    }

    relBuilder.project(projectWithMissingBits);
    RexNode newCondition =
        filter
            .getCondition()
            .accept(
                new RexShuttle() {
                  @Override
                  public RexNode visitInputRef(RexInputRef inputRef) {
                    int index = inputRef.getIndex();
                    // Find this expression in the new project
                    for (int i = 0; i < projectWithMissingBits.size(); i++) {
                      RexNode projectExpr = projectWithMissingBits.get(i);
                      if (projectExpr instanceof RexInputRef) {
                        RexInputRef rexInputRef = (RexInputRef) projectExpr;
                        if (rexInputRef.getIndex() == index) {
                          return relBuilder.field(i);
                        }
                      }
                    }

                    throw new RuntimeException("Could not find new index");
                  }
                });

    relBuilder.filter(newCondition);

    List<RexNode> trimmingProjects = new ArrayList<>();
    for (int i = 0; i < project.getProjects().size(); i++) {
      trimmingProjects.add(relBuilder.field(i));
    }

    relBuilder.project(trimmingProjects, project.getRowType().getFieldNames());

    RelNode transformed = relBuilder.build();
    call.transformTo(transformed);
  }

  public interface Config extends RelRule.Config {
    Config DEFAULT =
        RelRule.Config.EMPTY
            .withDescription(WeakProjectCorrelatedFilterTransposeRule.class.getName())
            .withOperandSupplier(
                op ->
                    op.operand(Project.class)
                        // We don't want to pull a correlated filter above this operator
                        // Otherwise we want decorrelate before rewriting it
                        .predicate(project -> !Utils.hasAdjustCountOperator(project))
                        .oneInput(OperandTransformFactory.CORRELATED_FILTER))
            .as(Config.class);

    @Override
    default WeakProjectCorrelatedFilterTransposeRule toRule() {
      return new WeakProjectCorrelatedFilterTransposeRule(this);
    }
  }
}
