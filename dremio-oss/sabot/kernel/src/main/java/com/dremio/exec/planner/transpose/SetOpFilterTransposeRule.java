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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.plan.hep.HepRelVertex;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.SingleRel;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.SetOp;
import org.apache.calcite.rel.rules.TransformationRule;
import org.apache.calcite.util.ImmutableBeans;
import org.apache.calcite.util.Pair;

public final class SetOpFilterTransposeRule extends RelRule<SetOpFilterTransposeRule.Config>
    implements TransformationRule {

  private SetOpFilterTransposeRule(Config config) {
    super(config);
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    SetOp setOp = call.rel(0);
    Filter filter = call.rel(1);

    Optional<List<Filter>> optionalFilters = extractFilters(setOp);
    if (optionalFilters.isEmpty()) {
      return;
    }

    List<Filter> extractedFilters = optionalFilters.get();
    for (Filter extractedFilter : extractedFilters) {
      if (!config.shouldConsiderFiltersEquivalent().test(Pair.of(filter, extractedFilter))) {
        return;
      }
    }

    List<RelNode> newInputs =
        extractedFilters.stream().map(SingleRel::getInput).collect(Collectors.toList());

    // Replace the setOp with a single Filter on top
    RelNode newSetOp = setOp.copy(setOp.getTraitSet(), newInputs);
    RelNode transposed = call.builder().push(newSetOp).filter(filter.getCondition()).build();
    call.transformTo(transposed);
  }

  private static Optional<List<Filter>> extractFilters(SetOp setOp) {
    List<Filter> filters = new ArrayList<>();
    for (RelNode input : setOp.getInputs()) {
      input = unwrapHepRelVertex(input);
      if (!(input instanceof Filter)) {
        return Optional.empty();
      }

      Filter childFilter = (Filter) input;
      filters.add(childFilter);
    }

    return Optional.of(filters);
  }

  private static RelNode unwrapHepRelVertex(RelNode relNode) {
    if (relNode instanceof HepRelVertex) {
      return ((HepRelVertex) relNode).getCurrentRel();
    }

    return relNode;
  }

  public interface Config extends RelRule.Config {
    Config DEFAULT =
        EMPTY
            .withDescription(SetOpFilterTransposeRule.class.getSimpleName())
            .withOperandSupplier(
                setOp ->
                    setOp
                        .operand(SetOp.class)
                        .inputs(filter -> filter.operand(Filter.class).anyInputs()))
            .as(Config.class)
            .withShouldConsiderFiltersEquivalent(
                filterFilterPair ->
                    filterFilterPair
                        .left
                        .getCondition()
                        .equals(filterFilterPair.right.getCondition()));

    @Override
    default SetOpFilterTransposeRule toRule() {
      return new SetOpFilterTransposeRule(this);
    }

    @ImmutableBeans.Property
    Predicate<Pair<Filter, Filter>> shouldConsiderFiltersEquivalent();

    Config withShouldConsiderFiltersEquivalent(Predicate<Pair<Filter, Filter>> predicate);
  }
}
