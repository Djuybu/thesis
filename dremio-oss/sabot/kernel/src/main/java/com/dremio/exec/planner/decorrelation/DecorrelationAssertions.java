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

import com.dremio.exec.planner.common.AssertionLogger;
import com.dremio.exec.planner.logical.RelDataTypeEqualityComparer;
import com.dremio.exec.planner.sql.handlers.RexSubQueryUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;
import org.apache.calcite.rel.RelHomogeneousShuttle;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Correlate;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexCorrelVariable;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexSubQuery;
import org.slf4j.LoggerFactory;

public final class DecorrelationAssertions {
  private static final Boolean ENABLED = false;

  private static final AssertionLogger ASSERTION_LOGGER =
      new AssertionLogger(LoggerFactory.getLogger(DecorrelationAssertions.class));

  public static void assertRexSubquery(RexSubQuery rexSubQuery) {
    if (!ENABLED) {
      return;
    }

    ASSERTION_LOGGER.assertTrue(
        usesCorrelationId(rexSubQuery),
        "RexSubquery has correlationId, but no corresponding variable");
  }

  public static void assertPostSqlToRelPreExpansion(RelNode relNode) {
    if (!ENABLED) {
      return;
    }

    try {
      Stack<RexSubQuery> lineage = new Stack<>();
      relNode.accept(
          new RelHomogeneousShuttle() {
            @Override
            public RelNode visit(RelNode other) {
              RelNode visitedRelNode = super.visit(other);
              // RexSubqueries can only be found in Project, Filter, and Joins
              if (other instanceof Project || other instanceof Filter || other instanceof Join) {
                Set<RexSubQuery> collectedRexSubqueries = collectRexSubqueries(visitedRelNode);
                for (RexSubQuery rexSubQuery : collectedRexSubqueries) {
                  if (rexSubQuery.correlationId != null) {
                    ASSERTION_LOGGER.assertTrue(
                        usesCorrelationId(rexSubQuery),
                        "RexSubquery has correlationId, but no corresponding variable");
                    Set<RexCorrelVariable> correlVariables =
                        collectCorrelatedVariables(rexSubQuery.rel, rexSubQuery.correlationId);
                    // By definition the input schema is the child node,
                    // but for JOINs, since they have two children it is the node itself
                    RelDataType inputSchema;
                    if (other instanceof Join) {
                      inputSchema = other.getRowType();
                    } else {
                      inputSchema = other.getInput(0).getRowType();
                    }

                    for (RexCorrelVariable correlVariable : correlVariables) {
                      ASSERTION_LOGGER.assertTrue(
                          RelDataTypeEqualityComparer.areEqual(
                              correlVariable.getType(), inputSchema),
                          "Found RexCorrelVariable with improper schema");
                    }
                  }
                }
              }

              visitedRelNode =
                  visitedRelNode.accept(
                      new RexShuttle() {
                        @Override
                        public RexNode visitSubQuery(RexSubQuery rexSubQuery) {
                          lineage.push(rexSubQuery);

                          ASSERTION_LOGGER.assertFalse(
                              hasDuplicateCorrelationId(lineage),
                              "Duplicate CorrelationID found in RexSubquery lineage.");

                          assertRexSubquery(rexSubQuery);
                          rexSubQuery.rel.accept(this);
                          lineage.pop();
                          return rexSubQuery;
                        }
                      });
              return visitedRelNode;
            }
          });
    } catch (Throwable ex) {
      // We don't want a bug in this logic to bring out the system:
      ASSERTION_LOGGER.hitUnexpectedException(ex);
    }
  }

