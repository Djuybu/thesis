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
package com.dremio.exec.planner.plancache;

import static com.dremio.exec.planner.events.PlannerEventHandler.handle;
import static com.dremio.exec.planner.physical.PlannerSettings.QUERY_PLAN_CACHE_ENABLED;
import static com.dremio.exec.planner.plancache.PlanCacheMetrics.PlanCachePhases.PLAN_CACHE_PUT;
import static com.dremio.exec.planner.plancache.PlanCacheMetrics.QueryOutcome.NOT_PUT_BLACKLISTED;
import static com.dremio.exec.planner.plancache.PlanCacheMetrics.QueryOutcome.NOT_PUT_DYNAMIC_FUNCTION;
import static com.dremio.exec.planner.plancache.PlanCacheMetrics.QueryOutcome.NOT_PUT_EXTERNAL_QUERY;
import static com.dremio.exec.planner.plancache.PlanCacheMetrics.QueryOutcome.NOT_PUT_MAT_CACHE_NOT_INIT;
import static com.dremio.exec.planner.plancache.PlanCacheMetrics.QueryOutcome.NOT_PUT_VERSIONED_TABLE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.lang3.StringUtils.containsIgnoreCase;

import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.catalog.CatalogUtil;
import com.dremio.exec.catalog.DremioTable;
import com.dremio.exec.ops.PlannerCatalog;
import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.planner.PlannerPhase;
import com.dremio.exec.planner.acceleration.DremioMaterialization;
import com.dremio.exec.planner.acceleration.MaterializationList;
import com.dremio.exec.planner.common.CopyIntoTableRelBase;
import com.dremio.exec.planner.common.TableOptimizeRelBase;
import com.dremio.exec.planner.common.VacuumCatalogRelBase;
import com.dremio.exec.planner.common.VacuumTableRelBase;
import com.dremio.exec.planner.observer.AttemptObserver;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.physical.explain.PrelSequencer;
import com.dremio.exec.planner.physical.visitor.WriterPathUpdater;
import com.dremio.exec.planner.plancache.PlanCacheMetrics.PlanCacheEvent;
import com.dremio.exec.planner.sql.handlers.SqlHandlerConfig;
import com.dremio.exec.proto.UserBitShared.WorkloadType;
import com.dremio.exec.store.mfunctions.MFunctionQueryRelBase;
import com.dremio.exec.tablefunctions.copyerrors.CopyErrorsRelBase;
import com.dremio.options.OptionResolver;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.sql.SqlExplainLevel;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.dialect.CalciteSqlDialect;

public class PlanCacheUtils {

  public static void putIntoPlanCache(
      SqlHandlerConfig config,
      SqlNode sqlNode,
      PlanCache planCache,
      PlanCacheKey cachedKey,
      RelNode drel,
      Prel prel,
      List<SqlOperator> uncacheableFunctions) {
    if (!config.getConverter().getOptionResolver().getOption(QUERY_PLAN_CACHE_ENABLED)) {
      return;
    }
    Stopwatch stopwatch = Stopwatch.createStarted();
    var events = new ArrayList<PlanCacheEvent>();
    try (var ignore =
        config
            .getConverter()
            .getPlannerEventBus()
            .register(handle(PlanCacheEvent.class, events::add))) {
      // after we generate a physical plan, save it in the plan cache if plan cache is present
      if (PlanCacheUtils.supportPlanCache(config, sqlNode, uncacheableFunctions, drel)) {
        planCache.putCachedPlan(config, cachedKey, prel);
      } else {
        events.add(PlanCacheMetrics.createUncacheablePlan());
        PlanCacheMetrics.reportPhase(
            config.getObserver(), PLAN_CACHE_PUT, cachedKey, events, stopwatch);
      }
    }
  }

