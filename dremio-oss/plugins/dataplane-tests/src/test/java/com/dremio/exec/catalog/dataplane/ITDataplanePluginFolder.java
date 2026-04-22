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

import static com.dremio.exec.catalog.dataplane.test.ContainerEntity.tableAFirst;
import static com.dremio.exec.catalog.dataplane.test.ContainerEntity.tableBSecond;
import static com.dremio.exec.catalog.dataplane.test.ContainerEntity.viewCThird;
import static com.dremio.exec.catalog.dataplane.test.ContainerEntity.viewDFourth;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.DATAPLANE_PLUGIN_NAME;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.DEFAULT_BRANCH_NAME;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.convertFolderNameToList;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.createBranchAtBranchQuery;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.createEmptyTableQuery;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.createFolderAtQuery;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.createFolderQuery;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.createTagQuery;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.createViewQuery;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.folderA;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.folderB;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.generateNestedFolderPath;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.generateUniqueFolderName;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.generateUniqueTableName;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.generateUniqueTagName;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.tableA;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.useBranchQuery;
import static com.dremio.exec.catalog.dataplane.test.TestDataplaneAssertions.assertLastCommitMadeBySpecifiedAuthor;
import static com.dremio.exec.catalog.dataplane.test.TestDataplaneAssertions.assertNessieDoesNotHaveNamespace;
import static com.dremio.exec.catalog.dataplane.test.TestDataplaneAssertions.assertNessieHasNamespace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dremio.BaseTestQuery;
import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.catalog.model.VersionContext;
import com.dremio.catalog.model.dataset.TableVersionContext;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.exceptions.UserRemoteException;
import com.dremio.exec.catalog.VersionedPlugin;
import com.dremio.exec.catalog.dataplane.test.ContainerEntity;
import com.dremio.exec.catalog.dataplane.test.DataplaneStorage;
import com.dremio.exec.catalog.dataplane.test.ITDataplanePluginTestSetup;
import com.dremio.plugins.ExternalNamespaceEntry;
import com.dremio.service.namespace.space.proto.FolderConfig;
import com.google.common.collect.Streams;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.projectnessie.model.Namespace;

/**
 *
 *
 * <pre>
 * Test Dataplane listEntries and listEntriesWithNested. In Nessie API V2, there are no implicit namespaces.
 *
 * dataplane_test
 *  - tableAFirst
 *  - tableBSecond
 *  - viewCThird
 *  - viewDFourth
 *  - explicitFolder1
 *     - tableA
 *     - tableBSecond
 *     - viewCThird
 *     - viewDFourth
 *     - explicitFolderInExplicitParent3
 *        - tableAFirst
 *        - tableBSecond
 *        - viewCThird
 *        - viewDFourth
 *  - emptyExplicitFolder7
 *  - maxDepthExplicitFolder8
 *    - maxDepthExplicitFolder9
 *      - maxDepthExplicitFolder10
 *        - maxDepthExplicitFolder11
 *          - maxDepthExplicitFolder12
 *            - maxDepthExplicitFolder13
 *              - maxDepthExplicitFolder14
 *                - maxDepthExplicitFolder15
 *                  - maxDepthExplicitFolder16
 *                    - maxDepthExplicitFolder17
 *                      - maxDepthExplicitFolder18
 *                        - maxDepthExplicitFolder19
 *                          - maxDepthExplicitFolder20
 *                            - maxDepthExplicitFolder21
 *                              - maxDepthExplicitFolder22
 *                                - maxDepthExplicitFolder23
 *                                  - maxDepthExplicitFolder24
 *                                    - maxDepthExplicitFolder25
 *                                      - maxDepthExplicitFolder26
 *                                        - tableWithFortySixCharacterssssssssssssssssssss
 *  - folderA
 *    - folderB
 *      - tableA
 *  - folderB
 * </pre>
 */
public class ITDataplanePluginFolder extends ITDataplanePluginTestSetup {

  private static final VersionContext DEFAULT_VERSION_CONTEXT =
      VersionContext.ofBranch(DEFAULT_BRANCH_NAME);

  private static final String longTableNameForMaxDepthTest =
      "tableWithFortySixCharacterssssssssssssssssssss";

