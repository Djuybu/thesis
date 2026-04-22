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
package com.dremio.exec.catalog.dataplane;

import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.DATAPLANE_PLUGIN_NAME;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.DEFAULT_BRANCH_NAME;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.createEmptyTableQuery;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.createTagQuery;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.createViewQuery;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.generateUniqueFolderName;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.generateUniqueTableName;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.generateUniqueTagName;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.generateUniqueViewName;
import static com.dremio.exec.catalog.dataplane.test.TestDataplaneAssertions.assertNessieDoesNotHaveEntity;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.catalog.model.VersionContext;
import com.dremio.catalog.model.dataset.TableVersionContext;
import com.dremio.common.exceptions.UserException;
import com.dremio.exec.catalog.dataplane.test.ITDataplanePluginTestSetup;
import com.dremio.exec.store.NamespaceNotEmptyException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.projectnessie.model.ContentKey;

public class ITDataplanePluginDeleteFolder extends ITDataplanePluginTestSetup {

  @Test
  public void deleteEmptyFolder() throws Exception {
    final String rootFolder = generateUniqueFolderName();
    final List<String> folderPath = Arrays.asList(DATAPLANE_PLUGIN_NAME, rootFolder);
    final VersionContext version = VersionContext.ofBranch(DEFAULT_BRANCH_NAME);
    final CatalogEntityKey catalogEntityKey =
        CatalogEntityKey.newBuilder()
            .keyComponents((folderPath))
            .tableVersionContext(TableVersionContext.of(version))
            .build();

    getDataplanePlugin().createFolder(catalogEntityKey, null);
    getDataplanePlugin().deleteFolder(catalogEntityKey);

    // Assert
    assertNessieDoesNotHaveEntity(Collections.singletonList(rootFolder), DEFAULT_BRANCH_NAME, this);
  }

  @Test
  public void deleteNonEmptyFolderWithTableThenThrowError() throws Exception {
    final String rootFolder = generateUniqueFolderName();
    final String tableName = generateUniqueTableName();
    final List<String> tablePath = Arrays.asList(rootFolder, tableName);
    final List<String> folderPath = Arrays.asList(DATAPLANE_PLUGIN_NAME, rootFolder);
    final VersionContext version = VersionContext.ofBranch(DEFAULT_BRANCH_NAME);
    final CatalogEntityKey folderKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(folderPath)
            .tableVersionContext(TableVersionContext.of(version))
            .build();
    ContentKey contentKey = ContentKey.of(rootFolder);
    String folderName = contentKey.toPathString();

    getDataplanePlugin().createFolder(folderKey, null);
    runSQL(createEmptyTableQuery(tablePath));

    // Assert
    assertThatThrownBy(() -> getDataplanePlugin().deleteFolder(folderKey))
        .isInstanceOf(NamespaceNotEmptyException.class)
        .hasMessageContaining("Folder '%s' is not empty", folderName);
  }

  @Test
  public void deleteNonEmptyFolderWithViewThenThrowError() throws Exception {
    final String rootFolder = generateUniqueFolderName();
    final String tableName = generateUniqueTableName();
    final List<String> tablePath = Arrays.asList(rootFolder, tableName);
    final List<String> folderPath = Arrays.asList(DATAPLANE_PLUGIN_NAME, rootFolder);
    final VersionContext version = VersionContext.ofBranch(DEFAULT_BRANCH_NAME);
    final CatalogEntityKey catalogEntityKey =
        CatalogEntityKey.newBuilder()
            .keyComponents((folderPath))
            .tableVersionContext(TableVersionContext.of(version))
            .build();
    ContentKey contentKey = ContentKey.of(rootFolder);
    String folderName = contentKey.toPathString();

    getDataplanePlugin().createFolder(catalogEntityKey, null);
    runSQL(createEmptyTableQuery(tablePath));

    final String viewName = generateUniqueViewName();
    List<String> viewKey = Arrays.asList(rootFolder, viewName);
    runSQL(createViewQuery(viewKey, tablePath));

    // Assert
    assertThatThrownBy(() -> getDataplanePlugin().deleteFolder(catalogEntityKey))
        .isInstanceOf(NamespaceNotEmptyException.class)
        .hasMessageContaining("Folder '%s' is not empty", folderName);
  }

