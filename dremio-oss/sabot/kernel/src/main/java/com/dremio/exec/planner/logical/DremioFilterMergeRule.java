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

import com.dremio.exec.planner.common.MoreRelOptUtil;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.rules.SubstitutionRule;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexProgram;
import org.apache.calcite.rex.RexProgramBuilder;
import org.apache.calcite.tools.RelBuilder;

/** Forked from Calcite with cannolization of rex literals to avoid baseline regressions */
public class DremioFilterMergeRule extends RelRule<DremioFilterMergeRule.Config>
    implements SubstitutionRule {

  // ~ Constructors -----------------------------------------------------------

  /** Creates a FilterMergeRule. */
  protected DremioFilterMergeRule(Config config) {
    super(config);
  }

  // ~ Methods ----------------------------------------------------------------

  @Override
  public void onMatch(RelOptRuleCall call) {
    final Filter topFilter = call.rel(0);
    final Filter bottomFilter = call.rel(1);

    // use RexPrograms to merge the two FilterRels into a single program
    // so we can convert the two LogicalFilter conditions to directly
    // reference the bottom LogicalFilter's child
    RexBuilder rexBuilder = topFilter.getCluster().getRexBuilder();
    RexProgram bottomProgram = createProgram(bottomFilter);
    RexProgram topProgram = createProgram(topFilter);

    RexProgram mergedProgram =
        RexProgramBuilder.mergePrograms(topProgram, bottomProgram, rexBuilder);

    RexNode newCondition = mergedProgram.expandLocalRef(mergedProgram.getCondition());

    // MODIFICATION BEGIN
    newCondition = newCondition.accept(new MoreRelOptUtil.RexLiteralCanonicalizer(rexBuilder));
    // MODIFICATION ENDS

    final RelBuilder relBuilder = call.builder();
    relBuilder.push(bottomFilter.getInput()).filter(newCondition);

    call.transformTo(relBuilder.build());
  }

  /**
   * Creates a RexProgram corresponding to a LogicalFilter
   *
   * @param filterRel the LogicalFilter
   * @return created RexProgram
   */
  private RexProgram createProgram(Filter filterRel) {
    RexProgramBuilder programBuilder =
        new RexProgramBuilder(filterRel.getRowType(), filterRel.getCluster().getRexBuilder());
    programBuilder.addIdentity();
    programBuilder.addCondition(filterRel.getCondition());
    return programBuilder.getProgram();
  }

  /** Rule configuration. */
  public interface Config extends RelRule.Config {
    Config DEFAULT = EMPTY.as(Config.class).withOperandFor(Filter.class);

    @Override
    default DremioFilterMergeRule toRule() {
      return new DremioFilterMergeRule(this);
    }

    /** Defines an operand tree for the given classes. */
    default Config withOperandFor(Class<? extends Filter> filterClass) {
      return withOperandSupplier(
              b0 -> b0.operand(filterClass).oneInput(b1 -> b1.operand(filterClass).anyInputs()))
          .as(Config.class);
    }
  }
}

// End FilterMergeRule.java
