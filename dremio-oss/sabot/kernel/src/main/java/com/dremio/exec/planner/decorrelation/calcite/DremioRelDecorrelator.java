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
package com.dremio.exec.planner.decorrelation.calcite;

import static java.util.Objects.requireNonNull;

import com.dremio.exec.planner.logical.DremioRelFactories;
import com.dremio.exec.planner.logical.WindowRel;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.apache.calcite.plan.Context;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.Correlate;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.core.Union;
import org.apache.calcite.rel.core.Values;
import org.apache.calcite.rel.core.Window;
import org.apache.calcite.rel.core.Window.Group;
import org.apache.calcite.rel.core.Window.RexWinAggCall;
import org.apache.calcite.rel.logical.LogicalWindow;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.tools.RelBuilderFactory;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.ImmutableBitSet.Builder;
import org.apache.calcite.util.mapping.Mappings;

/** Dremio version of RelDecorrelator extended from Calcite */
public final class DremioRelDecorrelator extends CalciteRelDecorrelator {
  private final boolean isRelPlanning;
  private final RelBuilder relBuilder;

  private DremioRelDecorrelator(
      CorelMap cm,
      Context context,
      RelBuilder relBuilder,
      boolean forceValueGenerator,
      boolean isRelPlanning) {
    super(cm, context, relBuilder, forceValueGenerator);
    this.isRelPlanning = isRelPlanning;
    this.relBuilder = relBuilder;
  }

  @Override
  protected RelBuilderFactory relBuilderFactory() {
    if (isRelPlanning) {
      return DremioRelFactories.LOGICAL_BUILDER;
    }
    return DremioRelFactories.CALCITE_LOGICAL_BUILDER;
  }

  @Override
  public Frame decorrelateRel(Values rel, boolean isCorVarDefined) {
    // There are no inputs, so rel does not need to be changed.
    return decorrelateRel((RelNode) rel, isCorVarDefined);
  }

  /**
   * Modified from upstream to add a join condition if the same correlated variable occurs on both
   * sides.
   *
   * @param rel
   * @param isCorVarDefined
   * @return
   */
  @Override
  public Frame decorrelateRel(Join rel, boolean isCorVarDefined) {
    // For SEMI/ANTI join decorrelate it's input directly,
    // because the correlate variables can only be propagated from
    // the left side, which is not supported yet.
    if (!rel.getJoinType().projectsRight()) {
      decorrelateRel((RelNode) rel, isCorVarDefined);
    }

    // Rewrite logic
    // 1. rewrite join condition.
    // 2. map output positions and produce corVars if any.
    final RelNode oldLeft = rel.getLeft();
    final RelNode oldRight = rel.getRight();

    final Frame leftFrame = getInvoke(oldLeft, isCorVarDefined, rel);
    final Frame rightFrame = getInvoke(oldRight, isCorVarDefined, rel);

    if (leftFrame == null || rightFrame == null) {
      // If any input has not been rewritten, do not rewrite this rel.
      return null;
    }

    Sets.SetView<CorDef> intersection =
        Sets.intersection(leftFrame.corDefOutputs.keySet(), rightFrame.corDefOutputs.keySet());

    RexNode condition = decorrelateExpr(currentRel, map, cm, rel.getCondition());

    final RelNode newJoin =
        relBuilder
            .push(leftFrame.r)
            .push(rightFrame.r)
            .join(
                rel.getJoinType(),
                relBuilder.and(
                    ImmutableList.<RexNode>builder()
                        .add(condition)
                        .addAll(
                            intersection.stream()
                                    .map(
                                        cor ->
                                            relBuilder.equals(
                                                relBuilder.field(
                                                    2, 0, leftFrame.corDefOutputs.get(cor)),
                                                relBuilder.field(
                                                    2, 1, rightFrame.corDefOutputs.get(cor))))
                                ::iterator)
                        .build()),
                ImmutableSet.of())
            .build();

    // Create the mapping between the output of the old correlation rel
    // and the new join rel
    Map<Integer, Integer> mapOldToNewOutputs = new HashMap<>();

    int oldLeftFieldCount = oldLeft.getRowType().getFieldCount();
    int newLeftFieldCount = leftFrame.r.getRowType().getFieldCount();

    int oldRightFieldCount = oldRight.getRowType().getFieldCount();
    //noinspection AssertWithSideEffects
    assert rel.getRowType().getFieldCount() == oldLeftFieldCount + oldRightFieldCount;

    // Left input positions are not changed.
    mapOldToNewOutputs.putAll(leftFrame.oldToNewOutputs);

    // Right input positions are shifted by newLeftFieldCount.
    for (int i = 0; i < oldRightFieldCount; i++) {
      mapOldToNewOutputs.put(
          i + oldLeftFieldCount, rightFrame.oldToNewOutputs.get(i) + newLeftFieldCount);
    }

    final NavigableMap<CorDef, Integer> corDefOutputs = new TreeMap<>(leftFrame.corDefOutputs);

    // Right input positions are shifted by newLeftFieldCount.
    for (Map.Entry<CorDef, Integer> entry : rightFrame.corDefOutputs.entrySet()) {
      // For left joins, we want to pull up the left side fields if possible.
      // If we don't do this, it could possibly yield incorrect results.
      if (rel.getJoinType() == JoinRelType.LEFT && intersection.contains(entry.getKey())) {
        continue;
      }
      corDefOutputs.put(entry.getKey(), entry.getValue() + newLeftFieldCount);
    }
    return register(rel, newJoin, mapOldToNewOutputs, corDefOutputs);
  }

