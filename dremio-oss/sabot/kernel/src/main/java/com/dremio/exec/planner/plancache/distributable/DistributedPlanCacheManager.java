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

import com.dremio.datastore.format.Format;
import com.dremio.datastore.transientstore.CaffeinePojoValueTransientStore;
import com.dremio.datastore.transientstore.Marshaller;
import com.dremio.datastore.transientstore.TransientStore;
import com.dremio.distributedplancache.transientstore.DistributedPlanCacheTransientStore;
import com.dremio.exec.planner.plancache.PlanCache;
import com.dremio.exec.planner.sql.SqlConverter;
import com.dremio.proto.model.TransientStore.ByteArrayKey;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Weigher;
import java.util.concurrent.TimeUnit;

public class DistributedPlanCacheManager {
  private final PlanCacheEntryMarshaller.Factory marshallerFactory;
  private final Cache<ByteArrayKey, DistributedPlanCacheEntry> caffeinePojoCache;
  private final TransientStore<ByteArrayKey, byte[]> transientStore;

  public DistributedPlanCacheManager(
      PlanCacheEntryMarshaller.Factory marshallerFactory,
      TransientStore<ByteArrayKey, byte[]> transientStore,
      Cache<ByteArrayKey, DistributedPlanCacheEntry> caffeinePojoCache) {
    this.marshallerFactory = marshallerFactory;
    this.caffeinePojoCache = caffeinePojoCache;
    this.transientStore = transientStore;
  }

  public PlanCache createPlanCache() {
    CaffeinePojoValueTransientStore<byte[], DistributedPlanCacheEntry> transientStore =
        new CaffeinePojoValueTransientStore<>(caffeinePojoCache, Format.ofBytes());
    return new DistributablePlanCache(transientStore);
  }

  public boolean supportsSerializingCache() {
    return null != marshallerFactory;
  }

  public PlanCache createSerializingCache(SqlConverter sqlConverter) {
    PlanCacheEntryMarshaller planCacheEntryMarshaller =
        marshallerFactory.build(
            sqlConverter.getPhysicalPlanReader(),
            sqlConverter.getOpTab(),
            sqlConverter.getCluster());
    // Create Distributed plan cache store from a transient store.
    // This design enable us to configure any implementation of TransientStore for DPC.
    DistributedPlanCacheTransientStore<byte[], DistributedPlanCacheEntry>
        distributedPlanCacheTransientStore =
            new DistributedPlanCacheTransientStore<>(
                this.transientStore,
                Marshaller.BYTE_MARSHALLER,
                planCacheEntryMarshaller.withCompression());
    return new DistributablePlanCache(distributedPlanCacheTransientStore);
  }

  public static DistributedPlanCacheManager create(
      Class<? extends PlanCacheEntryMarshaller.Factory> factoryClass,
      long maxCacheEntries,
      long expiresAfterMinute,
      TransientStore<ByteArrayKey, byte[]> transientStore) {
    PlanCacheEntryMarshaller.Factory factory;
    if (PlanCacheEntryMarshaller.Factory.class == factoryClass) {
      factory = null;
    } else {
      try {
        factory = factoryClass.getDeclaredConstructor().newInstance();
      } catch (SecurityException | ReflectiveOperationException ex) {
        throw new RuntimeException(ex);
      }
    }
    Cache<ByteArrayKey, DistributedPlanCacheEntry> pojoCache =
        Caffeine.newBuilder()
            .maximumWeight(maxCacheEntries)
            .weigher((Weigher<ByteArrayKey, DistributedPlanCacheEntry>) (key, cachedPlan) -> 1)
            .softValues()
            .expireAfterAccess(expiresAfterMinute, TimeUnit.MINUTES)
            .build();
    return new DistributedPlanCacheManager(factory, transientStore, pojoCache);
  }

  public void invalidateAll() {
    caffeinePojoCache.invalidateAll();
    transientStore.deleteAll();
  }
}
