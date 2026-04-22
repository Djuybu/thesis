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
package com.dremio.exec.planner.cost;

import com.dremio.exec.planner.acceleration.ExpansionNode;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.planner.physical.PrelUtil;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptPredicateList;
import org.apache.calcite.plan.Strong;
import org.apache.calcite.plan.volcano.RelSubset;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Correlate;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Union;
import org.apache.calcite.rel.metadata.BuiltInMetadata;
import org.apache.calcite.rel.metadata.MetadataDef;
import org.apache.calcite.rel.metadata.MetadataHandler;
import org.apache.calcite.rel.metadata.ReflectiveRelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexExecutor;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexSimplify;
import org.apache.calcite.rex.RexUnknownAs;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.util.BuiltInMethod;
import org.apache.calcite.util.ControlFlowException;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.Util;

/** a metadata handler for pulling predicates. */
public class RelMdPredicates implements MetadataHandler<BuiltInMetadata.Predicates> {
  public static final RelMetadataProvider SOURCE =
      ReflectiveRelMetadataProvider.reflectiveSource(
          BuiltInMethod.PREDICATES.method, new RelMdPredicates());

  // Not used...
  @Override
  public MetadataDef<BuiltInMetadata.Predicates> getDef() {
    return BuiltInMetadata.Predicates.DEF;
  }

  public RelOptPredicateList getPredicates(RelSubset subset, RelMetadataQuery mq) {
    // Currently disabled in Calcite upstream
    // Only go over the best node if it exists, and try the original node otherwise
    RelOptPredicateList predicates =
        mq.getPulledUpPredicates(Util.first(subset.getBest(), subset.getOriginal()));
    return predicates;
  }

  /*
   * Remove this method once we cherry-pick https://issues.apache.org/jira/browse/CALCITE-5466
   * to the Dremio calcite.
   */
  /** Infers predicates for a correlate node. */
  public RelOptPredicateList getPredicates(Correlate correlate, RelMetadataQuery mq) {
    return mq.getPulledUpPredicates(correlate.getLeft());
  }

  public RelOptPredicateList getPredicates(ExpansionNode expansionNode, RelMetadataQuery mq) {
    if (expansionNode.considerForPullUpPredicate()) {
      return mq.getPulledUpPredicates(expansionNode.getInput());
    }
    return RelOptPredicateList.EMPTY;
  }

  /*
   * This method is copied entirely from calcite code just to change the simplify call parameter
   * from RexUnknownAs.FALSE to RexUnknownAs.UNKNOWN. This was changed in CALCITE-2338, which
   * was causing the simplification to take longer.
   */
  /** Infers predicates for a Union node. */
  public RelOptPredicateList getPredicates(Union union, RelMetadataQuery mq) {
    final RexBuilder rexBuilder = union.getCluster().getRexBuilder();

    Set<RexNode> finalPredicates = new HashSet<>();
    final List<RexNode> finalResidualPredicates = new ArrayList<>();
    for (Ord<RelNode> input : Ord.zip(union.getInputs())) {
      RelOptPredicateList info = mq.getPulledUpPredicates(input.e);
      if (info.pulledUpPredicates.isEmpty()) {
        return RelOptPredicateList.EMPTY;
      }
      final Set<RexNode> predicates = new HashSet<>();
      final List<RexNode> residualPredicates = new ArrayList<>();
      for (RexNode pred : info.pulledUpPredicates) {
        if (input.i == 0) {
          predicates.add(pred);
          continue;
        }
        if (finalPredicates.contains(pred)) {
          predicates.add(pred);
        } else {
          residualPredicates.add(pred);
        }
      }
      // Add new residual predicates
      finalResidualPredicates.add(RexUtil.composeConjunction(rexBuilder, residualPredicates));
      // Add those that are not part of the final set to residual
      for (RexNode e : finalPredicates) {
        if (!predicates.contains(e)) {
          // This node was in previous union inputs, but it is not in this one
          for (int j = 0; j < input.i; j++) {
            finalResidualPredicates.set(
                j,
                RexUtil.composeConjunction(
                    rexBuilder, Arrays.asList(finalResidualPredicates.get(j), e)));
          }
        }
      }
      // Final predicates
      finalPredicates = predicates;
    }

    final List<RexNode> predicates = new ArrayList<>(finalPredicates);
    final RelOptCluster cluster = union.getCluster();
    final RexExecutor executor = Util.first(cluster.getPlanner().getExecutor(), RexUtil.EXECUTOR);
    RexNode disjunctivePredicate =
        new RexSimplify(rexBuilder, RelOptPredicateList.EMPTY, executor)
            .simplifyUnknownAs(
                rexBuilder.makeCall(SqlStdOperatorTable.OR, finalResidualPredicates),
                RexUnknownAs.UNKNOWN);
    if (!disjunctivePredicate.isAlwaysTrue()) {
      predicates.add(disjunctivePredicate);
    }
    return RelOptPredicateList.of(rexBuilder, predicates);
  }

