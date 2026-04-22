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

import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.service.namespace.dataset.proto.DatasetType;

/**
 * Listens for catalog access events in CachingCatalog such as when a new dataset is requested that
 * isn't cached yet or when a dataset has been retrieved from the underlying catalog layers and
 * loaded into the cache.
 */
public interface CatalogAccessListener {

  CatalogAccessListener NO_OP =
      new CatalogAccessListener() {
        @Override
        public void newDatasetRequested(CatalogIdentity identity, CatalogEntityKey key) {}

        @Override
        public void datasetLoaded(
            CatalogIdentity identity,
            CatalogEntityKey key,
            long loadTimeMillis,
            int resolutions,
            DatasetType datasetType) {}
      };

  void newDatasetRequested(CatalogIdentity identity, CatalogEntityKey key);

  void datasetLoaded(
      CatalogIdentity identity,
      CatalogEntityKey key,
      long loadTimeMillis,
      int resolutions,
      DatasetType datasetType);
}
