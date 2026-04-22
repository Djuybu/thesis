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
package com.dremio.plugins;

import static com.dremio.exec.store.iceberg.model.IcebergCommitOrigin.CREATE_TABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.projectnessie.client.NessieClientBuilder.createClientBuilder;

import com.dremio.catalog.model.VersionContext;
import com.dremio.exec.catalog.VersionedPlugin;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.projectnessie.client.api.NessieApiV2;
import org.projectnessie.model.ContentKey;

public class ITNessieClientImplWithRESTNessieServer extends AbstractNessieClientImplTestWithServer {

  private static NessieApiV2 api;

  @BeforeAll
  static void initApi() {
    String baseUrl =
        Preconditions.checkNotNull(
            System.getProperty("nessie.server.url"),
            "The nessie.server.url system property must be set");

    api = createClientBuilder("HTTP", null).withUri(baseUrl + "/api/v2").build(NessieApiV2.class);
  }

  private static final String NO_ANCESTOR =
      "2e1cfa82b035c26cbbbdae632cea070514eb8b773f616aaeaf668e2f0be8f10d";

  private static final String FAKE_METADATA_LOCATION = "location";
  private static final String FAKE_JOB_ID = "job";
  private static final String FAKE_USER_NAME = "user";

  @BeforeEach
  void setup() {
    init(api);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  public void testListEntriesPage(boolean withContent) {
    String parentNamespace = "listEntries" + withContent;
    int tableNum = 20;
    int pageSize = 7;
    List<ContentKey> expectedContentKeys = new ArrayList<>();
    for (int i = 0; i < tableNum; i++) {
      ContentKey key = ContentKey.of(parentNamespace, String.format("table%02d", i));
      client.commitTable(
          key.getElements(),
          FAKE_METADATA_LOCATION,
          CLIENT_METADATA,
          client.getDefaultBranch(),
          null,
          CREATE_TABLE,
          FAKE_JOB_ID,
          FAKE_USER_NAME);
      expectedContentKeys.add(key);
    }

    List<ContentKey> actualContentKeys = new ArrayList<>();
    String pageToken = null;
    do {
      NessieListResponsePage page =
          client.listEntriesPage(
              ImmutableList.of(parentNamespace),
              client.getDefaultBranch(),
              NessieClient.NestingMode.IMMEDIATE_CHILDREN_ONLY,
              withContent
                  ? NessieClient.ContentMode.ENTRY_WITH_CONTENT
                  : NessieClient.ContentMode.ENTRY_METADATA_ONLY,
              null,
              null,
              new ImmutableNessieListOptions.Builder()
                  .setMaxResultsPerPage(pageSize)
                  .setPageToken(pageToken)
                  .build());

      assertThat(page.entries().size()).isLessThanOrEqualTo(pageSize);
      assertThat(
              page.entries().stream()
                  .allMatch(
                      e ->
                          !withContent
                                  && (e.getNessieContent() == null
                                      || e.getNessieContent().isEmpty())
                              || withContent
                                  && e.getNessieContent() != null
                                  && e.getNessieContent().isPresent()))
          .isTrue();

      for (ExternalNamespaceEntry entry : page.entries()) {
        actualContentKeys.add(ContentKey.of(entry.getNameElements()));
      }
      pageToken = page.pageToken();
    } while (pageToken != null);

    assertThat(actualContentKeys).isEqualTo(expectedContentKeys);
  }

  @Test
  public void testListEntriesChangedBetweenWithFolderAndTable() {
    String folderName = String.format("folder_%s", UUID.randomUUID());
    List<String> folderPath = List.of(folderName);
    List<String> tablePath = List.of(folderName, String.format("table_%s", UUID.randomUUID()));
    String oldCommitHash = client.getDefaultBranch().getCommitHash();
    client.commitTable(
        tablePath,
        FAKE_METADATA_LOCATION,
        CLIENT_METADATA,
        client.getDefaultBranch(),
        null,
        CREATE_TABLE,
        FAKE_JOB_ID,
        FAKE_USER_NAME);

    assertListEntriesChangedBetween(
        oldCommitHash, client.getDefaultBranch().getCommitHash(), List.of(folderPath, tablePath));
  }

  @Test
  public void testListEntriesChangedBetweenWithFolderAndTableWithMoreCommits() {
    String folderName = String.format("folder_%s", UUID.randomUUID());
    List<String> folderPath = List.of(folderName);
    List<String> tablePath = List.of(folderName, String.format("table_%s", UUID.randomUUID()));
    String oldCommitHash = client.getDefaultBranch().getCommitHash();
    client.commitTable(
        tablePath,
        FAKE_METADATA_LOCATION,
        CLIENT_METADATA,
        client.getDefaultBranch(),
        null,
        CREATE_TABLE,
        FAKE_JOB_ID,
        FAKE_USER_NAME);
    String newCommitHash = client.getDefaultBranch().getCommitHash();
    client.commitTable(
        List.of(folderName, String.format("table_%s", UUID.randomUUID())),
        FAKE_METADATA_LOCATION,
        CLIENT_METADATA,
        client.getDefaultBranch(),
        null,
        CREATE_TABLE,
        FAKE_JOB_ID,
        FAKE_USER_NAME);

    assertListEntriesChangedBetween(oldCommitHash, newCommitHash, List.of(folderPath, tablePath));
  }

  @Test
  public void testListEntriesChangedBetweenWithDeletedFolderAndTable() {
    String folderName = String.format("folder_%s", UUID.randomUUID());
    List<String> folderPath = List.of(folderName);
    List<String> tablePath = List.of(folderName, String.format("table_%s", UUID.randomUUID()));
    client.commitTable(
        tablePath,
        FAKE_METADATA_LOCATION,
        CLIENT_METADATA,
        client.getDefaultBranch(),
        null,
        CREATE_TABLE,
        FAKE_JOB_ID,
        FAKE_USER_NAME);

    String oldCommitHash = client.getDefaultBranch().getCommitHash();
    client.deleteCatalogEntry(
        tablePath,
        VersionedPlugin.EntityType.ICEBERG_TABLE,
        client.getDefaultBranch(),
        FAKE_USER_NAME);
    client.deleteCatalogEntry(
        folderPath, VersionedPlugin.EntityType.FOLDER, client.getDefaultBranch(), FAKE_USER_NAME);

    assertListEntriesChangedBetween(
        oldCommitHash, client.getDefaultBranch().getCommitHash(), List.of(folderPath, tablePath));
  }

  @Test
  public void testListEntriesChangedBetweenWithTable() {
    String folderName = String.format("folder_%s", UUID.randomUUID());
    List<String> folderPath = List.of(folderName);
    client.createNamespace(
        folderPath, VersionContext.ofBranch(client.getDefaultBranch().getRefName()), null);

    String oldCommitHash = client.getDefaultBranch().getCommitHash();
    List<String> tablePath = List.of(folderName, String.format("table_%s", UUID.randomUUID()));
    client.commitTable(
        tablePath,
        FAKE_METADATA_LOCATION,
        CLIENT_METADATA,
        client.getDefaultBranch(),
        null,
        CREATE_TABLE,
        FAKE_JOB_ID,
        FAKE_USER_NAME);

    assertListEntriesChangedBetween(
        oldCommitHash, client.getDefaultBranch().getCommitHash(), List.of(tablePath));
  }

  @Test
  public void testListEntriesChangedBetweenWithDisconnectedCommitHashes() {
    Pair<String, List<String>> firstCommitData = getCommitHashAndTableFromNBranchWithoutAncestor();
    Pair<String, List<String>> secondCommitData = getCommitHashAndTableFromNBranchWithoutAncestor();

    assertThat(firstCommitData.getKey()).isNotEqualTo(secondCommitData.getKey());
    assertListEntriesChangedBetween(
        firstCommitData.getKey(),
        secondCommitData.getKey(),
        List.of(firstCommitData.getValue(), secondCommitData.getValue()));
  }

  private Pair<String, List<String>> getCommitHashAndTableFromNBranchWithoutAncestor() {
    String branchName = String.format("branch_%s", UUID.randomUUID());
    client.createBranch(branchName, VersionContext.ofCommit(NO_ANCESTOR));

    List<String> tablePath = List.of(String.format("table_%s", UUID.randomUUID()));
    client.commitTable(
        tablePath,
        FAKE_METADATA_LOCATION,
        CLIENT_METADATA,
        client.resolveVersionContext(VersionContext.ofBranch(branchName)),
        null,
        CREATE_TABLE,
        FAKE_JOB_ID,
        FAKE_USER_NAME);

    return Pair.of(
        client.resolveVersionContext(VersionContext.ofBranch(branchName)).getCommitHash(),
        tablePath);
  }

  private void assertListEntriesChangedBetween(
      String oldCommitHash, String newCommitHash, List<List<String>> expected) {
    List<List<String>> oldToNew =
        client.listEntriesChangedBetween(oldCommitHash, newCommitHash).collect(Collectors.toList());
    assertThat(oldToNew).containsExactlyInAnyOrderElementsOf(expected);

    // Swap the commits and verify they produce the same results.
    assertThat(client.listEntriesChangedBetween(newCommitHash, oldCommitHash))
        .containsExactlyInAnyOrderElementsOf(oldToNew);
  }
}
