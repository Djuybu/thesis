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
package com.dremio.exec.planner.physical;

import static com.dremio.exec.planner.ResultWriterUtils.buildWriterOptions;
import static com.dremio.exec.planner.physical.PlannerSettings.CTAS_ROUND_ROBIN;

import com.dremio.exec.planner.logical.Rel;
import com.dremio.exec.planner.logical.drel.LogicalResultWriterRel;
import com.dremio.options.OptionResolver;
import com.google.common.base.Preconditions;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.util.ImmutableBeans;

public class ResultWriterPrule extends RelRule<ResultWriterPrule.Config> {

  private final OptionResolver optionResolver;

  protected ResultWriterPrule(Config config) {
    super(config);
    this.optionResolver = Preconditions.checkNotNull(config.optionResolver(), "options");
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    final LogicalResultWriterRel writer = call.rel(0);
    final RelNode input = writer.getInput();

    final RelTraitSet writerTraits = inferWriterTraits(input);
    final RelNode convertedInput = convert(input, writerTraits);

    if (!new WriteTraitPull(call, writerTraits).go(writer, convertedInput)) {
      call.transformTo(convertWriter(writer, writerTraits, convertedInput));
    }
  }

  private class WriteTraitPull extends SubsetTransformer<LogicalResultWriterRel, RuntimeException> {
    final RelTraitSet writerTraits;

    public WriteTraitPull(RelOptRuleCall call, RelTraitSet writerTraits) {
      super(call);
      this.writerTraits = writerTraits;
    }

    @Override
    public RelNode convertChild(LogicalResultWriterRel writer, RelNode rel)
        throws RuntimeException {
      return convertWriter(writer, writerTraits, rel);
    }
  }

  private RelNode convertWriter(
      LogicalResultWriterRel writerRel, RelTraitSet writerTraits, RelNode convertedInput) {
    final RelTraitSet commiterTraits =
        writerRel.getTraitSet().plus(DistributionTrait.SINGLETON).plus(Prel.PHYSICAL);

    ResultWriterPrel writerPrel =
        new ResultWriterPrel(writerRel.getCluster(), writerTraits, convertedInput);
    RelNode convertedNode = convert(writerPrel, commiterTraits);
    return new ResultWriterCommiterPrel(writerPrel.getCluster(), commiterTraits, convertedNode);
  }

  private RelTraitSet inferWriterTraits(RelNode input) {
    final boolean addRoundRobin = optionResolver.getOption(CTAS_ROUND_ROBIN);
    return buildWriterOptions(optionResolver)
        .inferTraits(input.getTraitSet(), input.getRowType(), addRoundRobin);
  }

  public interface Config extends RelRule.Config {
    Config DEFAULT =
        EMPTY
            .withDescription("Prel.ResultWriterPrule")
            .withOperandSupplier(
                ot -> ot.operand(LogicalResultWriterRel.class).trait(Rel.LOGICAL).anyInputs())
            .as(Config.class);

    @ImmutableBeans.Property
    OptionResolver optionResolver();

    Config withOptionResolver(OptionResolver factory);

    @Override
    default RelOptRule toRule() {
      return new ResultWriterPrule(this);
    }
  }
}