  public static void assertPostExpansionPreDecorrelation(RelNode relNode) {
    if (!ENABLED) {
      return;
    }

    try {
      List<RexSubQuery> rexSubQueries = collectTopLevelRexSubqueries(relNode);
      ASSERTION_LOGGER.assertTrue(
          rexSubQueries.isEmpty(),
          "Found RexSubqueries, which means SubqueryRemoveRule didn't remove it");

      Stack<Correlate> lineage = new Stack<>();
      relNode.accept(
          new RelHomogeneousShuttle() {
            @Override
            public RelNode visit(RelNode other) {
              if (!(other instanceof Correlate)) {
                // Find all the correlate variables and make sure they are within the scope of their
                // parent correlate
                Set<RexCorrelVariable> correlateVariables =
                    RexSubQueryUtils.collectCorrelateVariablesInRelNode(other, null);
                for (RexCorrelVariable correlVariable : correlateVariables) {
                  ASSERTION_LOGGER.assertTrue(
                      correlateVariableInScope(correlVariable, lineage),
                      String.format("Correlate variable %s not in scope", correlVariable.id));
                }

                return super.visit(other);
              }

              Correlate correlate = (Correlate) other;
              lineage.push(correlate);

              ASSERTION_LOGGER.assertTrue(
                  noDuplicateIdsInLineage(lineage),
                  "Correlate variable ID is duplicated in the same lineage");
              ASSERTION_LOGGER.assertTrue(
                  correlateHasVariablesOnRight(correlate),
                  "Correlate '"
                      + correlate.getCorrelationId().getName()
                      + "' has no variable in it's right subtree.");
              ASSERTION_LOGGER.assertTrue(
                  correlateHasNoVariablesOnLeft(correlate),
                  "Correlate '"
                      + correlate.getCorrelationId().getName()
                      + "' has variables in it's left subtree.");
              ASSERTION_LOGGER.assertTrue(
                  correlateVariableSchemaIsCorrect(correlate),
                  "Found RexCorrelVariable with improper schema");
              RelNode visited = super.visit(other);

              lineage.pop();
              return visited;
            }
          });

      ASSERTION_LOGGER.assertTrue(
          correlateAndVariablesMatchUp(relNode),
          "Ids from Correlate Relnode and variables did not match up.");
    } catch (Throwable ex) {
      ASSERTION_LOGGER.hitUnexpectedException(ex);
    }
  }

  public static void assertPostDecorrelation(RelNode relNode) {
    if (!ENABLED) {
      return;
    }

    try {
      ASSERTION_LOGGER.assertTrue(
          hasNoCorrelates(relNode), "Correlate Relnode detected post decorrelation.");
      ASSERTION_LOGGER.assertTrue(
          RexSubQueryUtils.collectCorrelatedVariables(relNode).isEmpty(),
          "Detected correlated variables post decorrelation.");
    } catch (Throwable ex) {
      ASSERTION_LOGGER.hitUnexpectedException(ex);
    }
  }

  /**
   * Make sure that there is no nesting of RexSubquery with duplicate IDs:
   *
   * <p>IN(cor0, Project(EXISTS(cor0 ..
   */
  private static boolean hasDuplicateCorrelationId(Stack<RexSubQuery> lineage) {
    Set<Integer> seenIds = new HashSet<>();

    // Iterate through the lineage stack
    for (RexSubQuery subQuery : lineage) {
      // Get the correlation ID from the subquery
      CorrelationId correlationId = subQuery.correlationId;
      if (correlationId != null) {
        // Check if we've already seen this correlation ID
        if (!seenIds.add(correlationId.getId())) {
          return true;
        }
      }
    }

    // No duplicates found
    return false;
  }

  /** If a RexSubquery has a CorrelationId, then it should use it in its RelNode. */
  private static boolean usesCorrelationId(RexSubQuery rexSubQuery) {
    if (rexSubQuery.correlationId == null) {
      return true;
    }

    return !RexSubQueryUtils.collectCorrelatedVariables(rexSubQuery.rel, rexSubQuery.correlationId)
        .isEmpty();
  }

  private static boolean correlateVariableInScope(
      RexCorrelVariable rexCorrelVariable, Collection<Correlate> scope) {
    return scope.stream()
        .map(correlate -> correlate.getCorrelationId().getId())
        .collect(Collectors.toSet())
        .contains(rexCorrelVariable.id.getId());
  }

  /**
   * Ids must be unique within a correlate relnode nesting. So this is not allowed:
   *
   * <p>Correlate(id = 42) BaseQuery Correlate(id = 42)
   */
  private static boolean noDuplicateIdsInLineage(Stack<Correlate> lineage) {
    Set<Integer> distinctIds =
        lineage.stream()
            .map(parent -> parent.getCorrelationId().getId())
            .collect(Collectors.toSet());

    return distinctIds.size() == lineage.size();
  }

  /**
   * In general a Correlate RelNode can only have it's corresponding correlated variables on it's
   * right side. The left side of the correlate is by definition the base table for the correlation
   * that defines the correlate variable:
   *
   * <p>Correlate BaseTable correlated variables referencing BaseTable
   *
   * <p>So basically it's nonsense for the correlate variable to end up on the left. Indicative of a
   * bug that needs to be fixed upstream.
   */
  private static boolean correlateHasVariablesOnRight(Correlate correlate) {
    return !RexSubQueryUtils.collectCorrelatedVariables(
            correlate.getRight(), correlate.getCorrelationId())
        .isEmpty();
  }

  /**
   * In general a Correlate RelNode can only have it's corresponding correlated variables on it's
   * right side. The left side of the correlate is by definition the base table for the correlation
   * that defines the correlate variable:
   *
   * <p>Correlate BaseTable correlated variables referencing BaseTable
   *
   * <p>So basically it's nonsense for the correlate variable to end up on the left. Indicative of a
   * bug that needs to be fixed upstream.
   */
  private static boolean correlateHasNoVariablesOnLeft(Correlate correlate) {
    return RexSubQueryUtils.collectCorrelatedVariables(
            correlate.getLeft(), correlate.getCorrelationId())
        .isEmpty();
  }

