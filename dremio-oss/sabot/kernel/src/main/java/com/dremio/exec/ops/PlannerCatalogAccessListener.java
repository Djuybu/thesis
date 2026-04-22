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
package com.dremio.exec.ops;

import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.exec.catalog.CatalogAccessListener;
import com.dremio.exec.catalog.CatalogAccessStats;
import com.dremio.exec.catalog.CatalogIdentity;
import com.dremio.service.namespace.dataset.proto.DatasetType;

public class PlannerCatalogAccessListener implements CatalogAccessListener {

  private CatalogAccessStats allCatalogAccesses = new CatalogAccessStats();
  private CatalogAccessStats currentPhaseCatalogAccesses = new CatalogAccessStats();

  @Override
  public void newDatasetRequested(CatalogIdentity identity, CatalogEntityKey key) {
    currentPhaseCatalogAccesses.addRequestedDataset(identity, key);
  }

  @Override
  public void datasetLoaded(
      CatalogIdentity identity,
      CatalogEntityKey key,
      long loadTimeMillis,
      int resolutions,
      DatasetType datasetType) {
    currentPhaseCatalogAccesses.addLoadedDataset(
        identity, key, loadTimeMillis, resolutions, datasetType);
  }

  public void newPlanPhase() {
    allCatalogAccesses = allCatalogAccesses.merge(currentPhaseCatalogAccesses);
    currentPhaseCatalogAccesses = new CatalogAccessStats();
  }

  public CatalogAccessStats getCurrentPhaseCatalogAccesses() {
    return currentPhaseCatalogAccesses;
  }

  public CatalogAccessStats getAllCatalogAccesses() {
    return allCatalogAccesses;
  }
}
