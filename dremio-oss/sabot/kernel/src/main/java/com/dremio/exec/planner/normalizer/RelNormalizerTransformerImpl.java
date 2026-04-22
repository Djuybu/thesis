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
package com.dremio.exec.planner.normalizer;

import com.dremio.exec.ops.PlannerCatalog;
import com.dremio.exec.ops.ViewExpansionContext;
import com.dremio.exec.planner.DremioVolcanoPlanner;
import com.dremio.exec.planner.HepPlannerRunner;
import com.dremio.exec.planner.PlannerPhase;
import com.dremio.exec.planner.StatelessRelShuttleImpl;
import com.dremio.exec.planner.acceleration.substitution.SubstitutionProvider;
import com.dremio.exec.planner.common.MoreRelOptUtil;
import com.dremio.exec.planner.decorrelation.DecorrelationAssertions;
import com.dremio.exec.planner.events.FunctionDetectedEvent;
import com.dremio.exec.planner.events.PlannerEventBus;
import com.dremio.exec.planner.logical.DremioRelFactories;
import com.dremio.exec.planner.logical.PreProcessRel;
import com.dremio.exec.planner.logical.UncollectToFlattenConverter;
import com.dremio.exec.planner.logical.ValuesRewriteShuttle;
import com.dremio.exec.planner.observer.AttemptObserver;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.planner.sql.RexCorrelVariableSchemaFixer;
import com.dremio.exec.planner.sql.RexShuttleRelShuttle;
import com.dremio.exec.planner.sql.handlers.ConvertedRelNode;
import com.dremio.exec.planner.sql.handlers.PlanLogUtil;
import com.dremio.exec.planner.sql.handlers.RelTransformer;
import com.dremio.exec.work.foreman.SqlUnsupportedException;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Values;
import org.apache.calcite.rel.logical.LogicalValues;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RelNormalizerTransformerImpl implements RelNormalizerTransformer {

  private static final Logger LOGGER = LoggerFactory.getLogger(RelNormalizerTransformerImpl.class);
  private final HepPlannerRunner hepPlannerRunner;
  private final NormalizerRuleSets normalizerRuleSets;
  private final SubstitutionProvider substitutionProvider;
  private final ViewExpansionContext viewExpansionContext;
  private final PlannerSettings plannerSettings;
  private final PlannerCatalog plannerCatalog;
  private final PlannerEventBus plannerEventBus;

  public RelNormalizerTransformerImpl(
      HepPlannerRunner hepPlannerRunner,
      NormalizerRuleSets normalizerRuleSets,
      SubstitutionProvider substitutionProvider,
      ViewExpansionContext viewExpansionContext,
      PlannerSettings plannerSettings,
      PlannerCatalog plannerCatalog,
      PlannerEventBus plannerEventBus) {
    this.hepPlannerRunner = hepPlannerRunner;
    this.normalizerRuleSets = normalizerRuleSets;
    this.substitutionProvider = substitutionProvider;
    this.viewExpansionContext = viewExpansionContext;
    this.plannerSettings = plannerSettings;
    this.plannerCatalog = plannerCatalog;
    this.plannerEventBus = plannerEventBus;
  }

  @Override
  public RelNode transform(ConvertedRelNode convertedRelNode, AttemptObserver attemptObserver)
      throws SqlUnsupportedException {
    try {
      final RelNode normalizedRel =
          transformPreSerialization(
              convertedRelNode.getConvertedNode(),
              attemptObserver,
              convertedRelNode.getValidatedRowType());

      attemptObserver.planSerializable(normalizedRel);
      if (!plannerSettings.options.getOption(PlannerSettings.PUSH_FILTER_PAST_EXPANSIONS)) {
        updateOriginalRoot(normalizedRel);
      }

      final RelNode normalizedRel2 = transformPostSerialization(normalizedRel);

      PlanLogUtil.log("INITIAL", normalizedRel2, LOGGER, null);
      attemptObserver.setNumJoinsInUserQuery(MoreRelOptUtil.countJoins(normalizedRel2));

      return normalizedRel2;
    } finally {
      attemptObserver.tablesCollected(plannerCatalog.getAllRequestedTables());
    }
  }

  @Override
  public RelNode transformForReflection(
      ConvertedRelNode convertedRelNode,
      RelTransformer relTransformer,
      AttemptObserver attemptObserver)
      throws SqlUnsupportedException {
    try {
      final RelNode normalizedRel =
          transformPreSerialization(
              convertedRelNode.getConvertedNode(),
              attemptObserver,
              convertedRelNode.getValidatedRowType());

      final RelNode transformed = relTransformer.transform(normalizedRel);

      attemptObserver.planSerializable(transformed);
      if (!plannerSettings.options.getOption(PlannerSettings.PUSH_FILTER_PAST_EXPANSIONS)) {
        updateOriginalRoot(transformed);
      }

      final RelNode normalizedRel2 = transformPostSerialization(transformed);

      PlanLogUtil.log("INITIAL", normalizedRel2, LOGGER, null);
      attemptObserver.setNumJoinsInUserQuery(MoreRelOptUtil.countJoins(normalizedRel2));

      return normalizedRel2;
    } finally {
      attemptObserver.tablesCollected(plannerCatalog.getAllRequestedTables());
    }
  }

  @Override
  public RelNode transformPreSerialization(
      RelNode relNode, AttemptObserver attemptObserver, RelDataType validatedRowType) {
    RelNode expanded = expand(relNode);
    Optional<RelNode> hashMatched = matchHash(expanded, attemptObserver, validatedRowType);
    RelNode drrsMatched = matchDRRs(hashMatched.orElse(expanded), attemptObserver);
    RelNode uncollectsReplaced =
        UncollectToFlattenConverter.convert(
            plannerSettings.options.getOption(PlannerSettings.VALUES_CAST_ENABLED)
                ? ValuesRewriteShuttle.rewrite(drrsMatched)
                : drrsMatched);
    RelNode aggregateRewritten =
        hepPlannerRunner.transform(uncollectsReplaced, normalizerRuleSets.createAggregateRewrite());
    // We need to do all this detection analysis before the reduce rules fire, since the function
    // might get converted to a literal.
    RelNode expandedButNotReduced = aggregateRewritten;
    dispatchFunctions(expandedButNotReduced, plannerEventBus);
    RelNode valuesNormalized = normalizeValues(expandedButNotReduced);
    RelNode reduced = reduce(valuesNormalized);

    return reduced;
  }

  @Override
  public RelNode transformPostSerialization(RelNode relNode) throws SqlUnsupportedException {
    RelNode expandedOperators =
        hepPlannerRunner.transform(relNode, normalizerRuleSets.createOperatorExpansion());
    RelNode preprocessedRel;
    if (plannerSettings.options.getOption(PlannerSettings.VALUES_CAST_ENABLED)) {
      RelNode values = ValuesRewriteShuttle.rewrite(expandedOperators);
      preprocessedRel = preprocess(values);
    } else {
      preprocessedRel = preprocess(expandedOperators);
    }
    return preprocessedRel;
  }

  private RelNode expand(RelNode relNode) {
    DecorrelationAssertions.assertPostSqlToRelPreExpansion(relNode);
    // This has to be the very first step, since it will expand RexSubqueries to Correlates and all
    // the other
    // transformations can't operate on the RelNode inside of RexSubquery.
    RelNode expanded =
        hepPlannerRunner.transform(relNode, normalizerRuleSets.createEntityExpansion());
    if (plannerSettings.options.getOption(PlannerSettings.FIX_CORRELATE_VARIABLE_SCHEMA)) {
      expanded = RexCorrelVariableSchemaFixer.fixSchema(expanded);
    }

    DecorrelationAssertions.assertPostExpansionPreDecorrelation(expanded);

    return expanded;
  }

  private Optional<RelNode> matchHash(
      RelNode relNode, AttemptObserver attemptObserver, RelDataType validatedRowType) {
    final Stopwatch hashStopwatch = Stopwatch.createStarted();
    Optional<RelNode> hashMatched =
        substitutionProvider.generateHashReplacement(relNode, validatedRowType);
    attemptObserver.planRelTransform(
        PlannerPhase.DEFAULT_HASH_MATCHING,
        null,
        relNode,
        hashMatched.orElse(relNode),
        hashStopwatch.elapsed(TimeUnit.MILLISECONDS),
        ImmutableList.of());
    return hashMatched;
  }

  private RelNode matchDRRs(RelNode relNode, AttemptObserver attemptObserver) {
    final Stopwatch drrStopwatch = Stopwatch.createStarted();
    RelNode drrsMatched =
        DRRMatcher.match(relNode, substitutionProvider, viewExpansionContext, attemptObserver);
    attemptObserver.planRelTransform(
        PlannerPhase.DEFAULT_RAW_MATCHING,
        null,
        relNode,
        drrsMatched,
        drrStopwatch.elapsed(TimeUnit.MILLISECONDS),
        ImmutableList.of());

    return drrsMatched;
  }

  private RelNode reduce(RelNode relNode) {
    RelNode reduced =
        hepPlannerRunner.transform(relNode, normalizerRuleSets.createReduceExpression());
    return reduced;
  }

  private RelNode normalizeValues(RelNode relNode) {
    // This logic can't be a rule, since the planner uses digest for equality and this rewrite ends
    // up with the same digest.
    RelBuilder relBuilder =
        DremioRelFactories.CALCITE_LOGICAL_BUILDER.create(relNode.getCluster(), null);
    try {
      return relNode.accept(
          new StatelessRelShuttleImpl() {
            @Override
            public RelNode visit(LogicalValues values) {
              if (!needsNormalization(values)) {
                return values;
              }

              RelDataType rowType = values.getRowType();
              List<ImmutableList<RexLiteral>> newTuples = new ArrayList<>();
              for (List<RexLiteral> tuple : values.getTuples()) {
                assert tuple.size() == rowType.getFieldCount();
                List<RexLiteral> newTuple = new ArrayList<>();
                for (Pair<RexLiteral, RelDataTypeField> pair :
                    Pair.zip(tuple, rowType.getFieldList())) {
                  RexLiteral literal = pair.left;
                  RelDataType fieldType = pair.right.getType();

                  RexLiteral newLiteral;
                  if (literal.getType() != fieldType) {
                    RexNode castedLiteral =
                        relBuilder.getRexBuilder().makeCast(fieldType, literal, true);
                    if (castedLiteral instanceof RexLiteral) {
                      newLiteral = (RexLiteral) castedLiteral;
                    } else {
                      newLiteral = literal;
                    }
                  } else {
                    newLiteral = literal;
                  }

                  newTuple.add(newLiteral);
                }

                newTuples.add(ImmutableList.copyOf(newTuple));
              }

              RelNode newValues =
                  relBuilder.values(ImmutableList.copyOf(newTuples), values.getRowType()).build();
              return newValues;
            }
          });
    } catch (Exception ex) {
      // We don't want this logic to fail the whole query if it fails.
      LOGGER.error("Failed to normalize a values rel for unknown reason.", ex);
      return relNode;
    }
  }

  private static boolean needsNormalization(Values values) {
    RelDataType rowType = values.getRowType();
    for (List<RexLiteral> tuple : values.getTuples()) {
      assert tuple.size() == rowType.getFieldCount();
      for (Pair<RexLiteral, RelDataTypeField> pair : Pair.zip(tuple, rowType.getFieldList())) {
        RexLiteral literal = pair.left;
        RelDataType fieldType = pair.right.getType();

        if (literal.getType() != fieldType) {
          return true;
        }
      }
    }

    return false;
  }

  private RelNode preprocess(RelNode relNode) throws SqlUnsupportedException {
    PreProcessRel visitor = PreProcessRel.createVisitor(relNode.getCluster().getRexBuilder());
    try {
      return relNode.accept(visitor);
    } catch (UnsupportedOperationException ex) {
      visitor.convertException();
      throw ex;
    }
  }

  public static void updateOriginalRoot(RelNode relNode) {
    final DremioVolcanoPlanner volcanoPlanner =
        (DremioVolcanoPlanner) relNode.getCluster().getPlanner();
    volcanoPlanner.setOriginalRoot(relNode);
  }

  private static void dispatchFunctions(RelNode relNode, PlannerEventBus plannerEventBus) {
    RexShuttleRelShuttle relShuttle =
        new RexShuttleRelShuttle(
            new RexShuttle() {
              @Override
              public RexNode visitCall(RexCall call) {
                SqlOperator sqlOperator = call.getOperator();
                FunctionDetectedEvent functionDetectedEvent =
                    new FunctionDetectedEvent(sqlOperator);
                plannerEventBus.dispatch(functionDetectedEvent);
                return super.visitCall(call);
              }
            });
    relNode.accept(relShuttle);
  }
}
