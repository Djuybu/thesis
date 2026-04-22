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
package com.dremio.exec.planner.transpose;

import java.util.Optional;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rex.RexNode;

/**
 * Implementation of
 *
 * @see JoinFilterTransposeRuleBase that handles when the filter is on the left hand side of the
 *     join.
 */
public final class JoinFilterTransposeRuleLeft extends JoinFilterTransposeRuleBase {
  private JoinFilterTransposeRuleLeft(Config config) {
    super(config);
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    Join join = call.rel(0);
    Filter filter = call.rel(1);
    RelNode rightChild = call.rel(2);

    RexNode newFilterCondition = filter.getCondition();
    if (join.getJoinType().generatesNullsOnLeft()) {
      Optional<RexNode> withNullHandling =
          handleNullability(newFilterCondition, call.builder().getRexBuilder());
      if (withNullHandling.isEmpty()) {
        return;
      }

      newFilterCondition = withNullHandling.get();
    }

    RelNode transformed =
        call.builder()
            .push(filter.getInput())
            .push(rightChild)
            .join(join.getJoinType(), join.getCondition())
            .filter(newFilterCondition)
            .build();

    call.transformTo(transformed);
  }

  public interface Config extends JoinFilterTransposeRuleBase.Config {
    Config DEFAULT =
        JoinFilterTransposeRuleBase.Config.DEFAULT
            .withDescription("JoinFilterTransposeRule(Filter-Other)")
            .withOperandSupplier(
                b0 ->
                    b0.operand(Join.class)
                        .inputs(
                            b1 -> b1.operand(Filter.class).anyInputs(),
                            b2 -> b2.operand(RelNode.class).anyInputs()))
            .as(Config.class);

    @Override
    default JoinFilterTransposeRuleLeft toRule() {
      return new JoinFilterTransposeRuleLeft(this);
    }
  }
}
