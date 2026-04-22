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
package com.dremio.exec.planner.decorrelation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;
import org.apache.calcite.rel.RelHomogeneousShuttle;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Correlate;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.SetOp;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.Uncollect;
import org.apache.calcite.rel.core.Window;
import org.apache.calcite.rex.RexCorrelVariable;
import org.apache.calcite.rex.RexFieldAccess;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexSubQuery;

public final class DecorrelationFailureReasonDetector {
  private DecorrelationFailureReasonDetector() {}

  public static Optional<String> investigateFailures(RelNode relNode) {
    List<Correlate> correlates = collectCorrelates(relNode);
    Set<RexFieldAccess> correlatedFieldAccess = collectCorrelatedFieldAccess(relNode);
    Set<RexSubQuery> subQueries = collectRexSubquery(relNode);

    if (correlates.isEmpty() && correlatedFieldAccess.isEmpty() && subQueries.isEmpty()) {
      return Optional.empty();
    }

    Map<String, List<String>> failureReasonMap = new HashMap<>();
    failureReasonMap.put(
        "ROOT", investigateRootFailures(correlates, correlatedFieldAccess, subQueries));

    correlates.forEach(
        correlate ->
            failureReasonMap.put(
                correlate.getCorrelationId().getName(), investigateCorrelateFailures(correlate)));

    String aggregateFailureReason = buildFailureReasonMessage(failureReasonMap);
    return Optional.of(aggregateFailureReason);
  }

  private static List<String> investigateRootFailures(
      List<Correlate> correlates,
      Set<RexFieldAccess> correlatedFieldAccess,
      Set<RexSubQuery> subQueries) {

    List<String> failures = new ArrayList<>();

    if (!correlatedFieldAccess.isEmpty()) {
      failures.add("Found correlate variables: " + formatFieldAccesses(correlatedFieldAccess));
    }

    if (!subQueries.isEmpty()) {
      failures.add("Found unexpanded subqueries.");
    }

    if (!correlates.isEmpty()) {
      failures.add("Found correlate relnodes: " + formatCorrelateIds(correlates));
    }

    return failures;
  }

  private static List<String> investigateCorrelateFailures(Correlate correlate) {
    List<String> failureReasons = new ArrayList<>();
    checkUnsupportedRelNodes(correlate, failureReasons);
    return failureReasons;
  }

  /**
   * For various reasons the decorrelator gives up trying to decorrelate RelNodes that it doesn't
   * know how to handle As we add more decorrelation logic, then method will be needed less and
   * less.
   */
  private static void checkUnsupportedRelNodes(Correlate correlate, List<String> failureReasons) {
    Stack<RelNode> lineage = new Stack<>();
    List<RelNode> unsupportedRelNodes = new ArrayList<>();

    // Check to see if we have any unsupported relnodes under a correlate relnode
    // and over a correlate variable
    RelHomogeneousShuttle detector =
        new RelHomogeneousShuttle() {
          @Override
          public RelNode visit(RelNode other) {
            lineage.push(other);
            if (CorrelateVariableDetector.detect(other)) {
              // Correlate Variable detected
              // Now find all unsuported relnodes below the correlate
              boolean correlateDetected = false;
              for (RelNode relNode : lineage) {
                if (relNode instanceof Correlate) {
                  correlateDetected = true;
                }

                if (correlateDetected && isUnsupportedRelNode(relNode)) {
                  unsupportedRelNodes.add(relNode);
                }
              }
            }

            RelNode superVisit = super.visit(other);
            lineage.pop();
            return superVisit;
          }
        };

    correlate.accept(detector);

    if (!unsupportedRelNodes.isEmpty()) {
      String unsupportedNodes =
          unsupportedRelNodes.stream()
              .map(rel -> rel.getClass().getSimpleName())
              .distinct()
              .sorted()
              .collect(Collectors.joining(", "));
      failureReasons.add("Unsupported RelNodes: " + unsupportedNodes);
    }
  }

  /**
   * RelDecorrelator can not decorrelate a correlate that has these relnodes ABOVE a correlate
   * variable It either doesn't know how to handle them or some are impossible. As we add support
   * this list will shrink.
   */
  private static boolean isUnsupportedRelNode(RelNode relNode) {
    if (relNode instanceof Window) {
      return true;
    }

    if (relNode instanceof SetOp) {
      return true;
    }

    if (relNode instanceof Uncollect) {
      return true;
    }

    if (relNode instanceof Sort) {
      Sort sort = (Sort) relNode;
      if (sort.fetch != null) {
        return true;
      }

      if (sort.offset != null) {
        return true;
      }
    }

    return false;
  }