  public static boolean supportPlanCache(
      SqlHandlerConfig config,
      SqlNode sqlNode,
      List<SqlOperator> uncacheableFunctions,
      RelNode relNode) {
    var plannerEventBus = config.getPlannerEventBus();
    OptionResolver optionResolver = config.getContext().getOptions();
    Optional<MaterializationList> materialization = config.getMaterializations();
    if (containsIgnoreCase(sqlNode.toString(), "external_query")) {
      plannerEventBus.dispatch(
          new PlanCacheEvent(NOT_PUT_EXTERNAL_QUERY, "Query contains an external_query."));
      return false;
    } else if (!uncacheableFunctions.isEmpty()) {
      plannerEventBus.dispatch(
          new PlanCacheEvent(
              NOT_PUT_DYNAMIC_FUNCTION,
              String.format(
                  "Query contains dynamic or non-deterministic function(s): %s.",
                  uncacheableFunctions.stream()
                      .map(SqlOperator::getName)
                      .distinct()
                      .collect(Collectors.joining(", ")))));
      return false;
    } else if (materialization.isPresent()
        && !materialization.get().isMaterializationCacheInitialized()) {
      plannerEventBus.dispatch(
          new PlanCacheEvent(NOT_PUT_MAT_CACHE_NOT_INIT, "Materialization cache not initialized."));

      return false;
    } else if (containsBlacklistedNode(relNode)) {
      plannerEventBus.dispatch(
          new PlanCacheEvent(NOT_PUT_BLACKLISTED, "Contains blacklisted node."));
      return false;
    } else if (optionResolver.getOption(PlannerSettings.QUERY_PLAN_USE_LEGACY_CACHE)
        && checkForVersionedTable(config)) {
      plannerEventBus.dispatch(
          new PlanCacheEvent(NOT_PUT_VERSIONED_TABLE, "Query contains a versioned table."));
      return false;
    } else {
      return true;
    }
  }

  public static PlanCacheKey generateCacheKey(
      SqlHandlerConfig sqlHandlerConfig, SqlNode sqlNode, RelNode relNode) {
    QueryContext context = sqlHandlerConfig.getContext();
    Hasher hasher = Hashing.sha256().newHasher();

    String workloadType = context.getWorkloadType().name();
    if (workloadType.equals(WorkloadType.DDL.name())
        || workloadType.equals(WorkloadType.FLIGHT.name())
        || workloadType.equals(WorkloadType.JDBC.name())
        || workloadType.equals(WorkloadType.ODBC.name())) {
      workloadType = "DDLOrPrepareOrExecute";
    }

    hasher
        .putString(sqlNode.toSqlString(CalciteSqlDialect.DEFAULT).getSql(), UTF_8)
        .putString(RelOptUtil.toString(relNode), UTF_8)
        .putString(workloadType, UTF_8)
        .putString(context.getContextInformation().getCurrentDefaultSchema(), UTF_8);

    if (context.getPlannerSettings().isPlanCacheEnableSecuredUserBasedCaching()) {
      hasher.putString(context.getQueryUserName(), UTF_8);
    }

    hasher.putString("supportComplexTypes", UTF_8);
    hasher.putBoolean(context.getSession().isSupportComplexTypes());

    hashNonDefaultOptions(hasher, context, ImmutableSet.of());

    Optional.ofNullable(context.getGroupResourceInformation())
        .ifPresent(
            v -> {
              hasher.putInt(v.getExecutorNodeCount());
              hasher.putLong(v.getAverageExecutorCores(context.getOptions()));
            });

    HashCode materializationHash = hashDremioMaterializationList(sqlHandlerConfig, relNode);

    return new PlanCacheKey(hasher.hash(), materializationHash);
  }

  /**
   * @param sqlHandlerConfig
   * @param planCacheEntry
   * @return a Pair of prel and TexPlan
   */
  public static PrelAndTextPlan extractPrel(
      SqlHandlerConfig sqlHandlerConfig, PlanCacheEntry planCacheEntry) {
    PlannerSettings plannerSettings = sqlHandlerConfig.getContext().getPlannerSettings();
    AttemptObserver attemptObserver = sqlHandlerConfig.getObserver();
    Prel prel = planCacheEntry.getPrel();

    // After the plan has been cached during planning, the job could be canceled during
    // execution.
    // Reset the cancel flag in cached plan, otherwise the job will always be canceled.
    prel.getCluster()
        .getPlanner()
        .getContext()
        .unwrap(org.apache.calcite.util.CancelFlag.class)
        .clearCancel();

    final PlannerSettings.StoreQueryResultsPolicy storeQueryResultsPolicy =
        plannerSettings.storeQueryResultsPolicy();

    PlanCacheMetrics.reportStoreQueryResultPolicy(storeQueryResultsPolicy);
    if (storeQueryResultsPolicy == PlannerSettings.StoreQueryResultsPolicy.PATH_AND_ATTEMPT_ID) {
      // update writing path for this case only
      prel = WriterPathUpdater.update(prel, sqlHandlerConfig);
    }

    String textPlan = PrelSequencer.getPlanText(prel, SqlExplainLevel.ALL_ATTRIBUTES);
    String jsonPlan = PrelSequencer.getPlanJson(prel, SqlExplainLevel.ALL_ATTRIBUTES);

    attemptObserver.planJsonPlan(jsonPlan);
    attemptObserver.planStepLogging(PlannerPhase.PLAN_FINAL_PHYSICAL, textPlan, 0);

    return new PrelAndTextPlan(prel, textPlan);
  }