  /**
   * Correlate Variable RelDataTypes must match up with their base query.
   *
   * <p>So the following is not allowed:
   *
   * <p>Correlate BaseQuery (schema = a, b, c) CorVar(schema = a, b)
   */
  private static boolean correlateVariableSchemaIsCorrect(Correlate correlate) {
    Set<RexCorrelVariable> rexCorrelVariables =
        RexSubQueryUtils.collectCorrelatedVariables(
            correlate.getRight(), correlate.getCorrelationId());
    RelDataType leftSchema = correlate.getLeft().getRowType();
    return rexCorrelVariables.stream()
        .allMatch(x -> RelDataTypeEqualityComparer.areEqual(leftSchema, x.getType()));
  }

  /**
   * Within a RelTree we expect that the ids from the correlate relnode and correlate variables are
   * the same set of IDs.
   */
  private static boolean correlateAndVariablesMatchUp(RelNode relNode) {
    Set<Integer> idsFromCorrelates =
        collectCorrelates(relNode).stream()
            .map(correlate -> correlate.getCorrelationId().getId())
            .collect(Collectors.toSet());
    Set<Integer> idsFromVariables =
        RexSubQueryUtils.collectCorrelatedVariables(relNode).stream()
            .map(variable -> variable.id.getId())
            .collect(Collectors.toSet());

    return idsFromVariables.equals(idsFromCorrelates);
  }

  private static Set<Correlate> collectCorrelates(RelNode relNode) {
    Set<Correlate> correlates = new HashSet<>();
    relNode.accept(
        new RelHomogeneousShuttle() {
          @Override
          public RelNode visit(RelNode other) {
            if ((other instanceof Correlate)) {
              Correlate correlate = (Correlate) other;
              correlates.add(correlate);
            }

            return super.visit(other);
          }
        });

    return correlates;
  }

  private static boolean hasNoCorrelates(RelNode relNode) {
    final Boolean[] hasCorrelate = {false};
    relNode.accept(
        new RelHomogeneousShuttle() {
          @Override
          public RelNode visit(RelNode other) {
            if (hasCorrelate[0]) {
              return other;
            }

            if (!(other instanceof Correlate)) {
              return super.visit(other);
            }

            hasCorrelate[0] = true;
            return other;
          }
        });

    return !hasCorrelate[0];
  }

  private static List<RexSubQuery> collectTopLevelRexSubqueries(RelNode relNode) {
    List<RexSubQuery> rexSubQueries = new ArrayList<>();
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
                    });
            return visitedRelNode;
          }
        });

    return rexSubQueries;
  }

  private static Set<RexCorrelVariable> collectCorrelatedVariables(RelNode relNode) {
    return collectCorrelatedVariables(relNode, null);
  }

  /**
   * Collects all RexCorrelVariables under the relnode matching correlationId. If correlationId is
   * not set, then it just grabs all correlate variables
   */
  private static Set<RexCorrelVariable> collectCorrelatedVariables(
      RelNode relNode, CorrelationId correlationId) {
    final Set<RexCorrelVariable> rexCorrelVariables = new HashSet<>();
    relNode.accept(
        new RelHomogeneousShuttle() {
          @Override
          public RelNode visit(RelNode other) {
            RelNode visitedRelNode = super.visit(other);
            visitedRelNode =
                visitedRelNode.accept(
                    new RexShuttle() {
                      @Override
                      public RexNode visitCorrelVariable(RexCorrelVariable variable) {
                        rexCorrelVariables.add(variable);
                        return super.visitCorrelVariable(variable);
                      }

                      @Override
                      public RexNode visitSubQuery(RexSubQuery subQuery) {
                        subQuery.rel.accept(this);
                        return super.visitSubQuery(subQuery);
                      }
                    });
            return visitedRelNode;
          }
        });

    if (correlationId == null) {
      return rexCorrelVariables;
    }

    return rexCorrelVariables.stream()
        .filter(rexCorrelVariable -> (rexCorrelVariable.id.getId() == correlationId.getId()))
        .collect(Collectors.toSet());
  }

  private static Set<RexSubQuery> collectRexSubqueries(RelNode relNode) {
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
                      public RexNode visitSubQuery(RexSubQuery subQuery) {
                        rexSubQueries.add(subQuery);
                        return subQuery;
                      }
                    });
            return visitedRelNode;
          }
        });

    return rexSubQueries;
  }
}
