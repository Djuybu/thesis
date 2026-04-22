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
package com.dremio.exec.planner.sql.handlers.query;

import static org.slf4j.LoggerFactory.getLogger;

import com.dremio.common.logical.PlanProperties.Generator.ResultMode;
import com.dremio.common.util.Closeable;
import com.dremio.exec.catalog.CatalogOptions;
import com.dremio.exec.physical.PhysicalPlan;
import com.dremio.exec.physical.base.PhysicalOperator;
import com.dremio.exec.planner.events.FunctionDetectedEvent;
import com.dremio.exec.planner.events.PlannerEventBus;
import com.dremio.exec.planner.events.PlannerEventHandler;
import com.dremio.exec.planner.logical.Rel;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.plancache.PlanCache;
import com.dremio.exec.planner.plancache.PlanCacheEntry;
import com.dremio.exec.planner.plancache.PlanCacheKey;
import com.dremio.exec.planner.plancache.PlanCacheUtils;
import com.dremio.exec.planner.plancache.PlanCacheUtils.PrelAndTextPlan;
import com.dremio.exec.planner.sql.SqlExceptionHelper;
import com.dremio.exec.planner.sql.UncacheableFunctionDetector;
import com.dremio.exec.planner.sql.handlers.ConvertedRelNode;
import com.dremio.exec.planner.sql.handlers.DrelTransformer;
import com.dremio.exec.planner.sql.handlers.PlanLogUtil;
import com.dremio.exec.planner.sql.handlers.PrelTransformer;
import com.dremio.exec.planner.sql.handlers.SqlHandlerConfig;
import com.dremio.exec.planner.sql.handlers.SqlToRelTransformer;
import com.dremio.exec.work.foreman.SqlUnsupportedException;
import com.google.common.annotations.VisibleForTesting;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.tools.RelConversionException;
import org.apache.calcite.tools.ValidationException;
import org.apache.calcite.util.Pair;
import org.immutables.value.Value;
import org.slf4j.Logger;

/** The default handler for queries. */
public class NormalHandler implements SqlToPlanHandler {

  private static final Logger LOGGER = getLogger(NormalHandler.class);

  private String textPlan;
  private Rel drel;
  private Prel prel;
  private ConvertedRelNode convertedRelNode;
  private RelNode relPlanExplain;

  private UncacheableFunctionDetectedEventHandler uncacheableFunctionDetectedEventHandler =
      new UncacheableFunctionDetectedEventHandler();

  @WithSpan
  @Override
  public PhysicalPlan getPlan(SqlHandlerConfig config, String sql, SqlNode sqlNode)
      throws Exception {
    PlannerEventBus plannerEventBus = config.getPlannerEventBus();
    try (Closeable ignored = plannerEventBus.register(uncacheableFunctionDetectedEventHandler)) {
      Span.current()
          .setAttribute(
              "dremio.planner.workload_type", config.getContext().getWorkloadType().name());
      Span.current()
          .setAttribute(
              "dremio.planner.current_default_schema",
              config.getContext().getContextInformation().getCurrentDefaultSchema());
      final PlanCache planCache = config.getPlanCache();

      prePlan(config, sql, sqlNode);

      convertedRelNode = SqlToRelTransformer.validateAndConvert(config, sqlNode);
      if (config.getResultMode() == ResultMode.CONVERT_ONLY) {
        relPlanExplain = convertedRelNode.getConvertedNode();
        return null;
      }
      convertedRelNode = postConvertToRel(convertedRelNode);

      final PlanCacheKey cachedKey;
      PlanCacheEntry planCacheEntry;

      cachedKey =
          PlanCacheUtils.generateCacheKey(config, sqlNode, convertedRelNode.getConvertedNode());
      planCacheEntry = planCache.getIfPresentAndValid(config, cachedKey);

      Prel prel;
      if (planCacheEntry == null) {
        DrelPrelAndTextPlan drelPrelAndTextPlan =
            planLogicalAndPhysical(config, convertedRelNode, this::postConvertToDrel);
        // These probably should be emmited as events instead mutating the properties.
        drel = drelPrelAndTextPlan.drel();
        relPlanExplain = drel;
        prel = drelPrelAndTextPlan.prel();
        textPlan = drelPrelAndTextPlan.textPlan();
        if (drelPrelAndTextPlan.exitEarly()) {
          return null;
        }
        PlanCacheUtils.putIntoPlanCache(
            config,
            sqlNode,
            planCache,
            cachedKey,
            drel,
            prel,
            uncacheableFunctionDetectedEventHandler.getUncacheableFunctions());
      } else {
        PrelAndTextPlan prelAndTextPlan = PlanCacheUtils.extractPrel(config, planCacheEntry);
        prel = prelAndTextPlan.getPrel();
        textPlan = prelAndTextPlan.getTextPlan();
      }

      prel = postConvertToPrel(prel);

      PhysicalOperator pop = PrelTransformer.convertToPop(config, prel);
      pop = postConvertToPhysicalOperator(pop);

      PhysicalPlan plan = PrelTransformer.convertToPlan(config, pop);
      plan = postConvertToPhysicalPlan(plan);

      PlanLogUtil.log(config, "Dremio Plan", plan, LOGGER);
      this.prel = prel;
      relPlanExplain = prel;
      return plan;
    } catch (Error ex) {
      throw SqlExceptionHelper.coerceError(sql, ex);
    } catch (Exception ex) {
      throw SqlExceptionHelper.coerceException(LOGGER, sql, ex, true);
    }
  }