  private static final ContainerEntity sourceRoot =
      new ContainerEntity(
          DATAPLANE_PLUGIN_NAME,
          ContainerEntity.Type.SOURCE,
          ContainerEntity.Contains.FOLDERS_AND_VIEWS,
          Collections.emptyList());
  private static final ContainerEntity explicitFolder1 =
      new ContainerEntity(
          "explicitFolder1",
          ContainerEntity.Type.EXPLICIT_FOLDER,
          ContainerEntity.Contains.FOLDERS_AND_VIEWS,
          sourceRoot.getFullPath());
  private static final ContainerEntity explicitFolderInExplicitParent3 =
      new ContainerEntity(
          "explicitFolderInExplicitParent3",
          ContainerEntity.Type.EXPLICIT_FOLDER,
          ContainerEntity.Contains.FOLDERS_AND_VIEWS,
          explicitFolder1.getFullPath());
  private static final ContainerEntity emptyExplicitFolder7 =
      new ContainerEntity(
          "emptyExplicitFolder7",
          ContainerEntity.Type.EXPLICIT_FOLDER,
          ContainerEntity.Contains.EMPTY,
          sourceRoot.getFullPath());

  private static final ContainerEntity maxDepthExplicitFolder8 =
      new ContainerEntity(
          "maxDepthExplicitFolder8", // 23
          ContainerEntity.Type.EXPLICIT_FOLDER,
          ContainerEntity.Contains.EMPTY,
          sourceRoot.getFullPath());

  private static final ContainerEntity maxDepthExplicitFolder9 =
      new ContainerEntity(
          "maxDepthExplicitFolder9", // 23
          ContainerEntity.Type.EXPLICIT_FOLDER,
          ContainerEntity.Contains.EMPTY,
          maxDepthExplicitFolder8.getFullPath());

  private static final ContainerEntity maxDepthExplicitFolder10 =
      new ContainerEntity(
          "maxDepthExplicitFolder10", // 24
          ContainerEntity.Type.EXPLICIT_FOLDER,
          ContainerEntity.Contains.EMPTY,
          maxDepthExplicitFolder9.getFullPath());

  private static final ContainerEntity maxDepthExplicitFolder11 =
      new ContainerEntity(
          "maxDepthExplicitFolder11",
          ContainerEntity.Type.EXPLICIT_FOLDER,
          ContainerEntity.Contains.EMPTY,
          maxDepthExplicitFolder10.getFullPath());

  private static final ContainerEntity maxDepthExplicitFolder12 =
      new ContainerEntity(
          "maxDepthExplicitFolder12",
          ContainerEntity.Type.EXPLICIT_FOLDER,
          ContainerEntity.Contains.EMPTY,
          maxDepthExplicitFolder11.getFullPath());

  private static final ContainerEntity maxDepthExplicitFolder13 =
      new ContainerEntity(
          "maxDepthExplicitFolder13",
          ContainerEntity.Type.EXPLICIT_FOLDER,
          ContainerEntity.Contains.EMPTY,
          maxDepthExplicitFolder12.getFullPath());

  private static final ContainerEntity maxDepthExplicitFolder14 =
      new ContainerEntity(
          "maxDepthExplicitFolder14",
          ContainerEntity.Type.EXPLICIT_FOLDER,
          ContainerEntity.Contains.EMPTY,
          maxDepthExplicitFolder13.getFullPath());

  private static final ContainerEntity maxDepthExplicitFolder15 =
      new ContainerEntity(
          "maxDepthExplicitFolder15",
          ContainerEntity.Type.EXPLICIT_FOLDER,
          ContainerEntity.Contains.EMPTY,
          maxDepthExplicitFolder14.getFullPath());

  private static final ContainerEntity maxDepthExplicitFolder16 =
      new ContainerEntity(
          "maxDepthExplicitFolder16",
          ContainerEntity.Type.EXPLICIT_FOLDER,
          ContainerEntity.Contains.EMPTY,
          maxDepthExplicitFolder15.getFullPath());

  private static final ContainerEntity maxDepthExplicitFolder17 =
      new ContainerEntity(
          "maxDepthExplicitFolder17",
          ContainerEntity.Type.EXPLICIT_FOLDER,
          ContainerEntity.Contains.EMPTY,
          maxDepthExplicitFolder16.getFullPath());

  private static final ContainerEntity maxDepthExplicitFolder18 =
      new ContainerEntity(
          "maxDepthExplicitFolder18",
          ContainerEntity.Type.EXPLICIT_FOLDER,
          ContainerEntity.Contains.EMPTY,
          maxDepthExplicitFolder17.getFullPath());

  private static final ContainerEntity maxDepthExplicitFolder19 =
      new ContainerEntity(
          "maxDepthExplicitFolder19",
          ContainerEntity.Type.EXPLICIT_FOLDER,
          ContainerEntity.Contains.EMPTY,
          maxDepthExplicitFolder18.getFullPath());

