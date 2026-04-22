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
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;

/**
 * Implementation of
 *
 * @see JoinFilterTransposeRuleBase that handles when the filter is on the right hand side of the
 *     join.
 */
public final class JoinFilterTransposeRuleRight extends JoinFilterTransposeRuleBase {
  private JoinFilterTransposeRuleRight(Config config) {
    super(config);
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    Join join = call.rel(0);
    RelNode leftChild = call.rel(1);
    Filter filter = call.rel(2);

    RexNode newFilterCondition = filter.getCondition();
    if (join.getJoinType().generatesNullsOnRight()) {
      Optional<RexNode> withNullHandling =
          handleNullability(newFilterCondition, call.builder().getRexBuilder());
      if (withNullHandling.isEmpty()) {
        return;
      }

      newFilterCondition = withNullHandling.get();
    }

    int nFieldsLeft = leftChild.getRowType().getFieldList().size();
    // Adjust all input references in the right filter condition by the left input field count
    newFilterCondition =
        newFilterCondition.accept(
            new RexShuttle() {
              @Override
              public RexNode visitInputRef(RexInputRef inputRef) {
                // Adjust index by the left input field count
                return new RexInputRef(inputRef.getIndex() + nFieldsLeft, inputRef.getType());
              }
            });

    RelNode newRightChild = filter.getInput();
    RelNode transposed =
        call.builder()
            .push(leftChild)
            .push(newRightChild)
            .join(join.getJoinType(), join.getCondition())
            .filter(newFilterCondition)
            .build();
    call.transformTo(transposed);
  }

  public interface Config extends JoinFilterTransposeRuleBase.Config {
    Config DEFAULT =
        EMPTY
            .withDescription("JoinFilterTransposeRule(Other-Filter)")
            .withOperandSupplier(
                b0 ->
                    b0.operand(Join.class)
                        .inputs(
                            b1 -> b1.operand(RelNode.class).anyInputs(),
                            b2 -> b2.operand(Filter.class).anyInputs()))
            .as(JoinFilterTransposeRuleRight.Config.class);

    @Override
    default JoinFilterTransposeRuleRight toRule() {
      return new JoinFilterTransposeRuleRight(this);
    }
  }
}