  @VisibleForTesting
  public Prel getPrel() {
    return prel;
  }

  @Override
  public String getTextPlan() {
    return textPlan;
  }

  @Override
  public Rel getLogicalPlan() {
    return drel;
  }

  @Override
  public RelNode getPlanForExplain() {
    return relPlanExplain;
  }

  protected void prePlan(SqlHandlerConfig config, String sql, SqlNode sqlNode) {}

  protected ConvertedRelNode postConvertToRel(ConvertedRelNode rel) {
    return rel;
  }

  protected Rel postConvertToDrel(Rel drel) {
    return drel;
  }

  protected Prel postConvertToPrel(Prel prel) {
    return prel;
  }

  protected PhysicalOperator postConvertToPhysicalOperator(PhysicalOperator pop) {
    return pop;
  }

  protected PhysicalPlan postConvertToPhysicalPlan(PhysicalPlan plan) {
    return plan;
  }

  private static DrelPrelAndTextPlan planLogicalAndPhysical(
      SqlHandlerConfig sqlHandlerConfig,
      ConvertedRelNode convertedRelNode,
      Function<Rel, Rel> postConvertToDrel)
      throws SqlUnsupportedException, RelConversionException, ValidationException {
    PlannerSettings plannerSettings = sqlHandlerConfig.getContext().getPlannerSettings();
    final RelDataType validatedRowType = convertedRelNode.getValidatedRowType();
    final RelNode queryRelNode = convertedRelNode.getConvertedNode();
    Rel drel = DrelTransformer.convertToDrel(sqlHandlerConfig, queryRelNode, validatedRowType);
    drel = postConvertToDrel.apply(drel);
    if (sqlHandlerConfig.getResultMode().equals(ResultMode.LOGICAL)) {
      // we only want to do logical planning, there is no point going further in the plan
      // generation
      return new ImmutableDrelPrelAndTextPlan.Builder().setDrel(drel).setExitEarly(true).build();
    }
    if (!plannerSettings.ignoreScannedColumnsLimit()) {
      long maxScannedColumns =
          sqlHandlerConfig
              .getContext()
              .getOptions()
              .getOption(CatalogOptions.METADATA_LEAF_COLUMN_SCANNED_MAX);
      ScanLimitValidator.ensureLimit(drel, maxScannedColumns);
    }

    final Pair<Prel, String> prel = PrelTransformer.convertToPrel(sqlHandlerConfig, drel);

    return new ImmutableDrelPrelAndTextPlan.Builder()
        .setDrel(drel)
        .setPrel(prel.left)
        .setTextPlan(prel.right)
        .setExitEarly(false)
        .build();
  }

  protected static final class UncacheableFunctionDetectedEventHandler
      implements PlannerEventHandler<FunctionDetectedEvent> {

    private final List<SqlOperator> uncacheableFunctions;

    public UncacheableFunctionDetectedEventHandler() {
      uncacheableFunctions = new ArrayList<>();
    }

    @Override
    public void handle(FunctionDetectedEvent event) {
      if (UncacheableFunctionDetector.isA(event.getSqlOperator())) {
        uncacheableFunctions.add(event.getSqlOperator());
      }
    }

    @Override
    public Class<FunctionDetectedEvent> supports() {
      return FunctionDetectedEvent.class;
    }

    public List<SqlOperator> getUncacheableFunctions() {
      return uncacheableFunctions;
    }
  }

  protected ConvertedRelNode getConvertedRelNode() {
    return convertedRelNode;
  }

  public UncacheableFunctionDetectedEventHandler getUncacheableFunctionDetectedEventHandler() {
    return uncacheableFunctionDetectedEventHandler;
  }

  @Value.Immutable
  public interface DrelPrelAndTextPlan {

    Rel drel();

    Prel prel();

    String textPlan();

    boolean exitEarly();
  }
}
