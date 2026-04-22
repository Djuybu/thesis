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

import static com.dremio.exec.planner.physical.PlannerSettings.QUERY_PLAN_CACHE_FALL_THROUGH;
import static com.dremio.exec.planner.physical.PlannerSettings.QUERY_PLAN_CACHE_SERIALIZE_PLAN;
import static com.dremio.exec.planner.physical.PlannerSettings.QUERY_PLAN_USE_LEGACY_CACHE;

import com.dremio.exec.planner.observer.AttemptObserver;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.planner.plancache.distributable.DistributedPlanCacheManager;
import com.dremio.exec.planner.sql.SqlConverter;
import com.dremio.options.OptionResolver;
import com.google.common.collect.ImmutableList;

public class PlanCacheProviderImpl implements PlanCacheProvider {
  private final DistributedPlanCacheManager distributedPlanCacheManager;
  private final LegacyPlanCache legacyPlanCache;

  public PlanCacheProviderImpl(
      DistributedPlanCacheManager distributedPlanCacheManager, LegacyPlanCache legacyPlanCache) {
    this.distributedPlanCacheManager = distributedPlanCacheManager;
    this.legacyPlanCache = legacyPlanCache;
  }

  @Override
  public PlanCache resolve(
      SqlConverter sqlConverter, AttemptObserver attemptObserver, PlannerSettings plannerSettings) {
    PlanCache planCache = resolveInternal(sqlConverter, plannerSettings);
    return new ReportingPlanCache(
        planCache,
        sqlConverter.getPlannerEventBus(),
        attemptObserver,
        sqlConverter.getOptionResolver());
  }

  @Override
  public void invalidateAll() {
    legacyPlanCache.invalidateAll();
    distributedPlanCacheManager.invalidateAll();
  }

  @Override
  public void invalidateCacheOnDataset(String dataSetId) {
    legacyPlanCache.invalidateCacheOnDataset(dataSetId);
  }

  private PlanCache resolveInternal(SqlConverter sqlConverter, PlannerSettings plannerSettings) {
    OptionResolver options = plannerSettings.options;

    if (!plannerSettings.isPlanCacheEnabled()) {
      return PlanCache.EMPTY_CACHE;
    } else if (options.getOption(QUERY_PLAN_USE_LEGACY_CACHE)) {
      return legacyPlanCache;
    } else if (options.getOption(QUERY_PLAN_CACHE_FALL_THROUGH)) {
      return buildFallThroughCache(sqlConverter);
    } else if (usingSerializingCache(options)) {
      return distributedPlanCacheManager.createSerializingCache(sqlConverter);
    } else {
      return distributedPlanCacheManager.createPlanCache();
    }
  }

  private PlanCache buildFallThroughCache(SqlConverter sqlConverter) {
    return new FallThroughPlanCache(
        ImmutableList.of(
            usingSerializingCache(sqlConverter.getOptionResolver())
                ? distributedPlanCacheManager.createSerializingCache(sqlConverter)
                : distributedPlanCacheManager.createPlanCache(),
            legacyPlanCache));
  }

  private boolean usingSerializingCache(OptionResolver options) {
    return options.getOption(QUERY_PLAN_CACHE_SERIALIZE_PLAN)
        && distributedPlanCacheManager.supportsSerializingCache();
  }
}
