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
package com.dremio.exec.catalog;

import com.dremio.exec.maestro.GlobalKeysService;
import com.dremio.exec.store.dfs.MetadataIOPool;
import com.dremio.service.listing.DatasetListingService;
import com.dremio.service.orphanage.Orphanage;
import com.dremio.services.credentials.SecretsCreator;
import javax.inject.Provider;

/** The version of SabotContext for usage in the query engine catalog. */
public interface CatalogSabotContext extends PluginSabotContext {
  Provider<GlobalKeysService> getGlobalCredentialsServiceProvider();

  Provider<SecretsCreator> getSecretsCreator();

  DatasetListingService getDatasetListing();

  Orphanage.Factory getOrphanageFactory();

  Provider<MetadataIOPool> getMetadataIOPoolProvider();

  boolean isMaster();
}
