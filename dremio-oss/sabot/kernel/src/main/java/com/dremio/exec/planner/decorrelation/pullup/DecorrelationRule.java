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

import com.dremio.exec.planner.logical.RelDataTypeEqualityComparer;
import java.util.Set;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Correlate;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.rules.TransformationRule;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCorrelVariable;
import org.apache.calcite.rex.RexFieldAccess;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.tools.RelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rule for handling base case for decorrelation, which is converting:
 *
 * <pre>
 *   LogicalCorrelate(correlation=[$cor0], joinType=[left], requiredColumns=[{0}])
 *    LogicalTableScan(table=[[DEPT]])
 *    LogicalFilter(condition=[=($cor0.DEPTNO, $7)])
 *      LogicalTableScan(table=[[EMP]])
 * </pre>
 *
 * Into:
 *
 * <pre>
 *   LogicalJoin(condition=[=($0, $10)], joinType=[left])
 *    LogicalTableScan(table=[[DEPT]])
 *    LogicalTableScan(table=[[EMP]])
 * </pre>
 *
 * We combine this rule with Correlated Filter Pullup to perform decorrelation.
 *
 * @see PullupDecorrelator
 */
public final class DecorrelationRule extends RelRule<DecorrelationRule.Config>
    implements TransformationRule {
  private static final Logger LOGGER = LoggerFactory.getLogger(DecorrelationRule.class);

  private DecorrelationRule(Config config) {
    super(config);
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    final Correlate correlate = call.rel(0);
    final RelNode baseQuery = call.rel(1);
    final Filter filter = call.rel(2);

    final RelBuilder relBuilder = call.builder();
    final RexBuilder rexBuilder = relBuilder.getRexBuilder();

    Set<Integer> correlationIds = CorrelationIdExtractor.extract(filter.getCondition());
    if (!correlationIds.contains(correlate.getCorrelationId().getId())) {
      return;
    }

    RexNode decorrelatedCondition = decorrelateExpr(filter.getCondition(), rexBuilder, correlate);
    RelNode decorrelated =
        call.builder()
            .push(baseQuery)
            .push(filter.getInput())
            .join(correlate.getJoinType(), decorrelatedCondition)
            .build();
    call.transformTo(decorrelated);
  }

  private static RexNode decorrelateExpr(RexNode expr, RexBuilder rexBuilder, Correlate correlate) {
    return expr.accept(
        new RexShuttle() {
          @Override
          public RexNode visitInputRef(RexInputRef inputRef) {
            // Adjust for pulling the filter up
            int newIndex = correlate.getLeft().getRowType().getFieldCount() + inputRef.getIndex();
            RexNode newInputRef = rexBuilder.makeInputRef(correlate.getRowType(), newIndex);
            return newInputRef;
          }

          @Override
          public RexNode visitFieldAccess(RexFieldAccess fieldAccess) {
            if (!(fieldAccess.getReferenceExpr() instanceof RexCorrelVariable)) {
              return super.visitFieldAccess(fieldAccess);
            }

            RexCorrelVariable rexCorrelVariable =
                (RexCorrelVariable) fieldAccess.getReferenceExpr();
            if (rexCorrelVariable.id.getId() != correlate.getCorrelationId().getId()) {
              return super.visitFieldAccess(fieldAccess);
            }

            if (!RelDataTypeEqualityComparer.areEqual(
                rexCorrelVariable.getType(), correlate.getLeft().getRowType())) {
              LOGGER.warn(
                  "Encountered a correlate variable whose reldatatype does not align up with the correlate reldatatype.");
            }

            return rexBuilder.makeInputRef(
                rexCorrelVariable.getType(), fieldAccess.getField().getIndex());
          }
        });
  }

  public interface Config extends RelRule.Config {
    Config DEFAULT =
        EMPTY
            .withDescription(DecorrelationRule.class.getSimpleName())
            .withOperandSupplier(
                OperandTransformFactory.bi(Correlate.class).right().correlatedFilter())
            .as(Config.class);

    @Override
    default DecorrelationRule toRule() {
      return new DecorrelationRule(this);
    }
  }
}