  private static final ContainerEntity maxDepthExplicitFolder20 =
      new ContainerEntity(
          "maxDepthExplicitFolder20",
          ContainerEntity.Type.EXPLICIT_FOLDER,
          ContainerEntity.Contains.EMPTY,
          maxDepthExplicitFolder19.getFullPath());

  private static final ContainerEntity maxDepthExplicitFolder21 =
      new ContainerEntity(
          "maxDepthExplicitFolder21",
          ContainerEntity.Type.EXPLICIT_FOLDER,
          ContainerEntity.Contains.EMPTY,
          maxDepthExplicitFolder20.getFullPath());

  private static final ContainerEntity maxDepthExplicitFolder22 =
      new ContainerEntity(
          "maxDepthExplicitFolder22",
          ContainerEntity.Type.EXPLICIT_FOLDER,
          ContainerEntity.Contains.EMPTY,
          maxDepthExplicitFolder21.getFullPath());

  private static final ContainerEntity maxDepthExplicitFolder23 =
      new ContainerEntity(
          "maxDepthExplicitFolder23",
          ContainerEntity.Type.EXPLICIT_FOLDER,
          ContainerEntity.Contains.EMPTY,
          maxDepthExplicitFolder22.getFullPath());

  private static final ContainerEntity maxDepthExplicitFolder24 =
      new ContainerEntity(
          "maxDepthExplicitFolder24",
          ContainerEntity.Type.EXPLICIT_FOLDER,
          ContainerEntity.Contains.EMPTY,
          maxDepthExplicitFolder23.getFullPath());

  private static final ContainerEntity maxDepthExplicitFolder25 =
      new ContainerEntity(
          "maxDepthExplicitFolder25",
          ContainerEntity.Type.EXPLICIT_FOLDER,
          ContainerEntity.Contains.EMPTY,
          maxDepthExplicitFolder24.getFullPath());

  private static final ContainerEntity maxDepthExplicitFolder26 =
      new ContainerEntity(
          "maxDepthExplicitFolder26",
          ContainerEntity.Type.EXPLICIT_FOLDER,
          ContainerEntity.Contains.MAX_KEY_TABLE,
          maxDepthExplicitFolder25.getFullPath());
  private static final ContainerEntity explicitFolderA =
      new ContainerEntity(
          "folderA",
          ContainerEntity.Type.EXPLICIT_FOLDER,
          ContainerEntity.Contains.FOLDERS_ONLY,
          sourceRoot.getFullPath());

  private static final ContainerEntity explicitFolderB =
      new ContainerEntity(
          "folderB",
          ContainerEntity.Type.EXPLICIT_FOLDER,
          ContainerEntity.Contains.TABLES_ONLY,
          explicitFolderA.getFullPath());

  private static final ContainerEntity explicitFolderBUnderRoot =
      new ContainerEntity(
          "folderB",
          ContainerEntity.Type.EXPLICIT_FOLDER,
          ContainerEntity.Contains.EMPTY,
          sourceRoot.getFullPath());

  private void createEntitiesForContainer(ContainerEntity container) throws Exception {
    switch (container.getType()) {
      case SOURCE:
        // Intentional fallthrough
        break;
      case IMPLICIT_FOLDER:
        break;
      case EXPLICIT_FOLDER:
        getNessieApi()
            .createNamespace()
            .namespace(Namespace.of(container.getPathWithoutRoot()))
            .refName(DEFAULT_BRANCH_NAME)
            .create();
        break;
      default:
        throw new IllegalStateException("Unexpected value: " + container.getType());
    }

    switch (container.getContains()) {
      case FOLDERS_AND_VIEWS:
        createTablesAndViewsInContainer(container);
        break;
      case MAX_KEY_TABLE:
        createTablesAndViewsInContainerForMaxDepthTestCases(container);
        break;
      case TABLES_ONLY:
        createSingleTable(container, tableA);
        break;
      case EMPTY:
      // Intentional fallthrough
      case FOLDERS_ONLY:
        // we are manually creating folder. other than creating here. so we just break.
        break;
      default:
        throw new IllegalStateException("Unexpected value: " + container.getContains());
    }
  }

  private static void createTablesAndViewsInContainer(ContainerEntity container) throws Exception {
    runSQL(createEmptyTableQuery(container.getChildPathWithoutRoot("tableAFirst")));
    runSQL(createEmptyTableQuery(container.getChildPathWithoutRoot("tableBSecond")));
    runSQL(
        createViewQuery(
            container.getChildPathWithoutRoot("viewCThird"),
            container.getChildPathWithoutRoot("tableAFirst")));
    runSQL(
        createViewQuery(
            container.getChildPathWithoutRoot("viewDFourth"),
            container.getChildPathWithoutRoot("tableBSecond")));
  }

