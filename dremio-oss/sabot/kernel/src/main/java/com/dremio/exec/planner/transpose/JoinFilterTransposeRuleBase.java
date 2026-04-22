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
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.rules.TransformationRule;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.util.ImmutableBeans;

/**
 * Base class for:
 *
 * @see JoinFilterTransposeRuleLeft
 * @see JoinFilterTransposeRuleRight
 *     <p>Planner rule that matches a {@link org.apache.calcite.rel.core.Join} one of whose inputs
 *     is a {@link org.apache.calcite.rel.core.Filter}, and pulls the filter(s) up and pushes Join
 *     down.
 *     <p>Filter(s) are pulled up if the {@link org.apache.calcite.rel.core.Filter} doesn't
 *     originate from a null generating input in an outer join.
 *     <p>If there are outer joins, the Filter from left will be pulled up only if there is a left
 *     outer join. On the other hand, the right Filter will be pulled up only it there is a right
 *     outer join.
 */
public abstract class JoinFilterTransposeRuleBase
    extends RelRule<JoinFilterTransposeRuleBase.Config> implements TransformationRule {

  protected JoinFilterTransposeRuleBase(Config config) {
    super(config);
  }

  protected Optional<RexNode> handleNullability(RexNode rexNode, RexBuilder rexBuilder) {
    switch (config.getNullHandling()) {
      case NEVER:
        return Optional.empty();
      case IF_NULL_MATCHING:
        /*
         This involves rewriting to something like:
         FILTER(b is not null ? b.z = blah : true)
           LEFT JOIN ((a.x = b.y))
              Scan(a)
              Scan(b)
        */
        throw new IllegalArgumentException();
      case ALWAYS:
        RexNode withNullable =
            rexNode.accept(
                new RexShuttle() {
                  @Override
                  public RexNode visitInputRef(RexInputRef inputRef) {
                    // And make nullable
                    RelDataType newType =
                        rexBuilder
                            .getTypeFactory()
                            .createTypeWithNullability(inputRef.getType(), true);
                    return new RexInputRef(inputRef.getIndex(), newType);
                  }
                });
        return Optional.of(withNullable);
      default:
        throw new IllegalArgumentException();
    }
  }

  public interface Config extends RelRule.Config {
    Config DEFAULT =
        EMPTY
            .as(Config.class)
            .withNullHandling(NullHandling.NEVER)
            .withDescription("JoinFilterTransposeRuleBase")
            .as(Config.class);

    @ImmutableBeans.Property
    NullHandling getNullHandling();

    Config withNullHandling(NullHandling nullHandling);

    enum NullHandling {
      NEVER,
      IF_NULL_MATCHING,
      ALWAYS
    }
  }
}
