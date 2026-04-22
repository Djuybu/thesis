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
package com.dremio.plugins.icebergcatalog.store;

import static org.junit.Assert.assertEquals;

import com.dremio.BaseTestQuery;
import com.dremio.exec.catalog.PluginSabotContext;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.store.dfs.CacheProperties;
import javax.inject.Provider;
import org.junit.Test;

public class TestIcebergCatalogPluginConfig extends BaseTestQuery {

  static class TestAbstractIcebergCatalogPluginConfig extends IcebergCatalogPluginConfig {

    public TestAbstractIcebergCatalogPluginConfig() {}

    @Override
    public IcebergCatalogPlugin newPlugin(
        PluginSabotContext pluginSabotContext,
        String name,
        Provider<StoragePluginId> pluginIdProvider) {
      return null;
    }
  }

  private final TestAbstractIcebergCatalogPluginConfig testAbstractIcebergCatalogPluginConfig =
      new TestAbstractIcebergCatalogPluginConfig();

  @Test
  public void testGetCacheProperties() {
    CacheProperties cacheProperties = testAbstractIcebergCatalogPluginConfig.getCacheProperties();
    assertEquals(
        testAbstractIcebergCatalogPluginConfig.isCachingEnabled,
        cacheProperties.isCachingEnabled(null));
    assertEquals(
        testAbstractIcebergCatalogPluginConfig.maxCacheSpacePct,
        cacheProperties.cacheMaxSpaceLimitPct());
  }
}