  private static void createTablesAndViewsInContainerForMaxDepthTestCases(ContainerEntity container)
      throws Exception {
    // the key length before the last part is 454 so create tables that has length of 46 to hit the
    // max key length
    runSQL(createEmptyTableQuery(container.getChildPathWithoutRoot(longTableNameForMaxDepthTest)));
  }

  private static void createSingleTable(ContainerEntity container, String tableName)
      throws Exception {
    runSQL(createEmptyTableQuery(container.getChildPathWithoutRoot(tableName)));
  }

  @BeforeEach
  public void createFoldersTablesViews() throws Exception {
    createEntitiesForContainer(sourceRoot);
    createEntitiesForContainer(explicitFolder1);
    createEntitiesForContainer(explicitFolderInExplicitParent3);
    createEntitiesForContainer(emptyExplicitFolder7);
    createEntitiesForContainer(maxDepthExplicitFolder8);
    createEntitiesForContainer(maxDepthExplicitFolder9);
    createEntitiesForContainer(maxDepthExplicitFolder10);
    createEntitiesForContainer(maxDepthExplicitFolder11);
    createEntitiesForContainer(maxDepthExplicitFolder12);
    createEntitiesForContainer(maxDepthExplicitFolder13);
    createEntitiesForContainer(maxDepthExplicitFolder14);
    createEntitiesForContainer(maxDepthExplicitFolder15);
    createEntitiesForContainer(maxDepthExplicitFolder16);
    createEntitiesForContainer(maxDepthExplicitFolder17);
    createEntitiesForContainer(maxDepthExplicitFolder18);
    createEntitiesForContainer(maxDepthExplicitFolder19);
    createEntitiesForContainer(maxDepthExplicitFolder20);
    createEntitiesForContainer(maxDepthExplicitFolder21);
    createEntitiesForContainer(maxDepthExplicitFolder22);
    createEntitiesForContainer(maxDepthExplicitFolder23);
    createEntitiesForContainer(maxDepthExplicitFolder24);
    createEntitiesForContainer(maxDepthExplicitFolder25);
    createEntitiesForContainer(maxDepthExplicitFolder26);
    createEntitiesForContainer(explicitFolderA);
    createEntitiesForContainer(explicitFolderB);
    createEntitiesForContainer(explicitFolderBUnderRoot);
  }

  private Stream<List<String>> listEntries(ContainerEntity container) {
    return getDataplanePlugin()
        .listEntries(
            container.getPathWithoutRoot(),
            getDataplanePlugin().resolveVersionContext(DEFAULT_VERSION_CONTEXT),
            VersionedPlugin.NestingMode.IMMEDIATE_CHILDREN_ONLY,
            VersionedPlugin.ContentMode.ENTRY_METADATA_ONLY)
        .map(ExternalNamespaceEntry::getNameElements);
  }

  private Stream<List<String>> listEntriesIncludeNested(ContainerEntity container) {
    return getDataplanePlugin()
        .listEntries(
            container.getPathWithoutRoot(),
            getDataplanePlugin().resolveVersionContext(DEFAULT_VERSION_CONTEXT),
            VersionedPlugin.NestingMode.INCLUDE_NESTED_CHILDREN,
            VersionedPlugin.ContentMode.ENTRY_METADATA_ONLY)
        .map(ExternalNamespaceEntry::getNameElements);
  }

  @Test
  public void getFolder() {
    Optional<FolderConfig> optionalFolderConfig =
        getDataplanePlugin()
            .getFolder(
                CatalogEntityKey.newBuilder()
                    .keyComponents(explicitFolder1.getFullPath())
                    .tableVersionContext(TableVersionContext.of(DEFAULT_VERSION_CONTEXT))
                    .build());
    assertThat(optionalFolderConfig).isPresent();
    assertThat(optionalFolderConfig.get().getName()).isEqualTo(explicitFolder1.getName());
    assertThat(optionalFolderConfig.get().getFullPathList())
        .isEqualTo(explicitFolder1.getFullPath());
  }

  @Test
  public void listEntriesForExplicitFolder() {
    assertThat(listEntries(explicitFolder1))
        .containsExactlyInAnyOrderElementsOf(
            Streams.concat(
                    getFullPath(explicitFolderInExplicitParent3),
                    getFullPath(explicitFolder1, tableAFirst),
                    getFullPath(explicitFolder1, tableBSecond),
                    getFullPath(explicitFolder1, viewCThird),
                    getFullPath(explicitFolder1, viewDFourth))
                .collect(Collectors.toList()));
  }

