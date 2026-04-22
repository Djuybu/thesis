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
package com.dremio.exec.planner.sql.handlers;

import static com.dremio.exec.ExecConstants.ENABLE_RUNTIME_FILTER_ON_NON_PARTITIONED_PARQUET;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.dremio.common.JSONOptions;
import com.dremio.common.logical.PlanProperties;
import com.dremio.common.logical.PlanProperties.Generator.ResultMode;
import com.dremio.common.logical.PlanProperties.PlanPropertiesBuilder;
import com.dremio.common.logical.PlanProperties.PlanType;
import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.physical.PhysicalPlan;
import com.dremio.exec.physical.base.AbstractPhysicalVisitor;
import com.dremio.exec.physical.base.PhysicalOperator;
import com.dremio.exec.planner.PlannerPhase;
import com.dremio.exec.planner.PlannerType;
import com.dremio.exec.planner.common.MoreRelOptUtil;
import com.dremio.exec.planner.logical.Rel;
import com.dremio.exec.planner.physical.DistributionTrait;
import com.dremio.exec.planner.physical.PhysicalPlanCreator;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.physical.PrelUtil;
import com.dremio.exec.planner.physical.PrelUtil.CntStrOptimization;
import com.dremio.exec.planner.physical.explain.PrelSequencer;
import com.dremio.exec.planner.physical.visitor.BridgeReaderSchemaFinder;
import com.dremio.exec.planner.physical.visitor.CSEIdentifier;
import com.dremio.exec.planner.physical.visitor.ComplexToJsonPrelVisitor;
import com.dremio.exec.planner.physical.visitor.EmptyPrelPropagator;
import com.dremio.exec.planner.physical.visitor.ExcessiveExchangeIdentifier;
import com.dremio.exec.planner.physical.visitor.ExpandNestedFunctionVisitor;
import com.dremio.exec.planner.physical.visitor.FinalColumnReorderer;
import com.dremio.exec.planner.physical.visitor.InsertHashProjectVisitor;
import com.dremio.exec.planner.physical.visitor.InsertLocalExchangeVisitor;
import com.dremio.exec.planner.physical.visitor.JoinConditionValidatorVisitor;
import com.dremio.exec.planner.physical.visitor.JoinPrelRenameVisitor;
import com.dremio.exec.planner.physical.visitor.RelUniqifier;
import com.dremio.exec.planner.physical.visitor.RuntimeFilterDecorator;
import com.dremio.exec.planner.physical.visitor.SelectionVectorPrelVisitor;
import com.dremio.exec.planner.physical.visitor.SimpleLimitExchangeRemover;
import com.dremio.exec.planner.physical.visitor.SplitCountChecker;
import com.dremio.exec.planner.physical.visitor.SplitUpComplexExpressions;
import com.dremio.exec.planner.physical.visitor.StarColumnConverter;
import com.dremio.exec.planner.physical.visitor.SwapHashJoinVisitor;
import com.dremio.exec.planner.physical.visitor.WriterUpdater;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.proto.UserBitShared.PlannerPhaseRulesStats;
import com.dremio.exec.work.foreman.SqlUnsupportedException;
import com.dremio.options.OptionList;
import com.dremio.options.OptionManager;
import com.dremio.sabot.op.fromjson.ComplexFunctionPushDownVisitor;
import com.dremio.sabot.op.fromjson.ConvertFromJsonConverter;
import com.dremio.sabot.op.fromjson.ConvertFromUnnester;
import com.dremio.service.Pointer;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.tools.RelConversionException;
import org.apache.calcite.util.Pair;

/** Collection of Rel, Drel and Prel transformations used in various planning cycles. */
public final class PrelTransformer {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(PrelTransformer.class);

  private PrelTransformer() {}

  public static Pair<Prel, String> convertToPrel(SqlHandlerConfig config, RelNode drel)
      throws RelConversionException, SqlUnsupportedException {
    Preconditions.checkArgument(drel.getConvention() == Rel.LOGICAL);

    final RelTraitSet traits =
        drel.getTraitSet().plus(Prel.PHYSICAL).plus(DistributionTrait.SINGLETON);
    Prel phyRelNode;
    try {
      final Stopwatch watch = Stopwatch.createStarted();
      final RelNode prel1 =
          PlannerUtil.transform(
              config, PlannerType.VOLCANO, PlannerPhase.PHYSICAL, drel, traits, true);

      final RelNode prel2 =
          PlannerUtil.transform(
              config,
              PlannerType.HEP_AC,
              PlannerPhase.PHYSICAL_HEP,
              prel1,
              prel1.getTraitSet(),
              true);
      phyRelNode = (Prel) prel2.accept(new PrelFinalizer());
      if (config.getContext().getWorkloadType() == UserBitShared.WorkloadType.ACCELERATOR) {
        phyRelNode = (Prel) phyRelNode.accept(new IncrementalRefreshByPartitionFinalizeShuttle());
      }
      // log externally as we need to finalize before traversing the tree.
      PlanLogUtil.log(PlannerType.VOLCANO, PlannerPhase.PHYSICAL, phyRelNode, logger, watch);
    } catch (RelOptPlanner.CannotPlanException ex) {
      throw new RuntimeException(
          "This query cannot be planned due to an unforeseen internal exception.", ex);
    }
    return applyPhysicalPrelTransformations(config, phyRelNode);
  }

