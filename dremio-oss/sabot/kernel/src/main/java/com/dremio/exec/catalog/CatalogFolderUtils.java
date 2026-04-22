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

import com.dremio.catalog.model.CatalogFolder;
import com.dremio.catalog.model.ImmutableCatalogFolder;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.folder.FolderNamespaceService;
import com.dremio.service.namespace.proto.EntityId;
import com.dremio.service.namespace.space.proto.FolderConfig;
import org.jetbrains.annotations.NotNull;

public final class CatalogFolderUtils {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(CatalogFolderUtils.class);

  public static CatalogFolder convertToCatalog(FolderConfig folderConfig) {
    return (new ImmutableCatalogFolder.Builder())
        .setId(folderConfig.getId().getId())
        .setFullPath(folderConfig.getFullPathList())
        .setTag(folderConfig.getTag())
        .setStorageUri(folderConfig.getStorageUri())
        .build();
  }

  public static FolderConfig convertToNS(CatalogFolder catalogFolder) {
    FolderConfig folderConfig = new FolderConfig();
    if (catalogFolder.id() != null) {
      folderConfig.setId(new EntityId(catalogFolder.id()));
    }
    folderConfig.setFullPathList(catalogFolder.fullPath());
    folderConfig.setName(catalogFolder.fullPath().get(catalogFolder.fullPath().size() - 1));
    folderConfig.setTag(catalogFolder.tag());
    folderConfig.setStorageUri(catalogFolder.storageUri());
    return folderConfig;
  }

  public static FolderConfig convertToNS(
      CatalogFolder catalogFolder, EntityId entityId, String tag) {
    FolderConfig folderConfig = new FolderConfig();
    folderConfig.setId(entityId);
    folderConfig.setFullPathList(catalogFolder.fullPath());
    folderConfig.setName(catalogFolder.fullPath().get(catalogFolder.fullPath().size() - 1));
    folderConfig.setTag(tag);
    folderConfig.setStorageUri(catalogFolder.storageUri());
    return folderConfig;
  }

  static @NotNull FolderConfig getFolderConfigForNSUpdate(
      FolderNamespaceService systemNamespaceService,
      NamespaceKey folderKey,
      CatalogFolder folderFromPlugin) {
    try {
      FolderConfig existingFolderConfig = systemNamespaceService.getFolder(folderKey);
      if (existingFolderConfig != null) {
        return CatalogFolderUtils.convertToNS(
            folderFromPlugin, existingFolderConfig.getId(), existingFolderConfig.getTag());
      }
    } catch (NamespaceException e) {
      logger.debug("Folder '{}' not found in namespace, adding it to namespace", folderKey, e);
    }
    return CatalogFolderUtils.convertToNS(folderFromPlugin);
  }
}
