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

import com.dremio.exec.planner.StatelessRelShuttleImpl;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.plan.hep.HepRelVertex;
import org.apache.calcite.plan.volcano.RelSubset;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Correlate;
import org.apache.calcite.rel.core.CorrelationId;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.rules.PushProjector;
import org.apache.calcite.rel.rules.TransformationRule;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCorrelVariable;
import org.apache.calcite.rex.RexFieldAccess;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexOver;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.util.BitSets;
import org.apache.calcite.util.ImmutableBeans;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.Util;

/** Modeled off of Calcite's ProjectCorrelateTransposeRule to support the RelRule.Config pattern */
public final class ProjectCorrelateTransposeRule
    extends RelRule<ProjectCorrelateTransposeRule.Config> implements TransformationRule {
  private ProjectCorrelateTransposeRule(Config config) {
    super(config);
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    Project origProj = call.rel(0);
    final Correlate corr = call.rel(1);

    // locate all fields referenced in the projection
    // determine which inputs are referenced in the projection;
    // if all fields are being referenced and there are no
    // special expressions, no point in proceeding any further
    PushProjector pushProject =
        new PushProjector(
            origProj,
            call.builder().literal(true),
            corr,
            config.getPreserveExprCondition(),
            call.builder());
    if (pushProject.locateAllRefs()) {
      return;
    }

    // create left and right projections, projecting only those
    // fields referenced on each side
    RelNode leftProjRel = pushProject.createProjectRefsAndExprs(corr.getLeft(), true, false);
    RelNode rightProjRel = pushProject.createProjectRefsAndExprs(corr.getRight(), true, true);

    Map<Integer, Integer> requiredColsMap = new HashMap<>();

    // adjust requiredColumns that reference the projected columns
    int[] adjustments = pushProject.getAdjustments();
    BitSet updatedBits = new BitSet();
    for (Integer col : corr.getRequiredColumns()) {
      int newCol = col + adjustments[col];
      updatedBits.set(newCol);
      requiredColsMap.put(col, newCol);
    }

    RexBuilder rexBuilder = call.builder().getRexBuilder();

    CorrelationId correlationId = corr.getCluster().createCorrel();
    RexCorrelVariable rexCorrel =
        (RexCorrelVariable) rexBuilder.makeCorrel(leftProjRel.getRowType(), correlationId);

    // updates RexCorrelVariable and sets actual RelDataType for RexFieldAccess
    rightProjRel =
        rightProjRel.accept(
            new RelNodesExprsHandler(
                new RexFieldAccessReplacer(
                    corr.getCorrelationId(), rexCorrel, rexBuilder, requiredColsMap)));

    // create a new correlate with the projected children
    Correlate newCorrRel =
        corr.copy(
            corr.getTraitSet(),
            leftProjRel,
            rightProjRel,
            correlationId,
            ImmutableBitSet.of(BitSets.toIter(updatedBits)),
            corr.getJoinType());

    // put the original project on top of the correlate, converting it to
    // reference the modified projection list
    RelNode topProject = pushProject.createNewProject(newCorrRel, adjustments);

    call.transformTo(topProject);
  }

  /** Visitor for RexNodes which replaces {@link RexCorrelVariable} with specified. */
  public static class RexFieldAccessReplacer extends RexShuttle {
    private final RexBuilder builder;
    private final CorrelationId rexCorrelVariableToReplace;
    private final RexCorrelVariable rexCorrelVariable;
    private final Map<Integer, Integer> requiredColsMap;

    public RexFieldAccessReplacer(
        CorrelationId rexCorrelVariableToReplace,
        RexCorrelVariable rexCorrelVariable,
        RexBuilder builder,
        Map<Integer, Integer> requiredColsMap) {
      this.rexCorrelVariableToReplace = rexCorrelVariableToReplace;
      this.rexCorrelVariable = rexCorrelVariable;
      this.builder = builder;
      this.requiredColsMap = requiredColsMap;
    }

    @Override
    public RexNode visitCorrelVariable(RexCorrelVariable variable) {
      if (variable.id.equals(rexCorrelVariableToReplace)) {
        return rexCorrelVariable;
      }
      return variable;
    }

    @Override
    public RexNode visitFieldAccess(RexFieldAccess fieldAccess) {
      RexNode refExpr = fieldAccess.getReferenceExpr().accept(this);
      // creates new RexFieldAccess instance for the case when referenceExpr was replaced.
      // Otherwise calls super method.
      if (refExpr == rexCorrelVariable) {
        return builder.makeFieldAccess(
            refExpr, requiredColsMap.get(fieldAccess.getField().getIndex()));
      }
      return super.visitFieldAccess(fieldAccess);
    }
  }

  /**
   * Visitor for RelNodes which applies specified {@link RexShuttle} visitor for every node in the
   * tree.
   */
  public static class RelNodesExprsHandler extends StatelessRelShuttleImpl {
    private final RexShuttle rexVisitor;

    public RelNodesExprsHandler(RexShuttle rexVisitor) {
      this.rexVisitor = rexVisitor;
    }

    @Override
    protected RelNode visitChild(RelNode parent, int i, RelNode child) {
      if (child instanceof HepRelVertex) {
        child = ((HepRelVertex) child).getCurrentRel();
      } else if (child instanceof RelSubset) {
        RelSubset subset = (RelSubset) child;
        child = Util.first(subset.getBest(), subset.getOriginal());
      }
      return super.visitChild(parent, i, child).accept(rexVisitor);
    }
  }

  public interface Config extends RelRule.Config {
    Config DEFAULT =
        RelRule.Config.EMPTY
            .withDescription(ProjectCorrelateTransposeRule.class.getSimpleName())
            .withOperandSupplier(
                op ->
                    op.operand(Project.class)
                        .oneInput(op2 -> op2.operand(Correlate.class).anyInputs()))
            .as(Config.class)
            .withPreserveExprCondition(expr -> !(expr instanceof RexOver));

    @Override
    default ProjectCorrelateTransposeRule toRule() {
      return new ProjectCorrelateTransposeRule(this);
    }

    @ImmutableBeans.Property
    PushProjector.ExprCondition getPreserveExprCondition();

    ProjectCorrelateTransposeRule.Config withPreserveExprCondition(
        PushProjector.ExprCondition preserveExprCondition);
  }
}