  @Test
  public void listNestedEntriesForExplicitFolder() {
    assertThat(listEntriesIncludeNested(explicitFolder1))
        .containsExactlyInAnyOrderElementsOf(
            Streams.concat(
                    getFullPath(explicitFolderInExplicitParent3),
                    getFullPath(explicitFolder1, tableAFirst),
                    getFullPath(explicitFolder1, tableBSecond),
                    getFullPath(explicitFolder1, viewCThird),
                    getFullPath(explicitFolder1, viewDFourth),
                    getFullPath(explicitFolderInExplicitParent3, tableAFirst),
                    getFullPath(explicitFolderInExplicitParent3, tableBSecond),
                    getFullPath(explicitFolderInExplicitParent3, viewCThird),
                    getFullPath(explicitFolderInExplicitParent3, viewDFourth))
                .collect(Collectors.toList()));
  }

  @Test
  public void listEntriesInRoot() {
    assertThat(listEntries(sourceRoot))
        .containsExactlyInAnyOrderElementsOf(
            Streams.concat(
                    getFullPath(explicitFolder1),
                    getFullPath(emptyExplicitFolder7),
                    getFullPath(maxDepthExplicitFolder8),
                    getFullPath(sourceRoot, tableAFirst),
                    getFullPath(sourceRoot, tableBSecond),
                    getFullPath(sourceRoot, viewCThird),
                    getFullPath(sourceRoot, viewDFourth),
                    getFullPath(sourceRoot, folderA),
                    getFullPath(sourceRoot, folderB))
                .collect(Collectors.toList()));
  }

  @Test
  public void listNestedEntriesInRoot() {
    assertThat(listEntriesIncludeNested(sourceRoot))
        .containsExactlyInAnyOrderElementsOf(
            Streams.concat(
                    getFullPath(explicitFolder1),
                    getFullPath(explicitFolderInExplicitParent3),
                    getFullPath(emptyExplicitFolder7),
                    getFullPath(maxDepthExplicitFolder8),
                    getFullPath(maxDepthExplicitFolder9),
                    getFullPath(maxDepthExplicitFolder10),
                    getFullPath(maxDepthExplicitFolder11),
                    getFullPath(maxDepthExplicitFolder12),
                    getFullPath(maxDepthExplicitFolder13),
                    getFullPath(maxDepthExplicitFolder14),
                    getFullPath(maxDepthExplicitFolder15),
                    getFullPath(maxDepthExplicitFolder16),
                    getFullPath(maxDepthExplicitFolder17),
                    getFullPath(maxDepthExplicitFolder18),
                    getFullPath(maxDepthExplicitFolder19),
                    getFullPath(maxDepthExplicitFolder20),
                    getFullPath(maxDepthExplicitFolder21),
                    getFullPath(maxDepthExplicitFolder22),
                    getFullPath(maxDepthExplicitFolder23),
                    getFullPath(maxDepthExplicitFolder24),
                    getFullPath(maxDepthExplicitFolder25),
                    getFullPath(maxDepthExplicitFolder26),
                    getFullPath(sourceRoot, tableAFirst),
                    getFullPath(sourceRoot, tableBSecond),
                    getFullPath(sourceRoot, viewCThird),
                    getFullPath(sourceRoot, viewDFourth),
                    getFullPath(explicitFolder1, tableAFirst),
                    getFullPath(explicitFolder1, tableBSecond),
                    getFullPath(explicitFolder1, viewCThird),
                    getFullPath(explicitFolder1, viewDFourth),
                    getFullPath(explicitFolderInExplicitParent3, tableAFirst),
                    getFullPath(explicitFolderInExplicitParent3, tableBSecond),
                    getFullPath(explicitFolderInExplicitParent3, viewCThird),
                    getFullPath(explicitFolderInExplicitParent3, viewDFourth),
                    getFullPath(maxDepthExplicitFolder26, longTableNameForMaxDepthTest),
                    getFullPath(sourceRoot, folderA),
                    getFullPath(sourceRoot, folderB),
                    getFullPath(explicitFolderA, folderB),
                    getFullPath(explicitFolderB, tableA))
                .collect(Collectors.toList()));
  }

  @Test
  public void listEntriesInExplicitFolderInExplicitParent() {
    assertThat(listEntries(explicitFolderInExplicitParent3))
        .containsExactlyInAnyOrderElementsOf(
            Streams.concat(
                    getFullPath(explicitFolderInExplicitParent3, tableAFirst),
                    getFullPath(explicitFolderInExplicitParent3, tableBSecond),
                    getFullPath(explicitFolderInExplicitParent3, viewCThird),
                    getFullPath(explicitFolderInExplicitParent3, viewDFourth))
                .collect(Collectors.toList()));
  }

