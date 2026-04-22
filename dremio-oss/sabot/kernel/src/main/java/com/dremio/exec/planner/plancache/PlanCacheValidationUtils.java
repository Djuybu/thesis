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

import static com.dremio.exec.planner.plancache.PlanCacheMetrics.createInvalidMaterializationHash;
import static com.dremio.exec.planner.plancache.PlanCacheMetrics.createInvalidSourceTableUpdate;

import com.dremio.exec.catalog.DremioTable;
import com.dremio.exec.catalog.ManagedStoragePlugin;
import com.dremio.exec.ops.PlannerCatalog;
import com.dremio.exec.planner.sql.handlers.SqlHandlerConfig;
import com.dremio.exec.store.CatalogService;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.source.proto.SourceConfig;
import com.google.common.base.Preconditions;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PlanCacheValidationUtils {

  private static final Logger LOGGER = LoggerFactory.getLogger(PlanCacheValidationUtils.class);

  private PlanCacheValidationUtils() {}

  /**
   * Checks if a plan cache entry is still valid considering whether considered reflections for the
   * query or any table or source's metadata has changed since plan cache entry is created.
   */
  public static boolean checkIfValid(
      SqlHandlerConfig sqlHandlerConfig, PlanCacheKey planCacheKey, PlanCacheEntry planCache) {
    String reflectionEntryHash = planCache.getConsideredReflectionsHash();
    String currentReflectionHash = planCacheKey.getMaterializationHashString();
    if (!Objects.equals(reflectionEntryHash, currentReflectionHash)) {
      sqlHandlerConfig
          .getPlannerEventBus()
          .dispatch(createInvalidMaterializationHash(reflectionEntryHash, currentReflectionHash));
      return false;
    }
    return checkIfAllDatasetValid(sqlHandlerConfig, planCache);
  }

  /**
   * Planning up to this point will have seen all tables used by this query. If any table or
   * source's metadata has been updated after the plan cache entry is created, the entry is stale
   * and can't be used.
   */
  public static boolean checkIfAllDatasetValid(
      SqlHandlerConfig sqlHandlerConfig, PlanCacheEntry planCacheEntry) {
    final PlannerCatalog catalog =
        Preconditions.checkNotNull(sqlHandlerConfig.getConverter().getPlannerCatalog());
    Iterable<DremioTable> datasets = catalog.getAllRequestedTables();

    for (DremioTable dremioTable : datasets) {
      if (!checkIfValid(sqlHandlerConfig, planCacheEntry, dremioTable)) {
        return false;
      }
    }
    return true;
  }

  private static boolean checkIfValid(
      SqlHandlerConfig sqlHandlerConfig, PlanCacheEntry planCacheEntry, DremioTable dremioTable) {
    Long dsUpdateTime = extractUpdateTime(sqlHandlerConfig, dremioTable);
    if (null == dsUpdateTime) {
      sqlHandlerConfig
          .getPlannerEventBus()
          .dispatch(PlanCacheMetrics.createUpdateTimeMissingEvent(dremioTable));
      return false;
    } else if (planCacheEntry.getCreationTime() < dsUpdateTime) {
      sqlHandlerConfig
          .getPlannerEventBus()
          .dispatch(createInvalidSourceTableUpdate(planCacheEntry, dremioTable, dsUpdateTime));
      return false;
    } else {
      return true;
    }
  }

  private static Long extractUpdateTime(
      SqlHandlerConfig sqlHandlerConfig, DremioTable dremioTable) {
    try {
      Long datasetLastModified = extractDatasetUpdateTime(dremioTable);
      Long sourceConfigUpdateTime = extractSourceConfigUpdateTime(sqlHandlerConfig, dremioTable);
      if (null == datasetLastModified) {
        return sourceConfigUpdateTime;
      } else if (null == sourceConfigUpdateTime) {
        return datasetLastModified;
      } else {
        return Math.max(datasetLastModified, sourceConfigUpdateTime);
      }
    } catch (IllegalStateException ignore) {
      LOGGER.debug(
          String.format(
              "Dataset %s is ignored (no dataset config available).", dremioTable.getPath()),
          ignore);
      return null;
    }
  }

  private static Long extractDatasetUpdateTime(DremioTable dremioTable) {
    try {
      DatasetConfig datasetConfig = dremioTable.getDatasetConfig();
      if (datasetConfig != null) {
        return datasetConfig.getLastModified();
      } else {
        return null;
      }
    } catch (IllegalStateException ignore) {
      LOGGER.debug(
          String.format(
              "Dataset %s is ignored (no dataset config available).", dremioTable.getPath()),
          ignore);
      return null;
    }
  }

  private static Long extractSourceConfigUpdateTime(
      SqlHandlerConfig sqlHandlerConfig, DremioTable dremioTable) {
    CatalogService catalogService = sqlHandlerConfig.getContext().getCatalogService();

    ManagedStoragePlugin plugin = catalogService.getManagedSource(dremioTable.getPath().getRoot());
    if (plugin != null) {
      SourceConfig sourceConfig = plugin.getConfig();
      if ((sourceConfig != null)) {
        return sourceConfig.getLastModifiedAt() != null
            ? sourceConfig.getLastModifiedAt()
            : sourceConfig.getCtime();
      }
    }
    return null;
  }
}
