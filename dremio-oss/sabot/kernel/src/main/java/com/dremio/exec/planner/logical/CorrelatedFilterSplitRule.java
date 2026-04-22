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

import java.util.List;
import java.util.stream.Collectors;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.sql.SqlKind;

/**
 * Rule that splits an AND filter with correlated and uncorrelated components and splits them into
 * two seperate filters. This is useful in scenarios where we want to push down the uncorrealted
 * filter, but not hte correalted filter.
 */
public final class CorrelatedFilterSplitRule extends RelRule<RelRule.Config> {
  private CorrelatedFilterSplitRule(RelRule.Config config) {
    super(config);
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    Filter filter = call.rel(0);
    List<RexNode> conjunctions = RelOptUtil.conjunctions(filter.getCondition());
    List<RexNode> correlatedConjunctions =
        conjunctions.stream().filter(RexUtil::containsCorrelation).collect(Collectors.toList());
    List<RexNode> uncorrelatedConjunctions =
        conjunctions.stream()
            .filter(conjunction -> !RexUtil.containsCorrelation(conjunction))
            .collect(Collectors.toList());

    // Nothing to split off
    if (uncorrelatedConjunctions.isEmpty()) {
      return;
    }

    org.apache.calcite.tools.RelBuilder relBuilder = call.builder();
    RexBuilder rexBuilder = relBuilder.getRexBuilder();
    RexNode correlatedFilterCondition =
        RexUtil.composeConjunction(rexBuilder, correlatedConjunctions);
    RexNode uncorrelatedFilterCondition =
        RexUtil.composeConjunction(rexBuilder, uncorrelatedConjunctions);

    RelNode rewrittenNode =
        relBuilder
            .push(filter.getInput())
            .filter(uncorrelatedFilterCondition)
            .filter(correlatedFilterCondition)
            .build();

    call.transformTo(rewrittenNode);
  }

  public interface Config extends RelRule.Config {
    Config DEFAULT =
        Config.EMPTY
            .withDescription("CorrelatedFilterSplitRule")
            .withOperandSupplier(
                op ->
                    op.operand(Filter.class)
                        .predicate(
                            filter ->
                                filter.getCondition().isA(SqlKind.AND)
                                    && RexUtil.containsCorrelation(filter.getCondition()))
                        .anyInputs())
            .as(Config.class);

    @Override
    default CorrelatedFilterSplitRule toRule() {
      return new CorrelatedFilterSplitRule(this);
    }
  }
}
