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

import java.util.List;
import java.util.stream.Collectors;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.rules.TransformationRule;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.util.ImmutableBitSet;

/**
 * If we encounter a Filter on top of a AdjustCountProject rule, then we can remove it, since we
 * know it's not going to clear the correlate relnode. Technically we should do this for every
 * relnode.
 */
public final class FilterAdjustCountProjectRule extends RelRule<FilterAdjustCountProjectRule.Config>
    implements TransformationRule {
  private FilterAdjustCountProjectRule(Config config) {
    super(config);
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    Filter filter = call.rel(0);
    Project project = call.rel(1);

    ImmutableBitSet filterBits = RelOptUtil.InputFinder.bits(filter.getCondition());
    boolean needsRewrite = false;
    for (int index : filterBits.asSet()) {
      if (Utils.hasAdjustCountOperator(project.getProjects().get(index))) {
        needsRewrite = true;
      }
    }

    if (!needsRewrite) {
      return;
    }

    List<RexNode> newProjects =
        project.getProjects().stream()
            .map(
                x ->
                    x.accept(
                        new RexShuttle() {
                          @Override
                          public RexNode visitCall(RexCall call) {
                            RexNode visited = super.visitCall(call);
                            if (visited instanceof RexCall) {
                              RexCall visitedCall = (RexCall) visited;
                              if (visitedCall
                                  .getOperator()
                                  .equals(
                                      AggregateCorrelatedFilterTransposeRule
                                          .ADJUST_COUNT_OPERATOR)) {
                                return visitedCall.getOperands().get(0);
                              }
                            }

                            return visited;
                          }
                        }))
            .collect(Collectors.toList());

    RelNode transformed =
        call.builder()
            .push(project.getInput())
            .project(newProjects, project.getRowType().getFieldNames())
            .filter(filter.getCondition())
            .build();

    call.transformTo(transformed);
  }

  public interface Config extends RelRule.Config {
    Config DEFAULT =
        RelRule.Config.EMPTY
            .withDescription(FilterAdjustCountProjectRule.class.getName())
            .withOperandSupplier(
                op ->
                    op.operand(Filter.class).oneInput(OperandTransformFactory.ADJUST_COUNT_PROJECT))
            .as(Config.class);

    @Override
    default FilterAdjustCountProjectRule toRule() {
      return new FilterAdjustCountProjectRule(this);
    }
  }
}
