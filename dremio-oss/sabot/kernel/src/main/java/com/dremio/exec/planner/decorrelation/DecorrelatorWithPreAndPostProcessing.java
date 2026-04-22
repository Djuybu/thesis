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

import com.dremio.exec.planner.logical.CorrelateRelUniqueIdRewriter;
import com.dremio.exec.planner.trimmer.RelNodeTrimmerFactory;
import com.dremio.exec.planner.trimmer.TrimmerType;
import com.dremio.exec.planner.trimmer.calcite.DremioFieldTrimmerParameters;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelHomogeneousShuttle;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Correlate;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.tools.RelBuilderFactory;
import org.apache.calcite.util.Litmus;

/** Adds basic pre and post processing to any decorrelator. */
public final class DecorrelatorWithPreAndPostProcessing implements Decorrelator {
  private final Decorrelator decorrelator;
  private final RelBuilderFactory relBuilderFactory;
  private final boolean makeCorrelateIdsDistinct;
  private final boolean isRelPlanning;

  public DecorrelatorWithPreAndPostProcessing(
      Decorrelator decorrelator,
      RelBuilderFactory relBuilderFactory,
      boolean makeCorrelateIdsDistinct,
      boolean isRelPlanning) {
    this.decorrelator = decorrelator;
    this.relBuilderFactory = relBuilderFactory;
    this.makeCorrelateIdsDistinct = makeCorrelateIdsDistinct;
    this.isRelPlanning = isRelPlanning;
  }

  @Override
  public RelNode decorrelate(RelNode relNode) {
    RelNode preProccesed = preprocess(relNode);
    RelNode decorrelated = decorrelator.decorrelate(preProccesed);
    RelNode postProccesed = postProcess(decorrelated);
    return postProccesed;
  }

  private RelNode preprocess(RelNode relNode) {
    // For whatever reason this need to be part of the preprocess otherwise it will lead to
    // incorrect results
    // (not just failed to decorrelate)
    RelBuilder relBuilder = relBuilderFactory.create(relNode.getCluster(), null);
    relNode = rewriteCorrelateToJoin(relNode, relBuilder);
    if (makeCorrelateIdsDistinct) {
      relNode = CorrelateRelUniqueIdRewriter.rewrite(relNode, relBuilder);
    }

    return relNode;
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

  public RelNode postProcess(RelNode relNode) {
    // Decorrelation sometimes introduces plans that need to be trimmed.
    // For example, it introduces an internal only LITERAL_AGG call that has no execution.
    LiteralAggDetector detector = new LiteralAggDetector();
    relNode.accept(detector);
    if (detector.hasLiteralAgg) {
      relNode = trimUnusedFields(relNode);
    }

    return relNode;
  }

  private RelNode trimUnusedFields(RelNode relNode) {
    return RelNodeTrimmerFactory.create(
            TrimmerType.CALCITE,
            DremioFieldTrimmerParameters.builder()
                .shouldLog(true)
                .isRelPlanning(isRelPlanning)
                .trimProjectedColumn(true)
                .trimJoinBranch(false)
                .build())
        .tryTrim(relNode, relBuilderFactory)
        .unsafeGet();
  }

  private static final class LiteralAggDetector extends RelHomogeneousShuttle {
    private boolean hasLiteralAgg = false;

    @Override
    public RelNode visit(RelNode relNode) {
      if (relNode instanceof Aggregate) {
        Aggregate aggregate = (Aggregate) relNode;
        if (aggregate.getAggCallList().stream()
            .anyMatch(
                aggregateCall ->
                    aggregateCall.getAggregation().getName().equalsIgnoreCase("LITERAL_AGG"))) {
          hasLiteralAgg = true;
        }
      }

      return super.visit(relNode);
    }
  }
}
