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

import com.dremio.exec.planner.sql.RexShuttleRelShuttle;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.calcite.rel.RelHomogeneousShuttle;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Correlate;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCorrelVariable;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.tools.RelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CorrelateRelUniqueIdRewriter extends RelHomogeneousShuttle {
  public static final Logger LOGGER = LoggerFactory.getLogger(CorrelateRelUniqueIdRewriter.class);

  private final Set<Integer> correlationIds = new HashSet<>();
  private final RelBuilder relBuilder;
  private final Map<Integer, CorrelationId> correlationIdMapping = new HashMap<>();

  public CorrelateRelUniqueIdRewriter(RelBuilder relBuilder) {
    this.relBuilder = relBuilder;
  }

  @Override
  public RelNode visit(RelNode node) {
    node = super.visit(node);
    if (!(node instanceof Correlate)) {
      return node;
    }

    Correlate correlate = (Correlate) node;
    int correlateId = correlate.getCorrelationId().getId();
    if (correlationIds.add(correlateId)) {
      // Not a duplicate
      return node;
    }

    // Duplicate found, find the next unused distinct ID
    int newIdValue = correlateId;
    while (correlationIds.contains(newIdValue)) {
      newIdValue++;
    }

    correlationIds.add(newIdValue);

    CorrelationId newId = new CorrelationId(newIdValue);
    correlationIdMapping.put(correlateId, newId);

    // Rewrite the CorrelateRel with new distinct IDs if needed
    return correlate.copy(
        correlate.getTraitSet(),
        correlate.getInputs().get(0),
        // Only rewrite the variables on the right hand side
        // The variables on the left hand side belong to the nested correlate
        correlate
            .getInputs()
            .get(1)
            .accept(
                new RexShuttleRelShuttle(
                    new CorrelateVariableRewriter(
                        correlationIdMapping, relBuilder.getRexBuilder()))),
        correlationIdMapping.get(correlateId),
        correlate.getRequiredColumns(),
        correlate.getJoinType());
  }

  // RexShuttle to update correlated variable references
  private static class CorrelateVariableRewriter extends RexShuttle {
    private final Map<Integer, CorrelationId> correlationIdMapping;
    private final RexBuilder rexBuilder;

    public CorrelateVariableRewriter(
        Map<Integer, CorrelationId> correlationIdMapping, RexBuilder rexBuilder) {
      this.correlationIdMapping = correlationIdMapping;
      this.rexBuilder = rexBuilder;
    }

    @Override
    public RexNode visitCorrelVariable(RexCorrelVariable variable) {
      CorrelationId newId = correlationIdMapping.get(variable.id.getId());
      if (newId != null) {
        // Rewrite the variable with the new correlation ID using rexBuilder
        return rexBuilder.makeCorrel(variable.getType(), newId);
      }
      return super.visitCorrelVariable(variable);
    }
  }

  public static RelNode rewrite(RelNode root, RelBuilder relBuilder) {
    try {
      CorrelateRelUniqueIdRewriter rewriter = new CorrelateRelUniqueIdRewriter(relBuilder);
      return root.accept(rewriter);
    } catch (Exception ex) {
      LOGGER.error("Failed to make correlate ids unique for whatever reason.", ex);
      // If the rewrite fails we don't want to fail the query:
      return root;
    }
  }
}