  /** Put non-default options into plan cache key hash. */
  public static void hashNonDefaultOptions(
      Hasher hasher, QueryContext context, Set<String> excludeOptionNames) {
    context.getOptions().getNonDefaultOptions().stream()
        // A sanity filter in case an option with default value is put into non-default options
        .filter(optionValue -> !context.getOptions().getDefaultOptions().contains(optionValue))
        .filter(optionValue -> !excludeOptionNames.contains(optionValue.getName()))
        .sorted()
        .forEach(
            (v) -> {
              hasher.putString(v.getName(), UTF_8);
              switch (v.getKind()) {
                case BOOLEAN:
                  hasher.putBoolean(v.getBoolVal());
                  break;
                case DOUBLE:
                  hasher.putDouble(v.getFloatVal());
                  break;
                case LONG:
                  hasher.putLong(v.getNumVal());
                  break;
                case STRING:
                  hasher.putString(v.getStringVal(), UTF_8);
                  break;
                default:
                  throw new AssertionError("Unsupported OptionValue kind: " + v.getKind());
              }
            });
  }

  private static boolean containsBlacklistedNode(RelNode relNode) {
    if (relNode instanceof MFunctionQueryRelBase) {
      return true;
    } else if (relNode instanceof CopyErrorsRelBase) {
      return true;
    } else if (relNode instanceof CopyIntoTableRelBase) {
      return true;
    } else if (relNode instanceof TableOptimizeRelBase) {
      return true;
    } else if (relNode instanceof VacuumTableRelBase) {
      return true;
    } else if (relNode instanceof VacuumCatalogRelBase) {
      return true;
    } else {
      for (RelNode child : relNode.getInputs()) {
        if (containsBlacklistedNode(child)) {
          return true;
        }
      }
      return false;
    }
  }

  private static boolean checkForVersionedTable(SqlHandlerConfig sqlHandlerConfig) {
    PlannerCatalog plannerCatalog = sqlHandlerConfig.getConverter().getPlannerCatalog();
    Catalog catalog = sqlHandlerConfig.getContext().getCatalog();
    for (DremioTable table : plannerCatalog.getAllRequestedTables()) {
      if (CatalogUtil.requestedPluginSupportsVersionedTables(table.getPath(), catalog)) {
        // Versioned tables don't have a mtime - they have snapshot ids.  Since we don't have a
        // way
        // to invalidate
        // cache entries containing versioned datasets, don't allow these plans to enter the
        // cache.
        return true;
      }
    }
    return false;
  }

  private static HashCode hashDremioMaterializationList(
      SqlHandlerConfig sqlHandlerConfig, RelNode relNode) {
    Hasher hasher = Hashing.sha256().newHasher();
    if (sqlHandlerConfig.getMaterializations().isEmpty()) {
      return hasher.hash();
    }
    MaterializationList materializationList = sqlHandlerConfig.getMaterializations().get();
    List<DremioMaterialization> dremioMaterializationList =
        materializationList.buildConsideredMaterializations(relNode);

    dremioMaterializationList.stream()
        .map(DremioMaterialization::getMaterializationId)
        .forEach(hasher::putUnencodedChars);
    return hasher.hash();
  }

  public static class PrelAndTextPlan {
    private final Prel prel;
    private final String textPlan;

    private PrelAndTextPlan(Prel prel, String textPlan) {
      this.prel = prel;
      this.textPlan = textPlan;
    }

    public Prel getPrel() {
      return prel;
    }

    public String getTextPlan() {
      return textPlan;
    }
  }
}
