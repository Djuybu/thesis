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
import static com.dremio.exec.planner.plancache.PlanCacheMetrics.PlanCachePhases.PLAN_CACHE_CHECK;
import static com.dremio.exec.planner.plancache.PlanCacheMetrics.PlanCachePhases.PLAN_CACHE_PUT;

import com.dremio.exec.planner.events.PlannerEventBus;
import com.dremio.exec.planner.observer.AttemptObserver;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.plancache.PlanCacheMetrics.PlanCacheEvent;
import com.dremio.exec.planner.sql.handlers.SqlHandlerConfig;
import com.dremio.options.OptionResolver;
import com.google.common.base.Stopwatch;
import java.util.ArrayList;
import javax.annotation.Nullable;

/**
 * A delegating implementation of {@link PlanCache} which handles report metrics, honoring configs,
 * and
 */
public class ReportingPlanCache implements PlanCache {

  private final PlanCache planCache;
  private final PlannerEventBus plannerEventBus;
  private final AttemptObserver attemptObserver;
  private final OptionResolver optionResolver;

  public ReportingPlanCache(
      PlanCache planCache,
      PlannerEventBus plannerEventBus,
      AttemptObserver attemptObserver,
      OptionResolver optionResolver) {
    this.planCache = planCache;
    this.plannerEventBus = plannerEventBus;
    this.attemptObserver = attemptObserver;
    this.optionResolver = optionResolver;
  }

  @Override
  public boolean putCachedPlan(SqlHandlerConfig config, PlanCacheKey cachedKey, Prel prel) {
    Stopwatch stopwatch = Stopwatch.createStarted();
    var events = new ArrayList<PlanCacheEvent>();
    try (var ignore = plannerEventBus.register(handle(PlanCacheEvent.class, events::add))) {
      final boolean success = planCache.putCachedPlan(config, cachedKey, prel);
      if (success) {
        plannerEventBus.dispatch(PlanCacheMetrics.createSuccessfulPut());
      } else {
        plannerEventBus.dispatch(PlanCacheMetrics.createFailedPut());
      }
      return success;
    } finally {
      PlanCacheMetrics.reportPhase(attemptObserver, PLAN_CACHE_PUT, cachedKey, events, stopwatch);
    }
  }

  @Override
  public @Nullable PlanCacheEntry getIfPresentAndValid(
      SqlHandlerConfig sqlHandlerConfig, PlanCacheKey key) {
    Stopwatch stopwatch = Stopwatch.createStarted();
    var events = new ArrayList<PlanCacheEvent>();
    try (var ignore = plannerEventBus.register(handle(PlanCacheEvent.class, events::add))) {
      if (!optionResolver.getOption(PlannerSettings.QUERY_PLAN_CACHE_ENABLED)) {
        plannerEventBus.dispatch(PlanCacheMetrics.cachePlanCacheDisabled());
        return null;
      }
      @Nullable
      PlanCacheEntry planCacheEntry = planCache.getIfPresentAndValid(sqlHandlerConfig, key);
      if (null == planCacheEntry) {
        plannerEventBus.dispatch(PlanCacheMetrics.createCacheMissEvent());
        return null;
      } else {
        PlanCacheMetrics.observeCacheUsed(plannerEventBus, planCacheEntry);
        planCacheEntry.updateUseCount();
        attemptObserver.restoreAccelerationProfileFromCachedPlan(
            planCacheEntry.getAccelerationProfile());
        plannerEventBus.dispatch(PlanCacheMetrics.createCacheUsed(planCacheEntry));

        return planCacheEntry;
      }
    } finally {
      stopwatch.stop();
      PlanCacheMetrics.reportPhase(attemptObserver, PLAN_CACHE_CHECK, key, events, stopwatch);
    }
  }
}
