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
package com.dremio.exec.planner.plancache.distributable;

import static com.google.common.base.Preconditions.checkNotNull;

import com.dremio.datastore.api.Document;
import com.dremio.datastore.transientstore.TransientStore;
import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.plancache.PlanCache;
import com.dremio.exec.planner.plancache.PlanCacheKey;
import com.dremio.exec.planner.plancache.PlanCacheValidationUtils;
import com.dremio.exec.planner.sql.handlers.SqlHandlerConfig;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DistributablePlanCache implements PlanCache {

  public static final Logger LOGGER = LoggerFactory.getLogger(DistributablePlanCache.class);

  private final TransientStore<byte[], DistributedPlanCacheEntry> transientStore;

  public DistributablePlanCache(TransientStore<byte[], DistributedPlanCacheEntry> transientStore) {
    this.transientStore = checkNotNull(transientStore, "transientStore");
  }

  @Override
  public boolean putCachedPlan(SqlHandlerConfig config, PlanCacheKey cachedKey, Prel prel) {
    if (!DistributablePlanCacheUtils.isSupported(prel)) {
      return false;
    }
    DistributedPlanCacheEntry distrubutedCachedPlan =
        new DistributedPlanCacheEntry(prel, cachedKey.getMaterializationHashString());
    config.getObserver().addAccelerationProfileToCachedPlan(distrubutedCachedPlan);
    // This needs to be removed later.
    config.getConverter().dispose();
    return transientStore.put(cachedKey.getBytesHash(), distrubutedCachedPlan) != null;
  }

  @Override
  public @Nullable DistributedPlanCacheEntry getIfPresentAndValid(
      SqlHandlerConfig sqlHandlerConfig, PlanCacheKey planCacheKey) {
    Document<byte[], DistributedPlanCacheEntry> document =
        transientStore.get(planCacheKey.getBytesHash());
    if (null == document) {
      return null;
    } else if (!PlanCacheValidationUtils.checkIfValid(
        sqlHandlerConfig, planCacheKey, document.getValue())) {
      return null;
    } else {
      return document.getValue();
    }
  }
}
