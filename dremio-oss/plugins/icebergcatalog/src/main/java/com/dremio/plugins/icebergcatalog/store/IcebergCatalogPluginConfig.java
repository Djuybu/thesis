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

import com.dremio.exec.catalog.conf.ConnectionConf;
import com.dremio.exec.catalog.conf.DisplayMetadata;
import com.dremio.exec.catalog.conf.NotMetadataImpacting;
import com.dremio.exec.catalog.conf.Property;
import com.dremio.exec.catalog.conf.Secret;
import com.dremio.exec.store.dfs.AsyncStreamConf;
import com.dremio.exec.store.dfs.CacheProperties;
import com.dremio.exec.store.dfs.MutablePluginConf;
import com.dremio.options.OptionManager;
import io.protostuff.Tag;
import java.util.List;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;

/** Abstract PluginConfig class, that provides common properties for IcebergCatalogPlugin */
public abstract class IcebergCatalogPluginConfig
    extends ConnectionConf<IcebergCatalogPluginConfig, IcebergCatalogPlugin>
    implements AsyncStreamConf, MutablePluginConf {

  // 1-9   - IcebergCatalogPluginConfig
  // 10-19 - RestIcebergCatalogPluginConfig
  // 20-109 - Reserved by other plugins

  @Tag(1)
  @DisplayMetadata(label = "Catalog Properties")
  public List<Property> propertyList;

  @Tag(2)
  @DisplayMetadata(label = "Catalog Credentials")
  @Secret
  public List<Property> secretPropertyList;

  @Tag(3)
  @DisplayMetadata(label = "Enable asynchronous access for Parquet datasets")
  public boolean enableAsync = true;

  @Tag(4)
  @NotMetadataImpacting
  @DisplayMetadata(label = "Enable local caching when possible")
  public boolean isCachingEnabled = true;

  @Tag(5)
  @NotMetadataImpacting
  @Min(value = 1, message = "Max percent of total available cache space must be between 1 and 100")
  @Max(
      value = 100,
      message = "Max percent of total available cache space must be between 1 and 100")
  @DisplayMetadata(label = "Max percent of total available cache space to use when possible")
  public int maxCacheSpacePct = 100;

  @Override
  public boolean isAsyncEnabled() {
    return enableAsync;
  }

  @Override
  public CacheProperties getCacheProperties() {
    return new CacheProperties() {
      @Override
      public boolean isCachingEnabled(final OptionManager optionManager) {
        return isCachingEnabled;
      }

      @Override
      public int cacheMaxSpaceLimitPct() {
        return maxCacheSpacePct;
      }
    };
  }
}
