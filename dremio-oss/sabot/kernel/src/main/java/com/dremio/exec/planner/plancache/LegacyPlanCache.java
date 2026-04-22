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

import com.dremio.exec.catalog.DremioTable;
import com.dremio.exec.ops.PlannerCatalog;
import com.dremio.exec.planner.common.PlannerMetrics;
import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.sql.handlers.SqlHandlerConfig;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.cache.Weigher;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LegacyPlanCache implements PlanCache {
  private static final Logger LOGGER = LoggerFactory.getLogger(LegacyPlanCache.class);

  private final Cache<String, LegacyPlanCacheEntry> cachePlans;
  private final Multimap<String, String> datasetMap;

  public LegacyPlanCache(
      Cache<String, LegacyPlanCacheEntry> cachePlans, Multimap<String, String> map) {
    this.cachePlans = cachePlans;
    this.datasetMap = map;

    Gauge.builder(
            PlannerMetrics.createName(PlannerMetrics.PREFIX, PlanCacheMetrics.PLAN_CACHE_ENTRIES),
            cachePlans::size)
        .description("Number of plan cache entries")
        .register(Metrics.globalRegistry);
  }

  @Override
  public boolean putCachedPlan(SqlHandlerConfig config, PlanCacheKey cachedKey, Prel prel) {
    final PlannerCatalog catalog =
        Preconditions.checkNotNull(config.getConverter().getPlannerCatalog());

    boolean addedCacheToDatasetMap = false;
    Iterable<DremioTable> datasets = catalog.getAllRequestedTables();
    for (DremioTable dataset : datasets) {
      DatasetConfig datasetConfig;
      try {
        datasetConfig = dataset.getDatasetConfig();
      } catch (IllegalStateException ignore) {
        LOGGER.debug(
            String.format(
                "Dataset %s is ignored (no dataset config available).", dataset.getPath()),
            ignore);
        continue;
      }
      if (datasetConfig == null) {
        LOGGER.debug(
            String.format(
                "Dataset %s is ignored (no dataset config available).", dataset.getPath()));
        continue;
      }
      if (datasetConfig.getPhysicalDataset() == null) {
        continue;
      }
      synchronized (datasetMap) {
        datasetMap.put(datasetConfig.getId().getId(), cachedKey.getStringHash());
      }
      addedCacheToDatasetMap = true;
    }
    if (addedCacheToDatasetMap) {
      // Wiping out RelMetadataCache. It will be holding the RelNodes from the prior
      // planning phases.
      prel.getCluster().invalidateMetadataQuery();

      LegacyPlanCacheEntry newPlanCacheEntry =
          LegacyPlanCacheEntry.createCachedPlan(
              prel, prel.getEstimatedSize(), cachedKey.getMaterializationHashString());
      config.getObserver().addAccelerationProfileToCachedPlan(newPlanCacheEntry);
      cachePlans.put(cachedKey.getStringHash(), newPlanCacheEntry);
      config.getConverter().dispose();
      return true;
    } else {
      LOGGER.debug("Physical plan not cached: Query contains no physical datasets.");
      return false;
    }
  }

  public Cache<String, LegacyPlanCacheEntry> getCachePlans() {
    return cachePlans;
  }

  @Override
  public @Nullable LegacyPlanCacheEntry getIfPresentAndValid(
      SqlHandlerConfig sqlHandlerConfig, PlanCacheKey planCacheKey) {

    final LegacyPlanCacheEntry planCacheEntry =
        cachePlans.getIfPresent(planCacheKey.getStringHash());
    if (null == planCacheEntry) {
      return null;
    } else if (!PlanCacheValidationUtils.checkIfValid(
        sqlHandlerConfig, planCacheKey, planCacheEntry)) {
      cachePlans.invalidate(planCacheKey.getStringHash());
      return null;
    } else {
      return planCacheEntry;
    }
  }

  public void invalidateCacheOnDataset(String datasetId) {
    List<String> affectedCaches = datasetMap.get(datasetId).stream().collect(Collectors.toList());
    for (String cacheId : affectedCaches) {
      cachePlans.invalidate(cacheId);
    }
    if (!affectedCaches.isEmpty()) {
      LOGGER.debug(
          "Physical plan cache invalidated by datasetId {} for cacheKeys {}",
          datasetId,
          affectedCaches);
    }
  }

  public void invalidateAll() {
    cachePlans.invalidateAll();
  }

  public static LegacyPlanCache create(long maxCacheEntries, long expiresAfterMinute) {
    Multimap<String, String> datasetMap =
        Multimaps.synchronizedListMultimap(ArrayListMultimap.create());
    @SuppressWarnings("NoGuavaCacheUsage") // TODO: fix as part of DX-51884
    Cache<String, LegacyPlanCacheEntry> cachedPlans =
        CacheBuilder.newBuilder()
            .maximumWeight(maxCacheEntries)
            .expireAfterAccess(expiresAfterMinute, TimeUnit.MINUTES)
            .weigher(
                (Weigher<String, LegacyPlanCacheEntry>)
                    (key, cachedPlan) -> cachedPlan.getEstimatedSize())
            // plan caches are memory intensive. If there is memory pressure,
            // let GC release them as last resort before running OOM.
            .softValues()
            .removalListener(
                new RemovalListener<String, PlanCacheEntry>() {
                  @Override
                  public void onRemoval(RemovalNotification<String, PlanCacheEntry> notification) {
                    clearDatasetMapOnCacheGC(datasetMap, notification.getKey());
                  }
                })
            .build();
    return new LegacyPlanCache(cachedPlans, datasetMap);
  }

  private static void clearDatasetMapOnCacheGC(
      Multimap<String, String> datasetMap, String cacheId) {
    synchronized (datasetMap) {
      datasetMap.entries().removeIf(datasetMapEntry -> datasetMapEntry.getValue().equals(cacheId));
    }
  }
}
