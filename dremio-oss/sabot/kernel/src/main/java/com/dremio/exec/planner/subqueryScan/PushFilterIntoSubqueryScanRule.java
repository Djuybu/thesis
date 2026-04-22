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
package com.dremio.exec.planner.subqueryScan;

import com.google.common.base.Preconditions;
import java.util.Optional;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.rules.TransformationRule;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.ImmutableBeans;

public final class PushFilterIntoSubqueryScanRule
    extends RelRule<PushFilterIntoSubqueryScanRule.Config> implements TransformationRule {
  private PushFilterIntoSubqueryScanRule(Config config) {
    super(config);
    Preconditions.checkNotNull(config.strategyProvider());
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    Filter filter = call.rel(0);
    SubqueryScan subqueryScan = call.rel(1);
    RelBuilder relBuilder = call.builder();

    Optional<RelNode> optionalResult =
        config
            .strategyProvider()
            .provideStrategy(subqueryScan)
            .tryPush(filter, subqueryScan, relBuilder);
    if (optionalResult.isEmpty()) {
      return;
    }

    call.transformTo(optionalResult.get());
  }

  public interface Config extends RelRule.Config {
    Config DEFAULT =
        EMPTY
            .withDescription(PushFilterIntoSubqueryScanRule.class.getSimpleName())
            .withOperandSupplier(
                filter ->
                    filter
                        .operand(Filter.class)
                        .oneInput(tableScan -> tableScan.operand(SubqueryScan.class).noInputs()))
            .as(PushFilterIntoSubqueryScanRule.Config.class);

    @ImmutableBeans.Property
    StrategyProvider strategyProvider();

    Config withStrategyProvider(StrategyProvider strategyProvider);

    @Override
    default PushFilterIntoSubqueryScanRule toRule() {
      return new PushFilterIntoSubqueryScanRule(this);
    }
  }

  public interface StrategyProvider {
    Strategy NOT_SUPPORTED =
        new Strategy() {
          @Override
          public Optional<RelNode> tryPush(
              Filter filter, SubqueryScan subqueryScan, RelBuilder relBuilder) {
            return Optional.empty();
          }
        };

    Strategy provideStrategy(SubqueryScan subqueryScan);

    @FunctionalInterface
    interface Strategy {
      Optional<RelNode> tryPush(Filter filter, SubqueryScan subqueryScan, RelBuilder relBuilder);
    }
  }
}