  @Test
  public void listEntriesEmptyExplicitFolders() {
    assertThat(listEntries(emptyExplicitFolder7)).isEmpty();
  }

  @Test
  public void testMaxDepthFolders() {
    assertThat(listEntriesIncludeNested(maxDepthExplicitFolder8))
        .containsExactlyInAnyOrderElementsOf(
            Streams.concat(
                    getFullPath(maxDepthExplicitFolder8, maxDepthExplicitFolder9.getName()),
                    getFullPath(maxDepthExplicitFolder9, maxDepthExplicitFolder10.getName()),
                    getFullPath(maxDepthExplicitFolder10, maxDepthExplicitFolder11.getName()),
                    getFullPath(maxDepthExplicitFolder11, maxDepthExplicitFolder12.getName()),
                    getFullPath(maxDepthExplicitFolder12, maxDepthExplicitFolder13.getName()),
                    getFullPath(maxDepthExplicitFolder13, maxDepthExplicitFolder14.getName()),
                    getFullPath(maxDepthExplicitFolder14, maxDepthExplicitFolder15.getName()),
                    getFullPath(maxDepthExplicitFolder15, maxDepthExplicitFolder16.getName()),
                    getFullPath(maxDepthExplicitFolder16, maxDepthExplicitFolder17.getName()),
                    getFullPath(maxDepthExplicitFolder17, maxDepthExplicitFolder18.getName()),
                    getFullPath(maxDepthExplicitFolder18, maxDepthExplicitFolder19.getName()),
                    getFullPath(maxDepthExplicitFolder19, maxDepthExplicitFolder20.getName()),
                    getFullPath(maxDepthExplicitFolder20, maxDepthExplicitFolder21.getName()),
                    getFullPath(maxDepthExplicitFolder21, maxDepthExplicitFolder22.getName()),
                    getFullPath(maxDepthExplicitFolder22, maxDepthExplicitFolder23.getName()),
                    getFullPath(maxDepthExplicitFolder23, maxDepthExplicitFolder24.getName()),
                    getFullPath(maxDepthExplicitFolder24, maxDepthExplicitFolder25.getName()),
                    getFullPath(maxDepthExplicitFolder25, maxDepthExplicitFolder26.getName()),
                    getFullPath(maxDepthExplicitFolder26, longTableNameForMaxDepthTest))
                .collect(Collectors.toList()));
  }

  @Test
  public void testFolderAccessHavingInvalidEntries() {
    // Assert that dataplane_test.folderA.folderB has entry that has name of tableA
    assertThat(listEntries(explicitFolderB))
        .containsExactlyInAnyOrderElementsOf(
            Streams.concat(getFullPath(explicitFolderB, tableA)).collect(Collectors.toList()));

    // Assert that dataplane_test.folderB.folderB does not have entry that has name of tableA
    List<String> incorrectFullPath = Arrays.asList(folderB, folderB);
    assertThat(
            getDataplanePlugin()
                .listEntries(
                    incorrectFullPath,
                    getDataplanePlugin().resolveVersionContext(DEFAULT_VERSION_CONTEXT),
                    VersionedPlugin.NestingMode.IMMEDIATE_CHILDREN_ONLY,
                    VersionedPlugin.ContentMode.ENTRY_METADATA_ONLY))
        .map(ExternalNamespaceEntry::getNameElements)
        .isEmpty();
  }

  @Test
  public void creatFolderWithTagContext() throws Exception {
    final String rootFolder = generateUniqueFolderName();
    final String someTag = generateUniqueTagName();
    // Setup
    final List<String> folderPath = Arrays.asList(DATAPLANE_PLUGIN_NAME, rootFolder);
    runSQL(createTagQuery(someTag, DEFAULT_BRANCH_NAME));
    CatalogEntityKey folderKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(folderPath)
            .tableVersionContext(TableVersionContext.of(VersionContext.ofTag(someTag)))
            .build();
    // Act + Assert
    assertThatThrownBy(() -> getDataplanePlugin().createFolder(folderKey, null))
        .isInstanceOf(UserException.class)
        .hasMessageContaining(
            "Create folder is only supported for branches - not on tags or commits");
  }

