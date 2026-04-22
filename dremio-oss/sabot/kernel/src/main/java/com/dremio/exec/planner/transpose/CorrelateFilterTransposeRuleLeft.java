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

import com.google.common.collect.ImmutableList;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Correlate;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.rules.TransformationRule;
import org.apache.calcite.rex.RexNode;

/**
 * This class pulls filters above Correlates, which is useful for reflection matching. It's a copy
 * and past of JoinFilterTransposeRule and won't be needed after we decorrelate in normalization.
 */
public final class CorrelateFilterTransposeRuleLeft
    extends RelRule<CorrelateFilterTransposeRuleLeft.Config> implements TransformationRule {
  private CorrelateFilterTransposeRuleLeft(Config config) {
    super(config);
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    Correlate correlate = call.rel(0);
    Filter filter = call.rel(1);

    RexNode newFilterCondition = filter.getCondition();
    RelNode newLeftChild = filter.getInput();
    RelNode newRightChild = call.rel(2);
    Correlate newCorrelate =
        correlate.copy(correlate.getTraitSet(), ImmutableList.of(newLeftChild, newRightChild));

    RelNode transposed = call.builder().push(newCorrelate).filter(newFilterCondition).build();
    call.transformTo(transposed);
  }

  public interface Config extends RelRule.Config {
    Config DEFAULT =
        EMPTY
            .withOperandSupplier(
                b0 ->
                    b0.operand(Correlate.class)
                        .inputs(
                            b1 -> b1.operand(Filter.class).anyInputs(),
                            b2 -> b2.operand(RelNode.class).anyInputs()))
            .withDescription("CorrelateFilterTransposeRuleLeft")
            .as(CorrelateFilterTransposeRuleLeft.Config.class);

    @Override
    default CorrelateFilterTransposeRuleLeft toRule() {
      return new CorrelateFilterTransposeRuleLeft(this);
    }
  }
}
