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

import java.util.List;
import java.util.function.Predicate;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.plan.hep.HepRelVertex;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.rules.TransformationRule;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexProgram;
import org.apache.calcite.rex.RexProgramBuilder;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.ImmutableBeans;

/**
 * Planner rule that matches a {@link org.apache.calcite.rel.core.Join} one of whose inputs is a
 * {@link org.apache.calcite.rel.core.Filter}, and pulls the filter(s) up and pushes Join down.
 *
 * <p>Filter(s) are pulled up if the {@link org.apache.calcite.rel.core.Filter} doesn't originate
 * from a null generating input in an outer join.
 *
 * <p>If there are outer joins, the Filter from left will be pulled up only if there is a left outer
 * join. On the other hand, the right Filter will be pulled up only it there is a right outer join.
 */
public final class JoinFilterTransposeRule extends RelRule<JoinFilterTransposeRule.Config>
    implements TransformationRule {

  // ~ Static fields/initializers ---------------------------------------------

  // ~ Constructors -----------------------------------------------------------

  /** Creates a JoinFilterTransposeRule */
  private JoinFilterTransposeRule(Config config) {
    super(config);
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    Join joinRel = call.rel(0);
    JoinRelType joinType = joinRel.getJoinType();

    Filter leftFilter;
    Filter rightFilter;
    RelNode leftJoinChild;
    RelNode rightJoinChild;

    if (hasLeftFilter(joinRel)
        && !joinType.generatesNullsOnLeft()
        && config.shouldPullupFilter().test(getLeftFilter(joinRel))) {
      leftFilter = getLeftFilter(joinRel);
      leftJoinChild = getFilterChild(leftFilter);
    } else {
      leftFilter = null;
      leftJoinChild = joinRel.getLeft();
    }

    if (hasRightFilter(joinRel)
        && !joinType.generatesNullsOnRight()
        && config.shouldPullupFilter().test(getRightFilter(joinRel))) {
      rightFilter = getRightFilter(joinRel);
      rightJoinChild = getFilterChild(rightFilter);
    } else {
      rightFilter = null;
      rightJoinChild = joinRel.getRight();
    }

    if ((leftFilter == null && rightFilter == null)
        || // No Filter found
        (leftFilter == null && joinType == JoinRelType.LEFT)
        || // Right Filter with left outer join
        (rightFilter == null && joinType == JoinRelType.RIGHT) // Left Filter with right outer join
    ) {
      return;
    }

    Join newJoinRel =
        joinRel.copy(
            joinRel.getTraitSet(),
            joinRel.getCondition(),
            leftJoinChild,
            rightJoinChild,
            joinRel.getJoinType(),
            joinRel.isSemiJoinDone());

    // Use RexPrograms to merge the two Filters into a single program
    // so we can convert the two Filter conditions to directly
    // reference the new pushed down Join
    RelDataType joinRowType = newJoinRel.getRowType();
    List<RelDataTypeField> joinChildrenFields = joinRowType.getFieldList();
    RexBuilder rexBuilderFilter = joinRel.getCluster().getRexBuilder();

    RexProgram leftFilterProgram = // Top program
        createProgram(leftFilter, joinRowType, rexBuilderFilter, joinChildrenFields, 0);

    // For the RHS filter expressions, shift them to the right by the number of fields on the LHS.
    int nFieldsLeft = leftJoinChild.getRowType().getFieldList().size();
    RexProgram rightFilterProgram = // Bottom program
        createProgram(rightFilter, joinRowType, rexBuilderFilter, joinChildrenFields, nFieldsLeft);

    // The way we are merging left and right filter programs is that we are treating left filter
    // program as a
    // top program which will get input from right filter program treated as a bottom program, which
    // in turn
    // gets input from the Join we are pushing down. For more info, see:
    // org.apache.calcite.rex.RexProgramBuilder.mergePrograms(RexProgram, RexProgram, RexBuilder)
    RexProgram mergedFilterProgram;
    RexNode newFilterCondition;
    if (leftFilter != null && rightFilter != null) {
      if (joinType == JoinRelType.LEFT) { // Left outer join, only pull left Filter up.
        mergedFilterProgram = leftFilterProgram;
      } else if (joinType == JoinRelType.RIGHT) { // Right outer join, only pull right Filter up.
        mergedFilterProgram = rightFilterProgram;
      } else { // Full outer join or inner join, merge both Filters.
        mergedFilterProgram =
            RexProgramBuilder.mergePrograms(
                leftFilterProgram, rightFilterProgram, rexBuilderFilter);
      }
    } else if (leftFilter != null) { // Left inner join
      mergedFilterProgram = leftFilterProgram;
    } else { // Right inner join
      mergedFilterProgram = rightFilterProgram;
    }
    newFilterCondition = mergedFilterProgram.expandLocalRef(mergedFilterProgram.getCondition());

    // Finally, create the filter on top of the join
    final RelBuilder relBuilder = call.builder();
    relBuilder.push(newJoinRel);
    relBuilder.filter(newFilterCondition);

    call.transformTo(relBuilder.build());
  }

  private boolean hasLeftFilter(Join join) {
    RelNode left = join.getLeft();
    if (left instanceof Filter) {
      return true;
    }

    // This is to work around the fact that joins could have filters on either side
    // So it's not easy to have the config unwrap this for us
    // Ideally the rule should only pullup one side at a time
    if (left instanceof HepRelVertex) {
      HepRelVertex hepRelVertex = (HepRelVertex) left;
      return hepRelVertex.getCurrentRel() instanceof Filter;
    }

    return false;
  }

  private boolean hasRightFilter(Join join) {
    RelNode right = join.getRight();
    if (right instanceof Filter) {
      return true;
    }

    // This is to work around the fact that joins could have filters on either side
    // So it's not easy to have the config unwrap this for us
    // Ideally the rule should only pullup one side at a time
    if (right instanceof HepRelVertex) {
      HepRelVertex hepRelVertex = (HepRelVertex) right;
      return hepRelVertex.getCurrentRel() instanceof Filter;
    }

    return false;
  }

  private Filter getLeftFilter(Join join) {
    RelNode left = join.getLeft();
    if (left instanceof Filter) {
      return (Filter) left;
    }

    // This is to work around the fact that joins could have filters on either side
    // So it's not easy to have the config unwrap this for us
    // Ideally the rule should only pullup one side at a time
    if (left instanceof HepRelVertex) {
      HepRelVertex hepRelVertex = (HepRelVertex) left;
      return (Filter) hepRelVertex.getCurrentRel();
    }

    throw new RuntimeException("Expected join to have a left filter.");
  }

  private Filter getRightFilter(Join join) {
    RelNode right = join.getRight();
    if (right instanceof Filter) {
      return (Filter) right;
    }

    // This is to work around the fact that joins could have filters on either side
    // So it's not easy to have the config unwrap this for us
    // Ideally the rule should only pullup one side at a time
    if (right instanceof HepRelVertex) {
      HepRelVertex hepRelVertex = (HepRelVertex) right;
      return (Filter) hepRelVertex.getCurrentRel();
    }

    throw new RuntimeException("Expected join to have a right filter.");
  }

  /**
   * Returns the child of the filter that will be used as input into the new Join once the filters
   * are pulled above the Join.
   *
   * @param filter filter RelNode
   * @return child of the filter that will be used as input into the new Join once the filters are
   *     pulled above the Join
   */
  private RelNode getFilterChild(Filter filter) {
    return filter.getInput();
  }

  /**
   * Creates a RexProgram corresponding to the Filter
   *
   * @param filter the Filter
   * @param joinRowType row type of new Join being pushed down which is used by the
   *     RexProgramBuilder, since the Filter's input will be the Join that is going to be pushed
   *     down
   * @param rexBuilder the rex builder
   * @param joinChildrenFields concatenation of the fields from the left and right join inputs (once
   *     the filters have been removed)
   * @param adjustmentAmount the amount the expressions need to be shifted by
   * @return created RexProgram
   */
  private RexProgram createProgram(
      Filter filter,
      RelDataType joinRowType,
      RexBuilder rexBuilder,
      List<RelDataTypeField> joinChildrenFields,
      int adjustmentAmount) {
    if (filter != null) {
      RexProgramBuilder programBuilder = new RexProgramBuilder(joinRowType, rexBuilder);
      programBuilder.addIdentity();
      RexNode filterCondition = filter.getCondition();
      if (adjustmentAmount != 0) {
        List<RelDataTypeField> childFields = filter.getRowType().getFieldList();
        int nChildFields = childFields.size();
        int[] adjustments = new int[nChildFields];
        for (int i = 0; i < nChildFields; i++) {
          adjustments[i] = adjustmentAmount;
        }
        filterCondition =
            filterCondition.accept(
                new RelOptUtil.RexInputConverter(
                    rexBuilder, childFields, joinChildrenFields, adjustments));
      }
      programBuilder.addCondition(filterCondition);
      return programBuilder.getProgram();
    } else {
      return null;
    }
  }

  /** Rule configuration. */
  public interface Config extends RelRule.Config {
    Config LEFT_FILTER =
        EMPTY
            .withOperandSupplier(
                b0 ->
                    b0.operand(Join.class)
                        .inputs(
                            b1 -> b1.operand(Filter.class).anyInputs(),
                            b2 -> b2.operand(RelNode.class).anyInputs()))
            .withDescription("JoinFilterTransposeRule(Filter-Other)")
            .as(Config.class)
            .withShouldPullupFilter(filter -> true);

    Config RIGHT_FILTER =
        EMPTY
            .withOperandSupplier(
                b0 ->
                    b0.operand(Join.class)
                        .inputs(
                            b1 -> b1.operand(RelNode.class).anyInputs(),
                            b2 -> b2.operand(Filter.class).anyInputs()))
            .withDescription("JoinFilterTransposeRule(Other-Filter)")
            .as(Config.class)
            .withShouldPullupFilter(filter -> true);

    @Override
    default JoinFilterTransposeRule toRule() {
      return new JoinFilterTransposeRule(this);
    }

    @ImmutableBeans.Property
    Predicate<Filter> shouldPullupFilter();

    JoinFilterTransposeRule.Config withShouldPullupFilter(Predicate<Filter> predicate);
  }
}
