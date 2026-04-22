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

import com.dremio.catalog.exception.CatalogEntityAlreadyExistsException;
import com.dremio.catalog.exception.CatalogEntityForbiddenException;
import com.dremio.catalog.exception.CatalogEntityNotFoundException;
import com.dremio.catalog.exception.CatalogFolderNotEmptyException;
import com.dremio.catalog.exception.InvalidStorageUriException;
import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.catalog.model.CatalogFolder;
import com.dremio.common.exceptions.UserException;
import com.dremio.exec.store.NoDefaultBranchException;
import com.dremio.exec.store.ReferenceNotFoundException;
import com.dremio.exec.store.ReferenceTypeConflictException;
import java.util.Optional;
import javax.annotation.Nullable;

/** An interface for plugins that implement CRUD on folders. */
public interface SupportsMutatingFolders {
  /**
   * Create a folder by the given path & version
   *
   * @param key The key that is used to create a folder in the source.
   * @param storageUri The storage URI for the folder.
   * @return The created folder.
   * @throws CatalogEntityAlreadyExistsException If the folder already exists.
   * @throws CatalogEntityForbiddenException If the storage uri at folder conflicts.
   * @throws com.dremio.exec.store.NamespaceAlreadyExistsException If the folder already exists.
   * @throws ReferenceNotFoundException If the given reference cannot be found.
   * @throws NoDefaultBranchException If the source does not have a default branch set.
   * @throws ReferenceTypeConflictException If the requested version does not match the server.
   */
  Optional<CatalogFolder> createFolder(CatalogEntityKey key, @Nullable String storageUri)
      throws CatalogEntityAlreadyExistsException, CatalogEntityForbiddenException;

  /**
   * Update a namespace by the given path for the given version
   *
   * @param key The key that is used to create a folder in the source.
   * @param storageUri The storage URI for the folder.
   * @return The updated folder.
   * @throws ReferenceNotFoundException If the given reference cannot be found.
   * @throws com.dremio.exec.store.NamespaceNotEmptyException If the folder cannot be found.
   * @throws CatalogEntityNotFoundException If the folder cannot be found.
   * @throws UserException If the requested folder to be updated is not empty.
   * @throws CatalogEntityForbiddenException If the storage uri at folder conflicts.
   */
  Optional<CatalogFolder> updateFolder(CatalogEntityKey key, @Nullable String storageUri)
      throws CatalogEntityNotFoundException,
          InvalidStorageUriException,
          CatalogEntityForbiddenException;

  /**
   * Deletes an empty folder by the given path for the given version
   *
   * @param key The key that is used to create a folder in the source.
   * @throws ReferenceNotFoundException If the given reference cannot be found.
   * @throws CatalogEntityNotFoundException If the folder cannot be found.
   * @throws CatalogFolderNotEmptyException If the requested folder to be deleted is not empty.
   * @throws com.dremio.exec.store.NamespaceNotFoundException If the folder cannot be found.
   * @throws com.dremio.exec.store.NamespaceNotEmptyException If the requested folder to be deleted
   *     is not empty.
   * @throws UserException If the requested folder to be deleted is not empty.
   */
  void deleteFolder(CatalogEntityKey key)
      throws ReferenceNotFoundException,
          UserException,
          CatalogFolderNotEmptyException,
          CatalogEntityNotFoundException;
}
