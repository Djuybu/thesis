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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.plan.Strong;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Correlate;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.rules.TransformationRule;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.util.ImmutableBeans;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.Pair;

/**
 * This class pulls projects above Correlates, which is useful for reflection matching. It's modeled
 * off of JoinProjectTransposeRule and won't be needed after we decorrelate in normalization.
 */
public final class CorrelateProjectTransposeRule
    extends RelRule<CorrelateProjectTransposeRule.Config> implements TransformationRule {
  private CorrelateProjectTransposeRule(Config config) {
    super(config);
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    Correlate correlate = call.rel(0);
    JoinRelType joinType = correlate.getJoinType();

    Project leftProject;
    Project rightProject;
    RelNode newLeftChild;
    RelNode newRightChild;

    if (config.isLeft()) {
      leftProject = call.rel(1);
      rightProject = null;

      /*
       Normally we can not pull a Project above the null generating side of a Join, since it
       might convert the null into a non-null value, which violates the correctness of the query.
       An exception can be made for "strong" expressions, which return null if and only if all
       inputs in the expression are null.
      */
      if (joinType.generatesNullsOnLeft() && !Strong.allStrong(leftProject.getProjects())) {
        return;
      }

      newLeftChild = leftProject.getInput();
      newRightChild = call.rel(2);
    } else {
      leftProject = null;
      rightProject = call.rel(2);

      if (joinType.generatesNullsOnRight() && !Strong.allStrong(rightProject.getProjects())) {
        return;
      }

      newLeftChild = call.rel(1);
      newRightChild = rightProject.getInput();
    }

    int leftCorrelateSize = correlate.getLeft().getRowType().getFieldCount();

    // Calculate the requiredColumns for the new correlate node
    ImmutableBitSet.Builder newRequiredColumnsBuilder = ImmutableBitSet.builder();
    ImmutableBitSet originalRequiredColumns = correlate.getRequiredColumns();
    for (int oldIndex : originalRequiredColumns) {
      int newIndex;
      if (oldIndex < leftCorrelateSize) {
        // referencing left side of correlate
        int offset = oldIndex;
        if (config.isLeft()) {
          // Reference what the left project is referencing
          RexNode projectExpr = leftProject.getProjects().get(offset);
          if (!(projectExpr instanceof RexInputRef)) {
            return;
          }

          offset = ((RexInputRef) projectExpr).getIndex();
        }

        newIndex = offset;
      } else {
        int offset = oldIndex - leftCorrelateSize;
        // referencing right side of correlate
        if (!config.isLeft()) {
          // Reference what the right project is referencing
          RexNode projectExpr = rightProject.getProjects().get(offset);
          if (!(projectExpr instanceof RexInputRef)) {
            return;
          }

          offset = ((RexInputRef) projectExpr).getIndex();
        }

        newIndex = newLeftChild.getRowType().getFieldCount() + offset;
      }

      newRequiredColumnsBuilder.set(newIndex);
    }

    ImmutableBitSet newRequiredColumns = newRequiredColumnsBuilder.build();

    Correlate newCorrelate =
        correlate.copy(
            correlate.getTraitSet(),
            newLeftChild,
            newRightChild,
            correlate.getCorrelationId(),
            newRequiredColumns,
            joinType);

    // Create projection expressions, combining the projection expressions
    // from the projects that feed into the join.  For the RHS projection
    // expressions, shift them to the right by the number of fields on
    // the LHS.  If the join input was not a projection, simply create
    // references to the inputs.
    final RexBuilder rexBuilder = correlate.getCluster().getRexBuilder();
    final List<Pair<RexNode, String>> newProjects = new ArrayList<>();
    createProjectExprs(
        leftProject,
        newLeftChild,
        0,
        rexBuilder,
        newCorrelate.getRowType().getFieldList(),
        newProjects);
    createProjectExprs(
        rightProject,
        newRightChild,
        newLeftChild.getRowType().getFieldCount(),
        rexBuilder,
        newCorrelate.getRowType().getFieldList(),
        newProjects);

    List<RexNode> newProjectExpressions =
        newProjects.stream().map(pair -> pair.left).collect(Collectors.toList());
    List<String> newProjectAliases =
        newProjects.stream().map(pair -> pair.right).collect(Collectors.toList());

    RelNode transposed =
        call.builder()
            .push(newCorrelate)
            .project(newProjectExpressions, newProjectAliases, true)
            .build();

    call.transformTo(transposed);
  }

  // Copied and pasted from JoinProjectTransposeRule
  private static void createProjectExprs(
      Project projRel,
      RelNode joinChild,
      int adjustmentAmount,
      RexBuilder rexBuilder,
      List<RelDataTypeField> joinChildrenFields,
      List<Pair<RexNode, String>> projects) {
    List<RelDataTypeField> childFields = joinChild.getRowType().getFieldList();
    if (projRel != null) {
      List<Pair<RexNode, String>> namedProjects = projRel.getNamedProjects();
      int nChildFields = childFields.size();
      int[] adjustments = new int[nChildFields];
      for (int i = 0; i < nChildFields; i++) {
        adjustments[i] = adjustmentAmount;
      }
      for (Pair<RexNode, String> pair : namedProjects) {
        RexNode e = pair.left;
        if (adjustmentAmount != 0) {
          // shift the references by the adjustment amount
          e =
              e.accept(
                  new RelOptUtil.RexInputConverter(
                      rexBuilder, childFields, joinChildrenFields, adjustments));
        }
        projects.add(Pair.of(e, pair.right));
      }
    } else {
      // no projection; just create references to the inputs
      for (int i = 0; i < childFields.size(); i++) {
        final RelDataTypeField field = childFields.get(i);
        projects.add(
            Pair.of(
                (RexNode) rexBuilder.makeInputRef(field.getType(), i + adjustmentAmount),
                field.getName()));
      }
    }
  }

  public interface Config extends RelRule.Config {
    Config LEFT =
        EMPTY
            .withOperandSupplier(
                b0 ->
                    b0.operand(Correlate.class)
                        .inputs(
                            b1 -> b1.operand(Project.class).anyInputs(),
                            b2 -> b2.operand(RelNode.class).anyInputs()))
            .withDescription("CorrelateProjectTransposeRule(Project-Other)")
            .as(CorrelateProjectTransposeRule.Config.class)
            .withLeft(true);

    Config RIGHT =
        EMPTY
            .withOperandSupplier(
                b0 ->
                    b0.operand(Correlate.class)
                        .inputs(
                            b1 -> b1.operand(RelNode.class).anyInputs(),
                            b2 -> b2.operand(Project.class).anyInputs()))
            .withDescription("CorrelateProjectTransposeRule(Other-Project)")
            .as(CorrelateProjectTransposeRule.Config.class)
            .withLeft(false);

    @ImmutableBeans.Property
    @ImmutableBeans.BooleanDefault(false)
    boolean isLeft();

    /** Sets {@link #isLeft()}. */
    Config withLeft(boolean left);

    @Override
    default CorrelateProjectTransposeRule toRule() {
      return new CorrelateProjectTransposeRule(this);
    }
  }
}
