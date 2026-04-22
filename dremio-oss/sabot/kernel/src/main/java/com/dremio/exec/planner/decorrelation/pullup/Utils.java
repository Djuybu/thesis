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
package com.dremio.exec.planner.decorrelation.pullup;

import java.util.List;
import java.util.stream.Collectors;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.hep.HepRelVertex;
import org.apache.calcite.rel.RelHomogeneousShuttle;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexCorrelVariable;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexUtil;

public class Utils {
  private Utils() {}

  public static CorrelatedAndUncorrelatedConjunctions splitCorrelatedConjunctions(
      RexNode condition) {
    List<RexNode> conjunctions = RelOptUtil.conjunctions(condition);
    List<RexNode> correlatedConjunctions =
        conjunctions.stream().filter(RexUtil::containsCorrelation).collect(Collectors.toList());
    List<RexNode> uncorrelatedConjunctions =
        conjunctions.stream()
            .filter(conjunction -> !RexUtil.containsCorrelation(conjunction))
            .collect(Collectors.toList());

    return new CorrelatedAndUncorrelatedConjunctions(
        correlatedConjunctions, uncorrelatedConjunctions);
  }

  public static CorrelatedType determineCorrelatedConditionType(RexNode condition) {
    CorrelatedAndUncorrelatedConjunctions conjunctions = splitCorrelatedConjunctions(condition);
    if (!conjunctions.correlatedConjunctions.isEmpty()
        && conjunctions.uncorrelatedConjunctions.isEmpty()) {
      return CorrelatedType.PURELY_CORRELATED;
    } else if (conjunctions.correlatedConjunctions.isEmpty()
        && !conjunctions.uncorrelatedConjunctions.isEmpty()) {
      return CorrelatedType.PURELY_UNCORRELATED;
    } else if (!conjunctions.correlatedConjunctions.isEmpty()
        && !conjunctions.uncorrelatedConjunctions.isEmpty()) {
      return CorrelatedType.MIXED;
    } else {
      // both empty
      return CorrelatedType.PURELY_UNCORRELATED;
    }
  }

  public static boolean isPurelyCorrelatedCondition(RexNode condition) {
    return determineCorrelatedConditionType(condition) == CorrelatedType.PURELY_CORRELATED;
  }

  public static boolean isPurelyUncorrelatedCondition(RexNode condition) {
    return determineCorrelatedConditionType(condition) == CorrelatedType.PURELY_UNCORRELATED;
  }

  public static boolean isMixedCorrelatedCondition(RexNode condition) {
    return determineCorrelatedConditionType(condition) == CorrelatedType.MIXED;
  }

  public static boolean isPurelyCorrelatedFilter(Filter filter) {
    return isPurelyCorrelatedCondition(filter.getCondition());
  }

  public static boolean isPurelyUncorrelatedFilter(Filter filter) {
    return isPurelyUncorrelatedCondition(filter.getCondition());
  }

  public static boolean isMixedCorrelatedFilter(Filter filter) {
    return isMixedCorrelatedCondition(filter.getCondition());
  }

  public static CorrelatedType determineCorrelateFilterType(Filter filter) {
    return determineCorrelatedConditionType(filter.getCondition());
  }

  public static boolean isCorrelatedFilter(Filter filter) {
    if (!isPurelyCorrelatedFilter(filter)) {
      return false;
    }

    // Make sure no correlated variables under it
    final boolean[] hasCorrelatedVariablesUnder = {false};
    filter
        .getInput()
        .accept(
            new RelHomogeneousShuttle() {
              @Override
              public RelNode visit(RelNode other) {
                if (other instanceof HepRelVertex) {
                  HepRelVertex hepRelVertex = (HepRelVertex) other;
                  other = hepRelVertex.getCurrentRel();
                }

                other.accept(
                    new RexShuttle() {
                      @Override
                      public RexNode visitCorrelVariable(RexCorrelVariable correlVariable) {
                        hasCorrelatedVariablesUnder[0] = true;
                        return correlVariable;
                      }
                    });

                return super.visit(other);
              }
            });

    return !hasCorrelatedVariablesUnder[0];
  }

  public static boolean hasAdjustCountOperator(Project project) {
    return project.getProjects().stream().anyMatch(Utils::hasAdjustCountOperator);
  }

  public static boolean hasAdjustCountOperator(RexNode expr) {
    final Boolean[] found = {false};
    expr.accept(
        new RexShuttle() {
          @Override
          public RexNode visitCall(final RexCall call) {
            if (found[0]) {
              return call;
            }

            if (call.getOperator()
                .equals(AggregateCorrelatedFilterTransposeRule.ADJUST_COUNT_OPERATOR)) {
              found[0] = true;
            }

            return super.visitCall(call);
          }
        });

    return found[0];
  }

  public static final class CorrelatedAndUncorrelatedConjunctions {
    private final List<RexNode> correlatedConjunctions;
    private final List<RexNode> uncorrelatedConjunctions;

    public CorrelatedAndUncorrelatedConjunctions(
        List<RexNode> correlatedConjunctions, List<RexNode> uncorrelatedConjunctions) {
      this.correlatedConjunctions = correlatedConjunctions;
      this.uncorrelatedConjunctions = uncorrelatedConjunctions;
    }

    public List<RexNode> getCorrelatedConjunctions() {
      return correlatedConjunctions;
    }

    public List<RexNode> getUncorrelatedConjunctions() {
      return uncorrelatedConjunctions;
    }
  }

  public enum CorrelatedType {
    PURELY_CORRELATED,
    PURELY_UNCORRELATED,
    MIXED
  }
}
