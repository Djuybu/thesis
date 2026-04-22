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
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.createFolderQuery;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.createUdfQuery;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.createViewQuery;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.generateUniqueFolderName;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.generateUniqueFunctionName;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.generateUniqueTableName;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.generateUniqueViewName;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.useBranchQuery;
import static org.assertj.core.api.Assertions.assertThat;

import com.dremio.exec.catalog.dataplane.test.ITDataplanePluginTestSetup;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

public class ITDataplanePluginListEntriesChangedBetween extends ITDataplanePluginTestSetup {

  @Test
  public void testNoChange() {
    assertThat(
            getDataplanePlugin()
                .listEntriesChangedBetween(
                    getDataplanePlugin().getDefaultBranch().getCommitHash(),
                    getDataplanePlugin().getDefaultBranch().getCommitHash())
                .collect(Collectors.toList()))
        .isEmpty();
  }

  @Test
  public void testAddTableSameCommitHashes() throws Exception {
    runSQL(useBranchQuery(DEFAULT_BRANCH_NAME));
    List<String> tablePath = List.of(generateUniqueTableName());
    runSQL(createEmptyTableQuery(tablePath));

    assertThat(
            getDataplanePlugin()
                .listEntriesChangedBetween(
                    getDataplanePlugin().getDefaultBranch().getCommitHash(),
                    getDataplanePlugin().getDefaultBranch().getCommitHash())
                .collect(Collectors.toList()))
        .containsExactlyInAnyOrderElementsOf(List.of());
  }

  @Test
  public void testAddTable() throws Exception {
    String fromCommitHash = getDataplanePlugin().getDefaultBranch().getCommitHash();
    runSQL(useBranchQuery(DEFAULT_BRANCH_NAME));
    List<String> tablePath = List.of(generateUniqueTableName());
    runSQL(createEmptyTableQuery(tablePath));

    assertThat(
            getDataplanePlugin()
                .listEntriesChangedBetween(
                    fromCommitHash, getDataplanePlugin().getDefaultBranch().getCommitHash())
                .collect(Collectors.toList()))
        .containsExactlyInAnyOrderElementsOf(List.of(tablePath));
  }

  @Test
  public void testAddView() throws Exception {
    String fromCommitHash = getDataplanePlugin().getDefaultBranch().getCommitHash();
    runSQL(useBranchQuery(DEFAULT_BRANCH_NAME));
    List<String> udfPath = List.of(generateUniqueFunctionName());
    runSQL(createUdfQuery(udfPath));

    assertThat(
            getDataplanePlugin()
                .listEntriesChangedBetween(
                    fromCommitHash, getDataplanePlugin().getDefaultBranch().getCommitHash())
                .collect(Collectors.toList()))
        .containsExactlyInAnyOrderElementsOf(List.of(udfPath));
  }

  @Test
  public void testAddFolder() throws Exception {
    String fromCommitHash = getDataplanePlugin().getDefaultBranch().getCommitHash();
    runSQL(useBranchQuery(DEFAULT_BRANCH_NAME));
    List<String> folderPath = List.of(generateUniqueFolderName());
    runSQL(createFolderQuery(DATAPLANE_PLUGIN_NAME, folderPath));

    assertThat(
            getDataplanePlugin()
                .listEntriesChangedBetween(
                    fromCommitHash, getDataplanePlugin().getDefaultBranch().getCommitHash())
                .collect(Collectors.toList()))
        .containsExactlyInAnyOrderElementsOf(List.of(folderPath));
  }

  @Test
  public void testAddUdf() throws Exception {
    String fromCommitHash = getDataplanePlugin().getDefaultBranch().getCommitHash();
    runSQL(useBranchQuery(DEFAULT_BRANCH_NAME));
    List<String> udfPath = List.of(generateUniqueFunctionName());
    runSQL(createUdfQuery(udfPath));

    assertThat(
            getDataplanePlugin()
                .listEntriesChangedBetween(
                    fromCommitHash, getDataplanePlugin().getDefaultBranch().getCommitHash())
                .collect(Collectors.toList()))
        .containsExactlyInAnyOrderElementsOf(List.of(udfPath));
  }

  @Test
  public void testAddTwoTables() throws Exception {
    String fromCommitHash = getDataplanePlugin().getDefaultBranch().getCommitHash();
    runSQL(useBranchQuery(DEFAULT_BRANCH_NAME));
    List<String> firstTablePath = List.of(generateUniqueTableName());
    runSQL(createEmptyTableQuery(firstTablePath));
    List<String> secondTablePath = List.of(generateUniqueTableName());
    runSQL(createEmptyTableQuery(secondTablePath));

    assertThat(
            getDataplanePlugin()
                .listEntriesChangedBetween(
                    fromCommitHash, getDataplanePlugin().getDefaultBranch().getCommitHash())
                .collect(Collectors.toList()))
        .containsExactlyInAnyOrderElementsOf(List.of(firstTablePath, secondTablePath));
  }

  @Test
  public void testAddTableAndView() throws Exception {
    String fromCommitHash = getDataplanePlugin().getDefaultBranch().getCommitHash();
    runSQL(useBranchQuery(DEFAULT_BRANCH_NAME));
    List<String> tablePath = List.of(generateUniqueTableName());
    runSQL(createEmptyTableQuery(tablePath));
    List<String> viewPath = List.of(generateUniqueViewName());
    runSQL(createViewQuery(viewPath, tablePath));

    assertThat(
            getDataplanePlugin()
                .listEntriesChangedBetween(
                    fromCommitHash, getDataplanePlugin().getDefaultBranch().getCommitHash())
                .collect(Collectors.toList()))
        .containsExactlyInAnyOrderElementsOf(List.of(tablePath, viewPath));
  }

  @Test
  public void testAddTableViewAndUdf() throws Exception {
    String fromCommitHash = getDataplanePlugin().getDefaultBranch().getCommitHash();
    runSQL(useBranchQuery(DEFAULT_BRANCH_NAME));
    List<String> tablePath = List.of(generateUniqueTableName());
    runSQL(createEmptyTableQuery(tablePath));
    List<String> viewPath = List.of(generateUniqueViewName());
    runSQL(createViewQuery(viewPath, tablePath));
    List<String> udfPath = List.of(generateUniqueFunctionName());
    runSQL(createUdfQuery(udfPath));

    assertThat(
            getDataplanePlugin()
                .listEntriesChangedBetween(
                    fromCommitHash, getDataplanePlugin().getDefaultBranch().getCommitHash())
                .collect(Collectors.toList()))
        .containsExactlyInAnyOrderElementsOf(List.of(tablePath, viewPath, udfPath));
  }
}
