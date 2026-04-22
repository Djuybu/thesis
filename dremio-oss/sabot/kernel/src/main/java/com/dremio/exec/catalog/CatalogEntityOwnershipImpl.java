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
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import java.util.Optional;
import javax.annotation.Nullable;

public class CatalogEntityOwnershipImpl implements CatalogEntityOwnership {
  private final NamespaceService systemNamespaceService;

  public CatalogEntityOwnershipImpl(NamespaceService systemNamespaceService) {
    this.systemNamespaceService = systemNamespaceService;
  }

  @Override
  public Optional<CatalogIdentity> getCatalogEntityOwner(CatalogEntityKey catalogEntityKey) {
    @Nullable NameSpaceContainer nameSpaceContainer;
    try {
      nameSpaceContainer =
          systemNamespaceService.getEntityByPath(catalogEntityKey.toNamespaceKey());
    } catch (NamespaceException e) {
      return Optional.empty();
    }
    if (null == nameSpaceContainer) {
      return Optional.empty();
    }
    switch (nameSpaceContainer.getType()) {
      case DATASET:
        {
          final DatasetConfig dataset = nameSpaceContainer.getDataset();
          if (dataset.getType() == DatasetType.VIRTUAL_DATASET) {
            return Optional.empty();
          } else {
            return Optional.of(new CatalogUser(dataset.getOwner()));
          }
        }
      case FUNCTION:
        {
          return Optional.empty();
        }
      default:
        throw new RuntimeException("Unexpected type for getOwner " + nameSpaceContainer.getType());
    }
  }
}