  private static String formatFieldAccesses(Set<RexFieldAccess> fieldAccesses) {
    return fieldAccesses.stream().map(RexNode::toString).sorted().collect(Collectors.joining(", "));
  }

  private static String formatCorrelateIds(List<Correlate> correlates) {
    return correlates.stream()
        .map(correlate -> correlate.getCorrelationId().getName())
        .sorted()
        .collect(Collectors.joining(", "));
  }

  private static String buildFailureReasonMessage(Map<String, List<String>> failureReasonMap) {
    StringBuilder failureMessage =
        new StringBuilder("Decorrelation failed for the following reasons:\n");

    failureReasonMap.forEach(
        (key, reasons) -> {
          failureMessage.append("\nFor '").append(key).append("':");
          if (reasons.isEmpty()) {
            failureMessage.append("\n  *  FAILED FOR UNKNOWN REASON");
          } else {
            reasons.forEach(reason -> failureMessage.append("\n  *  ").append(reason));
          }
        });

    return failureMessage.toString();
  }

  private static final class CorrelateVariableDetector extends RexShuttle {
    private boolean hasCorrelateVariable;

    private CorrelateVariableDetector() {}

    @Override
    public RexNode visitCorrelVariable(RexCorrelVariable correlVariable) {
      hasCorrelateVariable = true;
      return correlVariable;
    }

    public static boolean detect(RelNode relNode) {
      CorrelateVariableDetector detector = new CorrelateVariableDetector();
      if (relNode instanceof Project) {
        Project project = (Project) relNode;
        project.getProjects().stream().forEach(p -> p.accept(detector));
        return detector.hasCorrelateVariable;
      } else if (relNode instanceof Filter) {
        Filter filter = (Filter) relNode;
        filter.getCondition().accept(detector);
        return detector.hasCorrelateVariable;
      } else if (relNode instanceof Join) {
        Join join = (Join) relNode;
        join.getCondition().accept(detector);
        return detector.hasCorrelateVariable;
      } else {
        return false;
      }
    }
  }

  public static List<Correlate> collectCorrelates(RelNode rel) {
    List<Correlate> correlates = new ArrayList<>();
    rel.accept(
        new RelHomogeneousShuttle() {
          @Override
          public RelNode visit(RelNode other) {
            if (other instanceof Correlate) {
              correlates.add((Correlate) other);
            }

            return super.visit(other);
          }
        });

    return correlates;
  }

  private static Set<RexFieldAccess> collectCorrelatedFieldAccess(RelNode relNode) {
    Set<RexFieldAccess> rexFieldAccesses = new HashSet<>();
    relNode.accept(
        new RelHomogeneousShuttle() {
          @Override
          public RelNode visit(RelNode other) {
            RelNode visitedRelNode = super.visit(other);
            visitedRelNode =
                visitedRelNode.accept(
                    new RexShuttle() {
                      @Override
                      public RexNode visitFieldAccess(RexFieldAccess fieldAccess) {
                        if (fieldAccess.getReferenceExpr() instanceof RexCorrelVariable) {
                          rexFieldAccesses.add(fieldAccess);
                        }

                        return fieldAccess;
                      }
                    });
            return visitedRelNode;
          }
        });

    return rexFieldAccesses;
  }

  public static Set<RexSubQuery> collectRexSubquery(RelNode relNode) {
    Set<RexSubQuery> rexSubQueries = new HashSet<>();
    relNode.accept(
        new RelHomogeneousShuttle() {
          @Override
          public RelNode visit(RelNode other) {
            RelNode visitedRelNode = super.visit(other);
            visitedRelNode =
                visitedRelNode.accept(
                    new RexShuttle() {
                      @Override
                      public RexNode visitSubQuery(RexSubQuery rexSubQuery) {
                        rexSubQueries.add(rexSubQuery);
                        return rexSubQuery;
                      }
                      ;
                    });
            return visitedRelNode;
          }
        });

    return rexSubQueries;
  }
}
