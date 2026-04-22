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

import com.dremio.catalog.exception.CatalogException;
import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.catalog.model.CatalogFolder;
import java.util.Optional;
import javax.annotation.Nullable;

public interface FolderCatalog {
  Optional<CatalogFolder> createFolder(CatalogFolder catalogFolder) throws CatalogException;

  Optional<CatalogFolder> updateFolder(CatalogFolder catalogFolder) throws CatalogException;

  void deleteFolder(CatalogEntityKey catalogEntityKey, @Nullable String version)
      throws CatalogException;

  /**
   * Get a folder by its key.
   *
   * @param catalogEntityKey the key of the folder to get
   * @return The folder if it exists or empty if it does not.
   */
  Optional<CatalogFolder> getFolder(CatalogEntityKey catalogEntityKey);
}
