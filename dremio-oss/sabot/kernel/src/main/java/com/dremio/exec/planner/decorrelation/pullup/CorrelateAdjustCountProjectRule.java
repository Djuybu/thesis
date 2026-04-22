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

import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Correlate;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.rules.TransformationRule;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.tools.RelBuilder;

/**
 * @see AggregateCorrelatedFilterTransposeRule Introduces the ADJUST_COUNT operator that marks that
 *     the column is a count that needs to be converted to 0 when null. At some point it needs to be
 *     rewritten to a normal case statment, but it needs to be rewritten above the correlate to
 *     preserve the type nullablilty. Basically we will see a query plan like so:
 *     <pre>
 * LogicalCorrelate(correlation=[$cor0], joinType=[left], requiredColumns=[{0}])
 *   LogicalTableScan(table=[[DEPT]])
 *   LogicalProject($f0=[true], $f1=[ADJUST_COUNT($0)])
 *     LogicalFilter(condition=[=($cor0.DEPTNO, $7)])
 *       LogicalTableScan(table=[[EMP]])
 * </pre>
 *     And we need to rewrite it to:
 *     <pre>
 * LogicalProject(DEPTNO=[$0], DNAME=[$1], LOC=[$2], $f0=[$3], $f1=[CASE(IS NULL($4), 0:BIGINT, $4)])
 *   LogicalCorrelate(correlation=[$cor0], joinType=[left], requiredColumns=[{}])
 *     LogicalTableScan(table=[[DEPT]])
 *     LogicalProject($f0=[true], $f1=[$0])
 *       LogicalFilter(condition=[=($cor0.DEPTNO, $7)])
 *         LogicalTableScan(table=[[EMP]])
 * </pre>
 */
public final class CorrelateAdjustCountProjectRule
    extends RelRule<CorrelateAdjustCountProjectRule.Config> implements TransformationRule {
  private CorrelateAdjustCountProjectRule(Config config) {
    super(config);
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    Correlate correlate = call.rel(0);
    RelNode left = call.rel(1);
    Project adjustCountProject = call.rel(2);

    RelBuilder relBuilder = call.builder();

    List<RexNode> newProjects = new ArrayList<>();
    Set<Integer> adjustCountIndexes = new HashSet<>();
    for (int i = 0; i < adjustCountProject.getProjects().size(); i++) {
      RexNode oldProject = adjustCountProject.getProjects().get(i);
      final Boolean[] isTopLevel = {true};
      int finalI = i;
      RexNode newProject =
          oldProject.accept(
              new RexShuttle() {
                @Override
                public RexNode visitCall(RexCall call) {
                  RexNode visited = super.visitCall(call);
                  if (visited instanceof RexCall) {
                    RexCall visitedCall = (RexCall) visited;
                    if (visitedCall
                        .getOperator()
                        .equals(AggregateCorrelatedFilterTransposeRule.ADJUST_COUNT_OPERATOR)) {
                      if (isTopLevel[0]) {
                        // Only add case statement if ADJUST_COUNT is the top level operator.
                        adjustCountIndexes.add(finalI);
                      }
                      return visitedCall.getOperands().get(0);
                    }
                  }

                  isTopLevel[0] = false;
                  return visited;
                }
              });

      newProjects.add(newProject);
    }

    relBuilder
        .push(left)
        .push(adjustCountProject.getInput())
        .project(newProjects, adjustCountProject.getRowType().getFieldNames(), true)
        .correlate(correlate.getJoinType(), correlate.getCorrelationId(), ImmutableList.of());
    List<RexNode> caseStatementProjects = new ArrayList<>();
    int leftCount = left.getRowType().getFieldCount();
    for (int i = 0; i < leftCount; i++) {
      caseStatementProjects.add(relBuilder.field(i));
    }

    for (int i = 0; i < adjustCountProject.getProjects().size(); i++) {
      RexNode field = relBuilder.field(leftCount + i);
      if (adjustCountIndexes.contains(i)) {
        RexBuilder rexBuilder = relBuilder.getRexBuilder();
        field =
            rexBuilder.makeCall(
                // Hack to preserve the nullability
                field.getType(),
                SqlStdOperatorTable.CASE,
                ImmutableList.of(
                    rexBuilder.makeCall(SqlStdOperatorTable.IS_NULL, field),
                    rexBuilder.makeZeroLiteral(
                        rexBuilder.getTypeFactory().createSqlType(SqlTypeName.BIGINT)),
                    field));
      }

      caseStatementProjects.add(field);
    }

    RelNode transposed =
        relBuilder
            .project(caseStatementProjects, correlate.getRowType().getFieldNames(), true)
            .build();

    call.transformTo(transposed);
  }

  public interface Config extends RelRule.Config {
    Config DEFAULT =
        EMPTY
            .withOperandSupplier(
                b0 ->
                    b0.operand(Correlate.class)
                        .inputs(
                            b1 -> b1.operand(RelNode.class).anyInputs(),
                            b2 ->
                                b2.operand(Project.class)
                                    .predicate(Utils::hasAdjustCountOperator)
                                    .anyInputs()))
            .withDescription(CorrelateAdjustCountProjectRule.class.getSimpleName())
            .as(CorrelateAdjustCountProjectRule.Config.class);

    @Override
    default CorrelateAdjustCountProjectRule toRule() {
      return new CorrelateAdjustCountProjectRule(this);
    }
  }
}
