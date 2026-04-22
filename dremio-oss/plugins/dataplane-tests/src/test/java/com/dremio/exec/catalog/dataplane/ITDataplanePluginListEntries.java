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

import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.DEFAULT_BRANCH_NAME;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.createEmptyTableQuery;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.generateUniqueTableName;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.useBranchQuery;
import static com.dremio.plugins.ExternalNamespaceEntry.Type.ICEBERG_TABLE;
import static org.assertj.core.api.Assertions.assertThat;

import com.dremio.catalog.model.ResolvedVersionContext;
import com.dremio.catalog.model.dataset.TableVersionContext;
import com.dremio.exec.catalog.VersionedPlugin;
import com.dremio.exec.catalog.dataplane.test.ITDataplanePluginTestSetup;
import com.dremio.plugins.ExternalNamespaceEntry;
import com.dremio.plugins.NessieContent;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

public class ITDataplanePluginListEntries extends ITDataplanePluginTestSetup {

  @Test
  public void testNoTables() {
    assertThat(
            getDataplanePlugin()
                .listEntries(
                    List.of(),
                    getDataplanePlugin().getDefaultBranch(),
                    VersionedPlugin.NestingMode.INCLUDE_NESTED_CHILDREN,
                    VersionedPlugin.ContentMode.ENTRY_METADATA_ONLY)
                .collect(Collectors.toList()))
        .containsExactlyInAnyOrderElementsOf(List.of());
  }

  @Test
  public void testOneTable() throws Exception {
    runSQL(useBranchQuery(DEFAULT_BRANCH_NAME));
    List<String> tablePath = List.of(generateUniqueTableName());
    runSQL(createEmptyTableQuery(tablePath));

    ResolvedVersionContext resolvedVersionContext = getDataplanePlugin().getDefaultBranch();
    //noinspection OptionalGetWithoutIsPresent
    assertThat(
            getDataplanePlugin()
                .listEntries(
                    List.of(),
                    resolvedVersionContext,
                    VersionedPlugin.NestingMode.INCLUDE_NESTED_CHILDREN,
                    VersionedPlugin.ContentMode.ENTRY_METADATA_ONLY)
                .collect(Collectors.toList()))
        .containsExactlyInAnyOrderElementsOf(
            List.of(
                ExternalNamespaceEntry.of(
                    ICEBERG_TABLE,
                    tablePath,
                    getDataplanePlugin()
                        .getNessieClient()
                        .getContent(tablePath, resolvedVersionContext, null)
                        .get()
                        .getContentId(),
                    TableVersionContext.of(resolvedVersionContext))));
  }

  @Test
  public void testOneTableWithContent() throws Exception {
    runSQL(useBranchQuery(DEFAULT_BRANCH_NAME));
    List<String> tablePath = List.of(generateUniqueTableName());
    runSQL(createEmptyTableQuery(tablePath));

    ResolvedVersionContext resolvedVersionContext = getDataplanePlugin().getDefaultBranch();
    Optional<NessieContent> nessieContent =
        getDataplanePlugin().getNessieClient().getContent(tablePath, resolvedVersionContext, null);
    assertThat(
            getDataplanePlugin()
                .listEntries(
                    List.of(),
                    resolvedVersionContext,
                    VersionedPlugin.NestingMode.INCLUDE_NESTED_CHILDREN,
                    VersionedPlugin.ContentMode.ENTRY_WITH_CONTENT)
                .collect(Collectors.toList()))
        .containsExactlyInAnyOrderElementsOf(
            List.of(
                ExternalNamespaceEntry.of(
                    ICEBERG_TABLE,
                    tablePath,
                    nessieContent.get().getContentId(),
                    TableVersionContext.of(resolvedVersionContext),
                    nessieContent)));
  }

  @Test
  public void testTwoTables() throws Exception {
    runSQL(useBranchQuery(DEFAULT_BRANCH_NAME));
    List<String> firstTablePath = List.of(generateUniqueTableName());
    runSQL(createEmptyTableQuery(firstTablePath));
    List<String> secondTablePath = List.of(generateUniqueTableName());
    runSQL(createEmptyTableQuery(secondTablePath));

    ResolvedVersionContext resolvedVersionContext = getDataplanePlugin().getDefaultBranch();
    //noinspection OptionalGetWithoutIsPresent
    assertThat(
            getDataplanePlugin()
                .listEntries(
                    List.of(),
                    resolvedVersionContext,
                    VersionedPlugin.NestingMode.INCLUDE_NESTED_CHILDREN,
                    VersionedPlugin.ContentMode.ENTRY_METADATA_ONLY)
                .collect(Collectors.toList()))
        .containsExactlyInAnyOrderElementsOf(
            List.of(
                ExternalNamespaceEntry.of(
                    ICEBERG_TABLE,
                    firstTablePath,
                    getDataplanePlugin()
                        .getNessieClient()
                        .getContent(firstTablePath, resolvedVersionContext, null)
                        .get()
                        .getContentId(),
                    TableVersionContext.of(resolvedVersionContext)),
                ExternalNamespaceEntry.of(
                    ICEBERG_TABLE,
                    secondTablePath,
                    getDataplanePlugin()
                        .getNessieClient()
                        .getContent(secondTablePath, resolvedVersionContext, null)
                        .get()
                        .getContentId(),
                    TableVersionContext.of(resolvedVersionContext))));
  }
}
