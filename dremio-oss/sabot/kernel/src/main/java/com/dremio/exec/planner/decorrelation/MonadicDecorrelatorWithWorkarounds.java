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

import com.dremio.exec.planner.common.ExceptionMonad;
import com.dremio.exec.planner.normalizer.DremioPruneEmptyRules;
import java.util.ArrayList;
import java.util.List;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.rel.RelHomogeneousShuttle;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Correlate;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.Litmus;

public final class MonadicDecorrelatorWithWorkarounds implements MonadicDecorrelator {
  private final MonadicDecorrelator monadicDecorrelator;
  private final RelBuilder relBuilder;

  public MonadicDecorrelatorWithWorkarounds(
      MonadicDecorrelator monadicDecorrelator, RelBuilder relBuilder) {
    this.monadicDecorrelator = monadicDecorrelator;
    this.relBuilder = relBuilder;
  }

  @Override
  public ExceptionMonad<RelNode, DecorrelationException> tryDecorrelate(RelNode relNode) {
    return monadicDecorrelator
        .tryDecorrelate(relNode)
        // These are workaround we add to make decorrelation work, but we only want to run
        // them on failures to not affect the success path
        .retry(
            () -> {

              // Some expansion rules incorrectly create a correlate without any correlate variables
              // We can rewrite them to a regular join to workaround this.
              RelNode correlatesToJoin = rewriteCorrelateToJoin(relNode, relBuilder);
              return monadicDecorrelator.tryDecorrelate(correlatesToJoin);
            })
        .retry(
            () -> {
              // If a correlate variable could be removed by pruning an empty rel, then we will take
              // it
              RelNode emptyRelsPruned = pruneEmptyRelNodes(relNode);
              return monadicDecorrelator.tryDecorrelate(emptyRelsPruned);
            })
        .retry(
            () -> {
              // If the root rel does not have a top level project,
              // Then the decorrelator bugs out and doesn't preserve the schema when doing value
              // generation.
              RelNode withTopLevelProject = addTopLevelProject(relNode, relBuilder);
              return monadicDecorrelator.tryDecorrelate(withTopLevelProject);
            });
  }

  /**
   * Rewrites CorrelateRel to a join if the correlation id is not getting used in the right
   * relational tree.
   */
  private RelNode rewriteCorrelateToJoin(RelNode relNode, RelBuilder relBuilder) {
    // Check if the correlates can be rewritten into joins.
    return relNode.accept(
        new RelHomogeneousShuttle() {
          @Override
          public RelNode visit(RelNode other) {
            if (!(other instanceof Correlate)) {
              return super.visit(other);
            }
            final Correlate correlate = (Correlate) other;
            // if the correlation id is not getting used in the right subtree, rewrite correlate to
            // join.
            if (RelOptUtil.notContainsCorrelation(
                correlate.getRight(), correlate.getCorrelationId(), Litmus.IGNORE)) {
              return super.visit(
                  relBuilder
                      .push(correlate.getLeft())
                      .push(correlate.getRight())
                      .join(correlate.getJoinType(), relBuilder.getRexBuilder().makeLiteral(true))
                      .build());
            } else {
              return super.visit(other);
            }
          }
        });
  }

  private static RelNode pruneEmptyRelNodes(RelNode root) {
    HepProgramBuilder programBuilder = new HepProgramBuilder();
    programBuilder.addRuleCollection(DremioPruneEmptyRules.ALL_RULES);
    HepPlanner hepPlanner = new HepPlanner(programBuilder.build());
    hepPlanner.setRoot(root);
    RelNode prunedRelNode = hepPlanner.findBestExp();
    return prunedRelNode;
  }

  private static RelNode addTopLevelProject(RelNode relNode, RelBuilder relBuilder) {
    List<RexNode> projects = new ArrayList<>();
    RexBuilder rexBuilder = relBuilder.getRexBuilder();
    for (RelDataTypeField relDataTypeField : relNode.getRowType().getFieldList()) {
      RexNode project =
          rexBuilder.makeInputRef(relDataTypeField.getType(), relDataTypeField.getIndex());
      projects.add(project);
    }

    return relBuilder
        .push(relNode)
        .project(projects, relNode.getRowType().getFieldNames(), true)
        .build();
  }
}
