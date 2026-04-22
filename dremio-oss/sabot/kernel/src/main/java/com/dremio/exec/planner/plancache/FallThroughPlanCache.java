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

import static org.slf4j.LoggerFactory.getLogger;

import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.sql.handlers.SqlHandlerConfig;
import java.util.List;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

public class FallThroughPlanCache implements PlanCache {
  private static final Logger LOGGER = getLogger(FallThroughPlanCache.class);
  private List<PlanCache> caches;

  public FallThroughPlanCache(List<PlanCache> caches) {
    this.caches = caches;
  }

  @Override
  public boolean putCachedPlan(SqlHandlerConfig config, PlanCacheKey cachedKey, Prel prel) {
    for (PlanCache cache : caches) {
      try {
        boolean inserted = cache.putCachedPlan(config, cachedKey, prel);
        if (inserted) {
          return true;
        }
      } catch (Exception e) {
        LOGGER.debug("Failed to put plan in cache", e);
      }
    }
    return false;
  }

  @Nullable
  @Override
  public PlanCacheEntry getIfPresentAndValid(
      SqlHandlerConfig sqlHandlerConfig, PlanCacheKey planCacheKey) {
    for (PlanCache cache : caches) {
      PlanCacheEntry entry = cache.getIfPresentAndValid(sqlHandlerConfig, planCacheKey);
      if (entry != null) {
        return entry;
      }
    }
    return null;
  }
}