  @Test
  public void deleteNonEmptyFolderWithSubFolderThenThrowError() throws Exception {
    final String rootFolder = generateUniqueFolderName();
    final List<String> rootFolderPath = Arrays.asList(DATAPLANE_PLUGIN_NAME, rootFolder);
    final String leafFolder = generateUniqueFolderName();
    final List<String> leafFolderPath =
        Arrays.asList(DATAPLANE_PLUGIN_NAME, rootFolder, leafFolder);
    final VersionContext version = VersionContext.ofBranch(DEFAULT_BRANCH_NAME);
    final CatalogEntityKey rootFolderKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(rootFolderPath)
            .tableVersionContext(TableVersionContext.of(version))
            .build();
    final CatalogEntityKey leafFolderKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(leafFolderPath)
            .tableVersionContext(TableVersionContext.of(version))
            .build();
    ContentKey contentKey = ContentKey.of(rootFolder);
    String folderName = contentKey.toPathString();

    getDataplanePlugin().createFolder(rootFolderKey, null);
    getDataplanePlugin().createFolder(leafFolderKey, null);

    // Assert
    assertThatThrownBy(() -> getDataplanePlugin().deleteFolder(rootFolderKey))
        .isInstanceOf(NamespaceNotEmptyException.class)
        .hasMessageContaining("Folder '%s' is not empty", folderName);
  }

  @Test
  public void deleteParentAndChildFolders() throws Exception {
    final String rootFolder = generateUniqueFolderName();
    final List<String> rootFolderPath = Arrays.asList(DATAPLANE_PLUGIN_NAME, rootFolder);
    final String leafFolder = generateUniqueFolderName();
    final List<String> leafFolderPath =
        Arrays.asList(DATAPLANE_PLUGIN_NAME, rootFolder, leafFolder);
    final VersionContext version = VersionContext.ofBranch(DEFAULT_BRANCH_NAME);
    final CatalogEntityKey rootFolderKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(rootFolderPath)
            .tableVersionContext(TableVersionContext.of(version))
            .build();
    final CatalogEntityKey leafFolderKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(leafFolderPath)
            .tableVersionContext(TableVersionContext.of(version))
            .build();

    getDataplanePlugin().createFolder(rootFolderKey, null);
    getDataplanePlugin().createFolder(leafFolderKey, null);

    // Delete child folder and then parent folder
    getDataplanePlugin().deleteFolder(leafFolderKey);
    getDataplanePlugin().deleteFolder(rootFolderKey);

    // Assert
    assertNessieDoesNotHaveEntity(Collections.singletonList(rootFolder), DEFAULT_BRANCH_NAME, this);
  }

  @Test
  public void deleteFolderWithTagContext() throws Exception {
    final String rootFolder = generateUniqueFolderName();
    final String someTag = generateUniqueTagName();
    // Setup
    final List<String> folderPath = Arrays.asList(DATAPLANE_PLUGIN_NAME, rootFolder);

    runSQL(createTagQuery(someTag, DEFAULT_BRANCH_NAME));
    VersionContext tagContext = VersionContext.ofTag(someTag);
    final CatalogEntityKey folderKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(folderPath)
            .tableVersionContext(
                TableVersionContext.of(VersionContext.ofBranch(DEFAULT_BRANCH_NAME)))
            .build();
    final CatalogEntityKey folderKeyWithTag =
        CatalogEntityKey.newBuilder()
            .keyComponents(folderPath)
            .tableVersionContext(TableVersionContext.of(tagContext))
            .build();
    getDataplanePlugin().createFolder(folderKey, null);

    // Act + Assert
    assertThatThrownBy(() -> getDataplanePlugin().deleteFolder(folderKeyWithTag))
        .isInstanceOf(UserException.class)
        .hasMessageContaining(
            "Delete folder is only supported for branches - not on tags or commits");
  }

  @Test
  public void deleteFolderWithCommitContext() throws Exception {
    final String rootFolder = generateUniqueFolderName();
    final String someCommitHash =
        "c7a79c74adf76649e643354c34ed69abfee5a3b070ef68cbe782a072b0a418ba";
    // Setup
    final List<String> folderPath = Arrays.asList(DATAPLANE_PLUGIN_NAME, rootFolder);

    VersionContext commitContext = VersionContext.ofCommit(someCommitHash);
    final CatalogEntityKey folderKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(folderPath)
            .tableVersionContext(
                TableVersionContext.of(VersionContext.ofBranch(DEFAULT_BRANCH_NAME)))
            .build();
    final CatalogEntityKey folderKeyWithCommitHash =
        CatalogEntityKey.newBuilder()
            .keyComponents((folderPath))
            .tableVersionContext(TableVersionContext.of(commitContext))
            .build();
    getDataplanePlugin().createFolder(folderKey, null);

    // Act + Assert
    assertThatThrownBy(() -> getDataplanePlugin().deleteFolder(folderKeyWithCommitHash))
        .isInstanceOf(UserException.class)
        .hasMessageContaining(
            "Delete folder is only supported for branches - not on tags or commits");
  }
}