  public static Pair<Prel, String> applyPhysicalPrelTransformations(
      SqlHandlerConfig config, Prel prel) throws RelConversionException {
    QueryContext context = config.getContext();
    OptionManager queryOptions = context.getOptions();
    final PlannerSettings plannerSettings = context.getPlannerSettings();

    /* The order of the following transformations is important */
    final Stopwatch finalPrelTimer = Stopwatch.createStarted();
    final Pointer<Prel> phyRelNode = new Pointer<>(prel);
    final List<PlannerPhaseRulesStats> transformationStats = new ArrayList<>();

    try {
      /*
       * Convert AND with not equal expressions to NOT-OR with equal conditions
       * to make query use InExpression logical expression
       */
      phyRelNode.value =
          transform(
              phyRelNode.value,
              () -> (Prel) phyRelNode.value.accept(new AndToOrConverter()),
              transformationStats,
              "AndToOrConverter");

      /* Disable distribution trait pulling
       *
       * Some of the following operations might rewrite the tree but would not
       * keep distribution traits consistent anymore (like ExcessiveExchangeIdentifier
       * which would remove hash distribution exchanges if no parallelization will occur).
       */
      plannerSettings.pullDistributionTrait(false);

      /*
       * Check whether the query is within the required number-of-splits limit(s)
       */
      phyRelNode.value =
          transform(
              phyRelNode.value,
              () ->
                  SplitCountChecker.checkNumSplits(
                      phyRelNode.value,
                      plannerSettings.getQueryMaxSplitLimit(),
                      plannerSettings.getDatasetMaxSplitLimit()),
              transformationStats,
              "SplitCountChecker");

      /*
       * 0.) For select * from join query, we need insert project on top of scan and a top project just
       * under screen operator. The project on top of scan will rename from * to T1*, while the top project
       * will rename T1* to *, before it output the final result. Only the top project will allow
       * duplicate columns, since user could "explicitly" ask for duplicate columns ( select *, col, *).
       * The rest of projects will remove the duplicate column when we generate POP in json format.
       */
      phyRelNode.value =
          transform(
              phyRelNode.value,
              () -> StarColumnConverter.insertRenameProject(phyRelNode.value),
              transformationStats,
              "StarColumnConverter");

      /*
       * 1.1)
       * Join might cause naming conflicts from its left and right child.
       * In such case, we have to insert Project to rename the conflicting names.
       */
      phyRelNode.value =
          transform(
              phyRelNode.value,
              () -> JoinPrelRenameVisitor.insertRenameProject(phyRelNode.value),
              transformationStats,
              "JoinPrelRenameVisitor");

      /*
       * 1.2.) Swap left / right for INNER hash join, if left's row count is < (1 + margin) right's row count.
       * We want to have smaller dataset on the right side, since hash table builds on right side.
       */
      if (plannerSettings.isHashJoinSwapEnabled()) {
        phyRelNode.value =
            transform(
                phyRelNode.value,
                () ->
                    SwapHashJoinVisitor.swapHashJoin(
                        phyRelNode.value, plannerSettings.getHashJoinSwapMarginFactor()),
                transformationStats,
                "SwapHashJoinVisitor");
      }

      /*
       * 1.3.a) Unnest all the CONVERT_FROMJSON calls
       */
      phyRelNode.value =
          transform(
              phyRelNode.value,
              () -> ConvertFromUnnester.unnest(phyRelNode.value),
              transformationStats,
              "ConvertFromUnnester");

      /*
       * 1.3.b) Push down all the convert_fromjson expressions
       */
      phyRelNode.value =
          transform(
              phyRelNode.value,
              () ->
                  ComplexFunctionPushDownVisitor.convertFromJsonPushDown(
                      phyRelNode.value, queryOptions),
              transformationStats,
              "ComplexFunctionPushDownVisitor");

      /*
       * 1.4) Break up all expressions with complex outputs into their own project operations
       *
       * This is not needed for planning anymore, but just in case there are udfs that needs to be split up, keep it.
       */
      phyRelNode.value =
          transform(
              phyRelNode.value,
              () ->
                  phyRelNode.value.accept(
                      new SplitUpComplexExpressions.SplitUpComplexExpressionsVisitor(
                          context.getFunctionRegistry()),
                      null),
              transformationStats,
              "SplitUpComplexExpressions");

      /*
       * 2.)
       * Since our operators work via names rather than indices, we have to make to reorder any
       * output before we return data to the user as we may have accidentally shuffled things.
       * This adds a trivial project to reorder columns prior to output.
       */
      phyRelNode.value =
          transform(
              phyRelNode.value,
              () -> FinalColumnReorderer.addFinalColumnOrdering(phyRelNode.value),
              transformationStats,
              "FinalColumnReorderer");

      /*
       * 2.5) Remove all exchanges in the following case:
       *   Leaf limits are disabled.
       *   Plan has no joins, window operators or aggregates (unions are okay)
       *   Plan has at least one subpattern that is scan > project > limit or scan > limit,
       *   The limit is 10k or less
       *   All scans are soft affinity
       */
      phyRelNode.value =
          transform(
              phyRelNode.value,
              () ->
                  SimpleLimitExchangeRemover.apply(
                      config.getContext().getPlannerSettings(), phyRelNode.value),
              transformationStats,
              "SimpleLimitExchangeRemover");

      /*
       * 3.)
       * If two fragments are both estimated to be parallelization one, remove the exchange
       * separating them
       */
      /* DX-2353  should be fixed since it removes necessary exchanges and returns incorrect results. */
      long targetSliceSize = plannerSettings.getSliceTarget();
      phyRelNode.value =
          transform(
              phyRelNode.value,
              () ->
                  ExcessiveExchangeIdentifier.removeExcessiveEchanges(
                      phyRelNode.value, targetSliceSize),
              transformationStats,
              "ExcessiveExchangeIdentifier");

      /* 4.)
       * Add ProducerConsumer after each scan if the option is set
       * Use the configured queueSize
       */
      /* DRILL-1617 Disabling ProducerConsumer as it produces incorrect results
      if (context.getOptions().getOption(PlannerSettings.PRODUCER_CONSUMER.getOptionName()).bool_val) {
        long queueSize = context.getOptions().getOption(PlannerSettings.PRODUCER_CONSUMER_QUEUE_SIZE.getOptionName()).num_val;
        phyRelNode = ProducerConsumerPrelVisitor.addProducerConsumerToScans(phyRelNode, (int) queueSize);
      }
      */

      /* 5.)
       * if the client does not support complex types (Map, Repeated)
       * insert a project which would convert
       */
      if (!context.getSession().isSupportComplexTypes()) {
        logger.debug("Client does not support complex types, add ComplexToJson operator.");
        phyRelNode.value =
            transform(
                phyRelNode.value,
                () -> ComplexToJsonPrelVisitor.addComplexToJsonPrel(phyRelNode.value),
                transformationStats,
                "ComplexToJsonPrelVisitor");
      }

      /* 5.5)
       * Insert additional required operations to achieve correct writer behavior
       */
      phyRelNode.value =
          transform(
              phyRelNode.value,
              () -> WriterUpdater.update(phyRelNode.value),
              transformationStats,
              "WriterUpdater");

      /* 5.5)
       * Insert Project before/after HashToMergeExchangePrel and HashToRandomExchangePrel nodes
       */
      phyRelNode.value =
          transform(
              phyRelNode.value,
              () -> InsertHashProjectVisitor.insertHashProjects(phyRelNode.value, queryOptions),
              transformationStats,
              "InsertHashProjectVisitor");

      /* 6.)
       * Insert LocalExchange (mux and/or demux) nodes
       */
      phyRelNode.value =
          transform(
              phyRelNode.value,
              () ->
                  InsertLocalExchangeVisitor.insertLocalExchanges(
                      phyRelNode.value, queryOptions, context.getGroupResourceInformation()),
              transformationStats,
              "InsertLocalExchangeVisitor");

      /*
       * 7.)
       *
       * Convert any CONVERT_FROM(*, 'JSON') into a separate operator.
       */
      phyRelNode.value =
          transform(
              phyRelNode.value,
              () ->
                  phyRelNode.value.accept(
                      new ConvertFromJsonConverter(context, phyRelNode.value.getCluster()), null),
              transformationStats,
              "ConvertFromJsonConverter");

      /*
       * 7.5.) Remove subtrees that are topped by a limit0.
       */
      phyRelNode.value =
          transform(
              phyRelNode.value,
              () -> Limit0Converter.eliminateEmptyTrees(config, phyRelNode.value),
              transformationStats,
              "Limit0Converter");

      // We need to keep this around, since redundant sort operations are introduced earlier in
      // physical planning
      phyRelNode.value =
          transform(
              phyRelNode.value,
              () -> EmptyPrelPropagator.propagateEmptyPrel(config, phyRelNode.value),
              transformationStats,
              "EmptyPrelPropagator");

      /* 8.)
       * Next, we add any required selection vector removers given the supported encodings of each
       * operator. This will ultimately move to a new trait but we're managing here for now to avoid
       * introducing new issues in planning before the next release
       */
      phyRelNode.value =
          transform(
              phyRelNode.value,
              () -> SelectionVectorPrelVisitor.addSelectionRemoversWhereNecessary(phyRelNode.value),
              transformationStats,
              "SelectionVectorPrelVisitor");

      /* 9.)
       * Finally, Make sure that the no rels are repeats.
       * This could happen in the case of querying the same table twice as Optiq may canonicalize these.
       */
      phyRelNode.value =
          transform(
              phyRelNode.value,
              () -> RelUniqifier.uniqifyGraph(phyRelNode.value),
              transformationStats,
              "RelUniqifier");

      if (plannerSettings.applyCseBeforeRuntimeFilter()) {
        /*
         * Remove common sub expressions.
         */
        if (plannerSettings.isCSEEnabled()) {
          phyRelNode.value =
              transform(
                  phyRelNode.value,
                  () ->
                      CSEIdentifier.embellishAfterCommonSubExprElimination(
                          config.getContext(), phyRelNode.value),
                  transformationStats,
                  "CSEIdentifier-CseBeforeRuntimeFilter");
        }

        /*
         * add runtime filter information if applicable
         */
        if (plannerSettings.isRuntimeFilterEnabled()) {
          phyRelNode.value =
              transform(
                  phyRelNode.value,
                  () ->
                      RuntimeFilterDecorator.addRuntimeFilterToHashJoin(
                          phyRelNode.value,
                          plannerSettings
                              .getOptions()
                              .getOption(ENABLE_RUNTIME_FILTER_ON_NON_PARTITIONED_PARQUET)),
                  transformationStats,
                  "RuntimeFilterDecorator-CseBeforeRuntimeFilter");
        }
      } else {
        /*
         * 9.1)
         * add runtime filter information if applicable
         */
        if (plannerSettings.isRuntimeFilterEnabled()) {
          phyRelNode.value =
              transform(
                  phyRelNode.value,
                  () ->
                      RuntimeFilterDecorator.addRuntimeFilterToHashJoin(
                          phyRelNode.value,
                          plannerSettings
                              .getOptions()
                              .getOption(ENABLE_RUNTIME_FILTER_ON_NON_PARTITIONED_PARQUET)),
                  transformationStats,
                  "RuntimeFilterDecorator-CseAfterRuntimeFilter");
        }

        /* 9.2)
         * Remove common sub expressions.
         */
        if (plannerSettings.isCSEEnabled()) {
          phyRelNode.value =
              transform(
                  phyRelNode.value,
                  () ->
                      CSEIdentifier.embellishAfterCommonSubExprElimination(
                          config.getContext(), phyRelNode.value),
                  transformationStats,
                  "CSEIdentifier-CseAfterRuntimeFilter");
        }
      }

      phyRelNode.value =
          transform(
              phyRelNode.value,
              () -> BridgeReaderSchemaFinder.findAndSetSchemas(phyRelNode.value, config),
              transformationStats,
              "BridgeReaderSchemaFinder");

      /* 10.0)
       * Expand nested functions. Need to do that here at the end of planning
       * so that we don't merge the projects back again.
       */
      phyRelNode.value =
          transform(
              phyRelNode.value,
              () ->
                  ExpandNestedFunctionVisitor.pushdownNestedFunctions(
                      phyRelNode.value, queryOptions),
              transformationStats,
              "ExpandNestedFunctionVisitor");

      /*
       * validate the join conditions after all prel transformation
       * */
      phyRelNode.value =
          transform(
              phyRelNode.value,
              () -> JoinConditionValidatorVisitor.validate(phyRelNode.value, queryOptions),
              transformationStats,
              "JoinConditionValidatorVisitor");
    } catch (Throwable t) {
      try {
        // In case of an exception, lets collect stats, so we don't lose them
        collectStats(phyRelNode, config, finalPrelTimer, transformationStats);
      } catch (Throwable unexpected) {
        t.addSuppressed(unexpected);
      }
      throw t;
    }
    return collectStats(phyRelNode, config, finalPrelTimer, transformationStats);
  }