  @Test
  public void createFolderWithCommitContext() throws Exception {
    final String rootFolder = generateUniqueFolderName();
    final String someCommitHash =
        "c7a79c74adf76649e643354c34ed69abfee5a3b070ef68cbe782a072b0a418ba";
    // Setup
    final List<String> folderPath = Arrays.asList(DATAPLANE_PLUGIN_NAME, rootFolder);
    final CatalogEntityKey folderKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(folderPath)
            .tableVersionContext(TableVersionContext.of(VersionContext.ofCommit(someCommitHash)))
            .build();

    // Act + Assert
    assertThatThrownBy(() -> getDataplanePlugin().createFolder(folderKey, null))
        .isInstanceOf(UserException.class)
        .hasMessageContaining(
            "Create folder is only supported for branches - not on tags or commits");
  }

  @Test
  public void createFolder() throws Exception {
    // Arrange
    final String folderName = generateUniqueFolderName();
    final List<String> folderPath = Collections.singletonList(folderName);

    // Act
    runSQL(createFolderQuery(DATAPLANE_PLUGIN_NAME, folderPath));

    // Assert
    assertLastCommitMadeBySpecifiedAuthor(DEFAULT_BRANCH_NAME, this);
    assertNessieHasNamespace(folderPath, DEFAULT_BRANCH_NAME, this);
    assertCommitLogTail(String.format("CREATE FOLDER %s", folderName));
  }

  @Test
  public void createNestedFolder() throws Exception {
    // Arrange
    final String folderName1 = generateUniqueFolderName();
    final String folderName2 = generateUniqueFolderName();
    final List<String> folderPath = generateNestedFolderPath(folderName1, folderName2);
    runSQL(createFolderQuery(DATAPLANE_PLUGIN_NAME, folderPath));

    // Assert
    assertLastCommitMadeBySpecifiedAuthor(DEFAULT_BRANCH_NAME, this);
    assertNessieHasNamespace(folderPath, DEFAULT_BRANCH_NAME, this);
  }

  @Test
  public void testCreateFolderWithContext() throws Exception {
    // Arrange
    final String folderName = generateUniqueFolderName();
    final List<String> folderPath = Collections.singletonList(folderName);

    // Act
    runSQL(createFolderQuery(DATAPLANE_PLUGIN_NAME, folderPath));

    // Assert
    assertLastCommitMadeBySpecifiedAuthor(DEFAULT_BRANCH_NAME, this);
    assertNessieHasNamespace(folderPath, DEFAULT_BRANCH_NAME, this);

    final String folderName2 = generateUniqueFolderName();
    final List<String> folderPath2 = Collections.singletonList(folderName2);
    final String branch2 = "branch2";

    runSQL(createBranchAtBranchQuery(branch2, DEFAULT_BRANCH_NAME));
    // set current context to branch2
    runSQL(useBranchQuery(branch2));
    runSQL(createFolderQuery(DATAPLANE_PLUGIN_NAME, folderPath2));

    // Assert that when we do not have [AT] token, we use
    // context as a default.
    assertLastCommitMadeBySpecifiedAuthor(branch2, this);
    assertNessieHasNamespace(folderPath2, branch2, this);
    assertNessieDoesNotHaveNamespace(folderPath2, DEFAULT_BRANCH_NAME, this);

    final String folderName3 = generateUniqueFolderName();
    final List<String> folderPath3 = Collections.singletonList(folderName3);
    final String branch3 = "branch3";

    // create folder3 at branch3 with current context branch2
    runSQL(createBranchAtBranchQuery(branch3, DEFAULT_BRANCH_NAME));
    runSQL(
        createFolderAtQuery(DATAPLANE_PLUGIN_NAME, folderPath3, VersionContext.ofBranch(branch3)));

    // the version context specified in AT token should override the context.
    // Therefore we have folder in branch3 not in branch2 nor main.
    assertLastCommitMadeBySpecifiedAuthor(branch3, this);
    assertNessieHasNamespace(folderPath3, branch3, this);
    assertNessieDoesNotHaveNamespace(folderPath3, DEFAULT_BRANCH_NAME, this);
    assertNessieDoesNotHaveNamespace(folderPath3, branch2, this);
  }

  @Test
  public void testCreateFolderWithAtTag() throws Exception {
    String tagName = "myTag";
    final String folderName = generateUniqueFolderName();
    final List<String> folderPath = Collections.singletonList(folderName);
    runSQL(createTagQuery(tagName, DEFAULT_BRANCH_NAME));
    // expect error for TAG
    assertThatThrownBy(
            () ->
                runSQL(
                    createFolderAtQuery(
                        DATAPLANE_PLUGIN_NAME,
                        folderPath,
                        VersionContext.ofTag(DEFAULT_BRANCH_NAME))))
        .isInstanceOf(UserRemoteException.class);
  }