  /**
   * Same as upstream except additional check to see if the limiting sort is under a correlate rel
   * and over a correlated variable
   */
  @Override
  public @Nullable Frame decorrelateRel(Sort rel, boolean isCorVarDefined) {
    //
    // Rewrite logic:
    //
    // 1. change the collations field to reference the new input.
    //

    // Sort itself should not reference corVars.
    assert !cm.mapRefRelToCorRef.containsKey(rel);

    // Sort only references field positions in collations field.
    // The collations field in the newRel now need to refer to the
    // new output positions in its input.
    // Its output does not change the input ordering, so there's no
    // need to call propagateExpr.

    final RelNode oldInput = rel.getInput();
    final Frame frame = getInvoke(oldInput, isCorVarDefined, rel);
    if (frame == null) {
      // If input has not been rewritten, do not rewrite this rel.
      return null;
    }

    // MODIFIED BEGINS
    if ((rel.fetch != null || rel.offset != null) && !frame.corDefOutputs.isEmpty()) {
      // We can not decorrelate a limiting sort over a correlated variable and under a correlate
      // relnode
      // since the filter needs to be pulled above it when rewriting the correlate to a join.
      // But if it's over the correlate relnode or under the variable, then it's okay.
      return null;
    }
    // MODIFICATIONS ENDS

    final RelNode newInput = frame.r;

    Mappings.TargetMapping mapping =
        Mappings.target(
            frame.oldToNewOutputs,
            oldInput.getRowType().getFieldCount(),
            newInput.getRowType().getFieldCount());

    RelCollation oldCollation = rel.getCollation();
    RelCollation newCollation = RexUtil.apply(mapping, oldCollation);

    final int offset = rel.offset == null ? -1 : RexLiteral.intValue(rel.offset);
    final int fetch = rel.fetch == null ? -1 : RexLiteral.intValue(rel.fetch);

    final RelNode newSort =
        relBuilder.push(newInput).sortLimit(offset, fetch, relBuilder.fields(newCollation)).build();

    // Sort does not change input ordering
    return register(rel, newSort, frame.oldToNewOutputs, frame.corDefOutputs);
  }