  /**
   * Infers predicates for a project.
   *
   * <ol>
   *   <li>create a mapping from input to projection. Map only positions that directly reference an
   *       input column.
   *   <li>Expressions that only contain above columns are retained in the Project's pullExpressions
   *       list.
   *   <li>For e.g. expression 'a + e = 9' below will not be pulled up because 'e' is not in the
   *       projection list.
   *       <blockquote>
   *       <pre>
   * inputPullUpExprs:      {a &gt; 7, b + c &lt; 10, a + e = 9}
   * projectionExprs:       {a, b, c, e / 2}
   * projectionPullupExprs: {a &gt; 7, b + c &lt; 10}
   * </pre>
   *       </blockquote>
   * </ol>
   */
  public RelOptPredicateList getPredicates(Project project, RelMetadataQuery mq) {
    final RelNode input = project.getInput();
    final RexBuilder rexBuilder = project.getCluster().getRexBuilder();
    final RelOptPredicateList inputInfo = mq.getPulledUpPredicates(input);
    final List<RexNode> projectPullUpPredicates = new ArrayList<>();

    // Store all the projected columns for a given projected expression.
    // E.g., Project($0 = $3, $1 = $3), store $3 -> [0, 1]
    Map<ComparableRexNode, List<Integer>> positionsList = new HashMap<>();
    for (Ord<RexNode> o : Ord.zip(project.getProjects())) {
      ComparableRexNode keyNode = new ComparableRexNode(o.e);
      positionsList.putIfAbsent(keyNode, new ArrayList<>());
      positionsList.get(keyNode).add(o.i);
    }

    PlannerSettings plannerSettings = PrelUtil.getPlannerSettings(project.getCluster());

    long maxPermutations =
        plannerSettings == null
            ? PlannerSettings.MAX_PREDICATE_PERMUTATIONS.getDefault().getNumVal()
            : plannerSettings.getOptions().getOption(PlannerSettings.MAX_PREDICATE_PERMUTATIONS);

    // Go over child pullUpPredicates. If a predicate is made from projected columns
    // then pull up the predicate in terms of the projected columns.
    // If a predicate contains columns outside the projection, see if we can pull up
    // a weaker predicate on the projection columns it does use.
    for (RexNode r : inputInfo.pulledUpPredicates) {
      try {
        Map<ComparableRexNode, IndexValueHolder> referenceMap = new HashMap<>();
        int i = 0;
        do {
          final RexNode newRex =
              r.accept(new ProjectMapper(rexBuilder, project, referenceMap, positionsList));
          projectPullUpPredicates.add(newRex);
        } while (++i < maxPermutations && updateReferences(referenceMap, positionsList));
      } catch (ProjectMapper.ProjectColumnMiss e) {
        // We do not add predicate which can not be referenced by these project fields.
      }
    }

    // Project can also generate constants. We need to include them.
    for (Ord<RexNode> expr : Ord.zip(project.getProjects())) {
      if (RexLiteral.isNullLiteral(expr.e)) {
        projectPullUpPredicates.add(
            rexBuilder.makeCall(
                SqlStdOperatorTable.IS_NULL, rexBuilder.makeInputRef(project, expr.i)));
      } else if (RexUtil.isConstant(expr.e)) {
        final List<RexNode> args =
            ImmutableList.of(rexBuilder.makeInputRef(project, expr.i), expr.e);
        final SqlOperator op =
            args.get(0).getType().isNullable() || args.get(1).getType().isNullable()
                ? SqlStdOperatorTable.IS_NOT_DISTINCT_FROM
                : SqlStdOperatorTable.EQUALS;
        projectPullUpPredicates.add(rexBuilder.makeCall(op, args));
      }
    }
    return RelOptPredicateList.of(rexBuilder, projectPullUpPredicates);
  }

