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

import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.rules.JoinProjectTransposeRule;

/** Calcite's variant has a bug, so this class exists as a workaround */
public final class JoinProjectTransposeRuleLeft extends JoinProjectTransposeRule {
  private JoinProjectTransposeRuleLeft(Config config) {
    super(config);
  }

  @Override
  protected boolean hasLeftChild(RelOptRuleCall call) {
    return true;
  }

  @Override
  protected boolean hasRightChild(RelOptRuleCall call) {
    return false;
  }

  public interface Config extends JoinProjectTransposeRule.Config {
    Config DEFAULT =
        JoinProjectTransposeRule.Config.LEFT_OUTER
            .withDescription("JoinProjectTransposeRule(Project-Other)")
            .withOperandSupplier(
                b0 ->
                    b0.operand(Join.class)
                        .inputs(
                            b1 -> b1.operand(Project.class).anyInputs(),
                            b2 -> b2.operand(RelNode.class).anyInputs()))
            .as(Config.class);

    @Override
    default JoinProjectTransposeRuleLeft toRule() {
      return new JoinProjectTransposeRuleLeft(this);
    }
  }
}