  public @Nullable Frame decorrelateRel(Window window, boolean isCorVarDefined) {
    RelNode oldInput = window.getInput();
    Frame frame = getInvoke(oldInput, isCorVarDefined, window);
    if (frame == null) {
      // Failed to decorrelate, so just let that bubble up
      return null;
    }

    RelNode newInput = frame.r;

    // Window itself should not reference corVars.
    assert !cm.mapRefRelToCorRef.containsKey(window);

    if (frame.corDefOutputs.isEmpty()) {
      // No correlated variable to worry about:
      // We can take a shortcut and just return the window with the new input
      // This also preserves the previous behavior when we had no handling for window rels.
      return register(
          window,
          window.copy(window.getTraitSet(), ImmutableList.of(newInput)),
          identityMap(window.getRowType().getFieldCount()),
          ImmutableSortedMap.of());
    }

    List<Group> newGroups = new ArrayList<>();
    for (Group group : window.groups) {
      // Add the correlated variables to the partitions
      Builder builder = ImmutableBitSet.builder();
      for (Map.Entry<CorDef, Integer> entry : frame.corDefOutputs.entrySet()) {
        builder.set(entry.getValue());
      }

      // Remap any indexes
      for (Integer key : group.keys.asList()) {
        Integer newKey = requireNonNull(frame.oldToNewOutputs.get(key));
        builder.set(newKey);
      }

      ImmutableBitSet newKeys = builder.build();

      // Rewrite the collation
      List<RelFieldCollation> newRelFieldCollations = new ArrayList<>();
      for (RelFieldCollation relFieldCollation : group.collation().getFieldCollations()) {
        RelFieldCollation newRelFieldCollation =
            relFieldCollation.withFieldIndex(relFieldCollation.getFieldIndex());
        newRelFieldCollations.add(newRelFieldCollation);
      }

      RelCollation newRelCollation = RelCollations.of(newRelFieldCollations);

      // Rewrite the agg calls
      List<RexWinAggCall> newRexWinAggCalls = new ArrayList<>();
      for (RexWinAggCall rexWinAggCall : group.aggCalls) {
        List<RexNode> newOperands =
            rexWinAggCall.getOperands().stream()
                .map(
                    oldOperand ->
                        decorrelateExpr(
                            requireNonNull(currentRel, "currentRel"), map, cm, oldOperand))
                .collect(Collectors.toList());

        RexWinAggCall newRexWinAggCall =
            new RexWinAggCall(
                (SqlAggFunction) rexWinAggCall.getOperator(),
                rexWinAggCall.getType(),
                newOperands,
                rexWinAggCall.ordinal,
                rexWinAggCall.distinct,
                rexWinAggCall.ignoreNulls);
        newRexWinAggCalls.add(newRexWinAggCall);
      }

      Group newGroup =
          new Group(
              newKeys,
              group.isRows,
              group.lowerBound,
              group.upperBound,
              newRelCollation,
              newRexWinAggCalls);
      newGroups.add(newGroup);
    }

    // We need to create a new row type for all the correlated variables we introduced ...
    List<RelDataType> types = new ArrayList<>();
    List<String> fieldNames = new ArrayList<>();
    for (int i = 0, j = 0;
        i < (window.getRowType().getFieldCount() + frame.corDefOutputs.size());
        i++) {
      RelDataTypeField relDataTypeField;
      if (frame.corDefOutputs.values().contains(i)) {
        relDataTypeField = newInput.getRowType().getFieldList().get(i);
      } else {
        relDataTypeField = window.getRowType().getFieldList().get(j);
        j++;
      }

      types.add(relDataTypeField.getType());
      fieldNames.add(relDataTypeField.getName());
    }

    RelDataType newRelDataType = relBuilder.getTypeFactory().createStructType(types, fieldNames);

    // This is a hack since relbuilder doesn't support window rels.
    RelNode newWindow;
    if (window instanceof LogicalWindow) {
      newWindow =
          LogicalWindow.create(
              window.getTraitSet(), newInput, window.constants, newRelDataType, newGroups);
    } else if (window instanceof WindowRel) {
      newWindow =
          WindowRel.create(
              window.getCluster(),
              window.getTraitSet(),
              newInput,
              window.constants,
              newRelDataType,
              newGroups);
    } else {
      throw new UnsupportedOperationException(
          "Unexpected window type: " + window.getClass().getSimpleName());
    }

    Map<Integer, Integer> oldToNewOutputs = new HashMap<>();
    for (int i = 0, j = 0; i < window.getRowType().getFieldCount(); i++, j++) {
      if (frame.corDefOutputs.values().contains(i)) {
        // We need to skip over the correlated variables introduced by the input
        j++;
      }

      oldToNewOutputs.put(i, j);
    }

    // We don't introduce any correlated variables or permute things
    return register(window, newWindow, oldToNewOutputs, frame.corDefOutputs);
  }

  /** Overriding since the super classes dispatcher bugs out for non base classes. */
  @Override
  public Frame getInvoke(RelNode r, boolean isCorVarDefined, RelNode parent) {
    final Frame frame = doInvoke(r, isCorVarDefined);
    currentRel = parent;
    if (frame != null) {
      map.put(r, frame);
    }
    currentRel = parent;
    return frame;
  }

  private Frame doInvoke(RelNode r, boolean isCorVarDefined) {
    if (r instanceof Aggregate) {
      return decorrelateRel((Aggregate) r, isCorVarDefined);
    } else if (r instanceof Correlate) {
      return decorrelateRel((Correlate) r, isCorVarDefined);
    } else if (r instanceof Filter) {
      return decorrelateRel((Filter) r, isCorVarDefined);
    } else if (r instanceof Join) {
      return decorrelateRel((Join) r, isCorVarDefined);
    } else if (r instanceof Project) {
      return decorrelateRel((Project) r, isCorVarDefined);
    } else if (r instanceof TableScan) {
      return decorrelateRel(r, isCorVarDefined);
    } else if (r instanceof Sort) {
      return decorrelateRel((Sort) r, isCorVarDefined);
    } else if (r instanceof Union) {
      return decorrelateRel((Union) r, isCorVarDefined);
    } else if (r instanceof Values) {
      return decorrelateRel((Values) r, isCorVarDefined);
    } else if (r instanceof Window) {
      return decorrelateRel((Window) r, isCorVarDefined);
    } else {
      // Just go through the generic rewrite logic.
      return decorrelateRel((RelNode) r, isCorVarDefined);
    }
  }

  public static DremioRelDecorrelator create(
      RelNode relNode, RelBuilder relBuilder, boolean forceValueGeneration, boolean isRelPlanning) {
    final CorelMap corelMap = new CorelMapBuilder().build(relNode);
    final RelOptCluster cluster = relNode.getCluster();
    final DremioRelDecorrelator decorrelator =
        new DremioRelDecorrelator(
            corelMap,
            cluster.getPlanner().getContext(),
            relBuilder,
            forceValueGeneration,
            isRelPlanning);

    return decorrelator;
  }
}