  private static Pair<Prel, String> collectStats(
      Pointer<Prel> phyRelNode,
      SqlHandlerConfig config,
      Stopwatch finalPrelTimer,
      List<PlannerPhaseRulesStats> transformationStats) {
    final Prel transformed = phyRelNode.value;
    final String textPlan = PrelSequencer.getPlanText(transformed, SqlExplainLevel.ALL_ATTRIBUTES);
    final String jsonPlan = PrelSequencer.getPlanJson(transformed, SqlExplainLevel.ALL_ATTRIBUTES);
    if (logger.isDebugEnabled()) {
      logger.debug(String.format("%s:\n%s", PlannerPhase.PLAN_FINAL_PHYSICAL, textPlan));
    }
    config
        .getObserver()
        .planFinalPhysical(textPlan, finalPrelTimer.elapsed(MILLISECONDS), transformationStats);
    config.getObserver().planJsonPlan(jsonPlan);
    config.getObserver().finalPrelPlanGenerated(transformed);
    config.getObserver().setNumJoinsInFinalPrel(MoreRelOptUtil.countJoins(transformed));
    return Pair.of(transformed, textPlan);
  }

  private static Prel transform(
      Prel input,
      ThrowingSupplier<Prel> prelSupplier,
      List<PlannerPhaseRulesStats> stats,
      String ruleDesc)
      throws RelConversionException {
    final Stopwatch timer = Stopwatch.createStarted();
    final int relNodeCount = MoreRelOptUtil.countRelNodes(input);
    final Prel output = prelSupplier.get();
    stats.add(
        PlannerPhaseRulesStats.newBuilder()
            .setRule(ruleDesc)
            .setTotalTimeMs(timer.elapsed(MILLISECONDS))
            .setMatchedCount(1)
            .setTransformedCount(1)
            .setRelnodesCount(relNodeCount)
            .build());
    return output;
  }

