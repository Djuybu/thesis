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

import com.dremio.config.DremioConfig;
import com.dremio.exec.catalog.PluginSabotContext;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.catalog.conf.DisplayMetadata;
import io.protostuff.Tag;
import java.util.List;
import javax.inject.Provider;

public class RestIcebergCatalogPluginConfig extends IcebergCatalogPluginConfig {

  // 1-9   - IcebergCatalogPluginConfig
  // 10-19 - RestIcebergCatalogPluginConfig
  // 20-109 - Reserved by other plugins

  @Tag(10)
  @DisplayMetadata(label = "Endpoint URI")
  public String restEndpointUri;

  @Tag(11)
  @DisplayMetadata(label = "Allowed Namespaces")
  /**
   * List of allowed namespaces. Set to null by default to indicate that all namespaces are visible
   * Uses {@code com.dremio.exec.store.IcebergCatalogPluginOptions.RESTCATALOG_ALLOWED_NS_SEPARATOR}
   * as NS separator regex sequence, by default "\\."
   */
  public List<String> allowedNamespaces;

  @Tag(12)
  @DisplayMetadata(label = "Allowed Namespaces include their whole subtrees")
  /**
   * List of allowed namespaces. Set to null by default to indicate that all namespaces are visible
   * Uses {@code com.dremio.exec.store.IcebergCatalogPluginOptions.RESTCATALOG_ALLOWED_NS_SEPARATOR}
   * as NS separator regex sequence, by default "\\."
   */
  public boolean isRecursiveAllowedNamespaces = true;

  public String getRestEndpointURI(DremioConfig dremioConfig) {
    return restEndpointUri;
  }

  @Override
  public IcebergCatalogPlugin newPlugin(
      PluginSabotContext pluginSabotContext,
      String name,
      Provider<StoragePluginId> pluginIdProvider) {
    return new RestIcebergCatalogPlugin(this, pluginSabotContext, name, pluginIdProvider);
  }
}