  /** Key for rexnodes in a map. */
  private static class ComparableRexNode {
    private final RexNode node;

    ComparableRexNode(RexNode node) {
      this.node = node;
    }

    @Override
    public int hashCode() {
      return Objects.hash(node.toString(), node.getType().getFullTypeString());
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof ComparableRexNode)) {
        return false;
      }
      if (o == this) {
        return true;
      }
      ComparableRexNode other = (ComparableRexNode) o;
      return other.node.toString().equals(this.node.toString())
          && other.node.getType().equals(this.node.getType());
    }
  }

  /**
   * A shuttle that stores a list of projection columns. The shuttle can be used to visit child
   * predicates to see if they can be expressed in terms of projection columns.
   */
  private static class ProjectMapper extends RexShuttle {
    private final Map<ComparableRexNode, IndexValueHolder> currPos;
    private final RexBuilder rexBuilder;
    private final Project project;

    // RexCalls can be composed of RexCalls.
    // When mapping a RexCall, we need to know if we are mapping the parent RexCall
    // or a child. If we can't fully map the parent,
    // then we will try pull up weakened partial predicates.
    private boolean nestedCall = false;
    private List<RexNode> nestedPartials;
    private final Map<ComparableRexNode, List<Integer>> positions;

    ProjectMapper(
        RexBuilder rexBuilder,
        Project project,
        Map<ComparableRexNode, IndexValueHolder> currPos,
        Map<ComparableRexNode, List<Integer>> positions) {
      this.currPos = currPos;
      this.positions = positions;
      this.rexBuilder = rexBuilder;
      this.project = project;
    }

    /**
     * @param node The node is either a input ref or call.
     * @return If the ref or call is projected return the projection column reference. Otherwise,
     *     null.
     */
    protected RexNode getReference(final RexNode node) {
      ComparableRexNode comparableRexNode = new ComparableRexNode(node);
      final List<Integer> indices = positions.get(comparableRexNode);
      if (indices != null) {
        int pos = 0;
        if (currPos.containsKey(comparableRexNode)) {
          pos = currPos.get(comparableRexNode).getIndex();
        } else {
          currPos.put(comparableRexNode, new IndexValueHolder(0));
        }
        return new RexInputRef(indices.get(pos), node.getType());
      }
      return null;
    }

    @Override
    public RexNode visitLiteral(final RexLiteral literal) {
      return literal;
    }

    /**
     * Exception thrown to exit a matcher. Not really an error. It means nothing new can be said of
     * the projected columns.
     */
    private static class ProjectColumnMiss extends ControlFlowException {
      @SuppressWarnings("ThrowableInstanceNeverThrown")
      public static final ProjectMapper.ProjectColumnMiss INSTANCE =
          new ProjectMapper.ProjectColumnMiss();
    }

    /**
     * Exception thrown within a matcher to communicate a subset of the given statement could be put
     * in terms of project columns. Not really an error.
     */
    private static class ProjectColumnPartialMiss extends ControlFlowException {
      @SuppressWarnings("ThrowableInstanceNeverThrown")
      public static final ProjectMapper.ProjectColumnPartialMiss INSTANCE =
          new ProjectMapper.ProjectColumnPartialMiss();
    }

    /**
     * @param ref In terms of input columns.
     * @return ref in terms of project columns, or null.
     */
    @Override
    public RexNode visitInputRef(final RexInputRef ref) {
      final RexNode newRef = getReference(ref);
      if (newRef != null) {
        return newRef;
      }
      throw ProjectMapper.ProjectColumnMiss.INSTANCE;
    }

    /**
     * @param call a RexCall in terms of the input columns.
     * @return a RexCall in terms of project columns, a weakened predicate in the case of a partial
     *     match. @Throws a ProjectColumnMiss if nothing can be mapped to projection columns; a
     *     ProjectColumnPartialMiss if some columns can be mapped.
     */
    @Override
    public RexNode visitCall(final RexCall call) {
      // If the entire call is projected, give the reference to project column back.
      final RexNode newRef = getReference(call);
      if (newRef != null) {
        return newRef;
      }

      List<RexNode> newOperands = new ArrayList<>();
      final List<RexNode> weakenedPredicates = new ArrayList<>();
      boolean fullMatch = true;

      for (RexNode op : call.getOperands()) {
        // We need to remember whether this is the root call or not.
        final boolean previousNestedState = nestedCall;
        try {
          nestedCall = true;
          final RexNode mappedRex = op.accept(this);
          newOperands.add(mappedRex);

        } catch (ProjectMapper.ProjectColumnMiss e) {
          fullMatch = false;

        } catch (ProjectMapper.ProjectColumnPartialMiss e) {
          // Collect all the sub matches to see if we can weaken them later on.
          fullMatch = false;
          weakenedPredicates.addAll(nestedPartials);
          nestedPartials.clear();

        } finally {
          nestedCall = previousNestedState;
        }
      }
      if (fullMatch) {
        // Return the original call in terms of the projection columns.
        return call.clone(call.type, newOperands);
      }

      for (RexNode r : newOperands) {
        final int colNum;

        // We can only make weakened conclusions about projected columns.
        if (!(r instanceof RexInputRef)) {
          continue;
        }

        final RexInputRef inp = (RexInputRef) r;
        colNum = inp.getIndex();

        if (project.getRowType().getFieldList().get(colNum).getType().isNullable()
            && Strong.isNull(r, ImmutableBitSet.of(colNum))) {
          weakenedPredicates.add(rexBuilder.makeCall(SqlStdOperatorTable.IS_NOT_NULL, r));
        }
      }

      // Nothing can be concluded about the subset of columns
      if (weakenedPredicates.isEmpty()) {
        throw ProjectMapper.ProjectColumnMiss.INSTANCE;
      }

      // Pass the conclusions about our columns up the chain
      // so they may be combined with siblings.
      if (nestedCall) {
        nestedPartials = weakenedPredicates;
        throw ProjectMapper.ProjectColumnPartialMiss.INSTANCE;
      }

      // Return the weakened predicates to the user if this is the parent node.
      return RexUtil.composeDisjunction(rexBuilder, weakenedPredicates);
    }
  }

  /**
   * Generate all the permutations out of the given projected columns map. Given a mapping of 3 ->
   * [1, 2] 4 -> [3, 4] this method will generate {3 -> 1, 4 -> 0} on it's first call and will
   * generate {3 -> 0, 4 -> 1} and finally will generate {3 -> 1, 4 -> 1}. Basically all the
   * permutations.
   *
   * @param curPos - current permutation of all the position
   * @param positions - Mapping of all the projected columns
   * @return true if there are any permutation left, false otherwise
   */
  private boolean updateReferences(
      Map<ComparableRexNode, IndexValueHolder> curPos,
      Map<ComparableRexNode, List<Integer>> positions) {
    for (ComparableRexNode node : curPos.keySet()) {
      int index = curPos.get(node).getIndex();
      if (index < positions.get(node).size() - 1) {
        curPos.get(node).setIndex(index + 1);
        return true;
      } else {
        curPos.get(node).setIndex(0);
      }
    }
    return false;
  }

  /**
   * A placeholder bean class to store the indices (values) of a Hash Map. We need it in order to
   * update the value and iterate over the map simultaneously.
   */
  private static class IndexValueHolder {
    private int index;

    public IndexValueHolder(int index) {
      this.index = index;
    }

    public int getIndex() {
      return index;
    }

    public void setIndex(int index) {
      this.index = index;
    }
  }
}