  public static PhysicalOperator convertToPop(SqlHandlerConfig config, Prel prel)
      throws IOException {

    /* This visitor will look for count star in the plan and will replace the count star with
    record count from table metadata.
    p.s.: this logic is here only bcz count star optimization is not executable without this step.
    so this is kind of making the plan executable similar to prelToPop.
    */
    prel = prel.accept(new CntStrOptimization(config.getConverter().getCluster()), null);

    PhysicalPlanCreator creator = new PhysicalPlanCreator(config, PrelSequencer.getIdMap(prel));
    PhysicalOperator op = prel.getPhysicalOperator(creator);

    // Catch unresolvable "is_member()" function in plan and set the flag in query context
    // indicating that groups info needs to be available at executor.
    if (PrelUtil.containsCall(prel, "IS_MEMBER")) {
      config.getContext().setQueryRequiresGroupsInfo(true);
    }
    return op;
  }

  public static PhysicalPlan convertToPlan(
      SqlHandlerConfig config, PhysicalOperator op, Runnable committer, Runnable cleaner) {
    OptionList options = new OptionList();
    options.merge(config.getContext().getQueryOptionManager().getNonDefaultOptions());
    options.merge(config.getContext().getSessionOptionManager().getNonDefaultOptions());

    PlanPropertiesBuilder propsBuilder = PlanProperties.builder();
    propsBuilder.type(PlanType.PHYSICAL);
    propsBuilder.version(1);
    propsBuilder.options(new JSONOptions(options));
    propsBuilder.resultMode(ResultMode.EXEC);
    propsBuilder.generator("default", "handler");
    List<PhysicalOperator> ops = Lists.newArrayList();
    PopCollector c = new PopCollector();
    op.accept(c, ops);
    return new PhysicalPlan(propsBuilder.build(), ops, committer, cleaner);
  }

  public static PhysicalPlan convertToPlan(SqlHandlerConfig config, PhysicalOperator op) {
    return convertToPlan(config, op, null, null);
  }

  private static class PopCollector
      extends AbstractPhysicalVisitor<Void, Collection<PhysicalOperator>, RuntimeException> {

    @Override
    public Void visitOp(PhysicalOperator op, Collection<PhysicalOperator> collection)
        throws RuntimeException {
      collection.add(op);
      for (PhysicalOperator o : op) {
        o.accept(this, collection);
      }
      return null;
    }
  }

  @FunctionalInterface
  public interface ThrowingSupplier<T> {
    T get() throws RelConversionException;
  }
}
