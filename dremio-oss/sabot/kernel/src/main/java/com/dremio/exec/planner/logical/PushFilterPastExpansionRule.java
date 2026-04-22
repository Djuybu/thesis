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
package com.dremio.exec.planner.logical;

import com.dremio.exec.planner.acceleration.ExpansionNode;
import java.util.ArrayList;
import java.util.List;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelOptUtil.VariableUsedVisitor;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;

public class PushFilterPastExpansionRule extends RelOptRule {
  public static final RelOptRule INSTANCE = new PushFilterPastExpansionRule();

  private PushFilterPastExpansionRule() {
    super(RelOptHelper.any(Filter.class, ExpansionNode.class), "PushFilterPastExpansionRule");
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    final Filter filter = call.rel(0);
    final ExpansionNode expansionNode = call.rel(1);
    List<RexNode> expansionFilters = new ArrayList<>();
    List<RexNode> topFilters = new ArrayList<>();

    // Do not push filter with correlate id past an expansion node.
    for (RexNode pred : RelOptUtil.conjunctions(filter.getCondition())) {
      VariableUsedVisitor variableUsedVisitor = new VariableUsedVisitor(null);
      pred.accept(variableUsedVisitor);
      if (variableUsedVisitor.variableFields.isEmpty()) {
        expansionFilters.add(pred);
      } else {
        topFilters.add(pred);
      }
    }

    // Return if nothing to push.
    if (expansionFilters.isEmpty()) {
      return;
    }

    ExpansionNode expansionNodeWithFilter =
        expansionNode
            .copy(
                expansionNode.getTraitSet(),
                filter.copy(filter.getTraitSet(), expansionNode.getInputs()),
                expansionFilters)
            .considerForPullUpPredicate(true);

    if (topFilters.isEmpty()) {
      call.transformTo(expansionNodeWithFilter);
    } else {
      call.transformTo(
          LogicalFilter.create(
              expansionNodeWithFilter,
              RexUtil.composeConjunction(expansionNode.getCluster().getRexBuilder(), topFilters)));
    }
  }
}