  @Test
  public void createFolderWithSingleElementWithContext() throws Exception {
    BaseTestQuery.test(String.format("USE %s", DATAPLANE_PLUGIN_NAME));
    // Arrange
    final String folderName = generateUniqueFolderName();
    final List<String> sqlFolderPath = convertFolderNameToList(folderName);
    // since sqlFolderPath only has the name of the folder, its namespaceKey should be
    // DATAPLANE_PLUGIN_NAME.folderName

    runSQL(createFolderQuery(DATAPLANE_PLUGIN_NAME, sqlFolderPath));

    // Act
    assertLastCommitMadeBySpecifiedAuthor(DEFAULT_BRANCH_NAME, this);
    assertNessieHasNamespace(Collections.singletonList(folderName), DEFAULT_BRANCH_NAME, this);
  }

  @Test
  public void createFolderUsingAt() throws Exception {
    // Arrange
    final String folderName = generateUniqueFolderName();
    final List<String> folderPath = Collections.singletonList(folderName);

    runSQL(
        createFolderAtQuery(
            DATAPLANE_PLUGIN_NAME, folderPath, VersionContext.ofBranch(DEFAULT_BRANCH_NAME)));

    // Act
    assertLastCommitMadeBySpecifiedAuthor(DEFAULT_BRANCH_NAME, this);
    assertNessieHasNamespace(folderPath, DEFAULT_BRANCH_NAME, this);
  }

  @Test
  public void testCreateFolderWithAtCommit() throws Exception {
    String commitHash = "c7a79c74adf76649e643354c34ed69abfee5a3b070ef68cbe782a072b0a418ba";
    final String folderName = generateUniqueFolderName();
    final List<String> folderPath = Collections.singletonList(folderName);
    // expect error for TAG
    assertThatThrownBy(
            () ->
                runSQL(
                    createFolderAtQuery(
                        DATAPLANE_PLUGIN_NAME, folderPath, VersionContext.ofCommit(commitHash))))
        .isInstanceOf(UserRemoteException.class);
  }

  @Test
  public void createFolderWithStorageUri() throws Exception {
    final String rootFolder = generateUniqueFolderName();
    final String storageUri =
        String.format(
            "%s://bucket1/folder1,", getScheme(DataplaneStorage.BucketSelection.ALTERNATE_BUCKET));
    // Setup
    final List<String> folderPath = Arrays.asList(DATAPLANE_PLUGIN_NAME, rootFolder);
    final CatalogEntityKey folderKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(folderPath)
            .tableVersionContext(
                TableVersionContext.of(VersionContext.ofBranch(DEFAULT_BRANCH_NAME)))
            .build();

    // Act
    assertThatThrownBy(() -> getDataplanePlugin().createFolder(folderKey, storageUri))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("Storage URI is not supported for folders");
  }

  @Test
  public void updateFolderWithStorageUri() throws Exception {
    // Arrange
    final String tableName = generateUniqueTableName();
    final String folderName = generateUniqueFolderName();
    final List<String> folderPath = Arrays.asList(DATAPLANE_PLUGIN_NAME, folderName);

    final String alternateStorageUri =
        String.format(
            "%s://bucket1/folder1,", getScheme(DataplaneStorage.BucketSelection.ALTERNATE_BUCKET));

    final CatalogEntityKey folderKey =
        CatalogEntityKey.newBuilder()
            .keyComponents(folderPath)
            .tableVersionContext(
                TableVersionContext.of(VersionContext.ofBranch(DEFAULT_BRANCH_NAME)))
            .build();

    // Act and Assert
    assertThatThrownBy(() -> getDataplanePlugin().updateFolder(folderKey, alternateStorageUri))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("Storage URI is not supported for folders");
  }

  public Stream<List<String>> getFullPath(ContainerEntity parent) {
    return Stream.of(parent.getPathWithoutRoot());
  }

  public Stream<List<String>> getFullPath(ContainerEntity parent, String nameElement) {
    if (parent.getType() == ContainerEntity.Type.SOURCE) {
      return Stream.of(Collections.singletonList(nameElement));
    }
    return Stream.of(
        Streams.concat(parent.getPathWithoutRoot().stream(), Stream.of(nameElement))
            .collect(Collectors.toList()));
  }

  private String getScheme(DataplaneStorage.BucketSelection bucketSelection)
      throws URISyntaxException {
    return new URI(getDataplaneStorage().getWarehousePath(bucketSelection)).getScheme();
  }
}
