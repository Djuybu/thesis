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
package com.dremio.exec.catalog.conf;

import static org.assertj.core.api.Assertions.assertThat;

import com.dremio.exec.catalog.SourceNameRefreshAction;
import java.util.List;
import org.junit.jupiter.api.Test;

class TestConnectionConfUtils {

  private static final String SOURCE_NAME = "source1";

  @Test
  public void testReplacePluginAllowedDatabasesChange_NoChange_Empty() {
    List<String> currentFolders = null;
    List<String> newFolders = null;

    List<SourceNameRefreshAction> actions =
        ConnectionConfUtils.getNameRefreshActionsForFoldersChange(
            SOURCE_NAME, currentFolders, newFolders);

    assertThat(actions).isEmpty();
  }

  @Test
  public void testReplacePluginAllowedDatabasesChange_NoChange() {
    List<String> currentFolders = List.of("a", "b", "c", "d");
    List<String> newFolders = List.of("a", "b", "c", "d");

    List<SourceNameRefreshAction> actions =
        ConnectionConfUtils.getNameRefreshActionsForFoldersChange(
            SOURCE_NAME, currentFolders, newFolders);

    assertThat(actions).isEmpty();
  }

  @Test
  public void testReplacePluginAllowedDatabasesChange_Add() {
    List<String> currentFolders = null;
    List<String> newFolders = List.of("a", "b", "c", "d");

    List<SourceNameRefreshAction> actions =
        ConnectionConfUtils.getNameRefreshActionsForFoldersChange(
            SOURCE_NAME, currentFolders, newFolders);

    assertThat(actions)
        .containsExactly(
            SourceNameRefreshAction.newDeleteAllAction(),
            SourceNameRefreshAction.newRefreshAllAction());
  }

  @Test
  public void testReplacePluginAllowedDatabasesChange_Update_Add() {
    List<String> currentFolders = List.of("a", "b", "c", "d");
    List<String> newFolders = List.of("a", "b", "c", "d", "e", "f");

    List<SourceNameRefreshAction> actions =
        ConnectionConfUtils.getNameRefreshActionsForFoldersChange(
            SOURCE_NAME, currentFolders, newFolders);

    List<List<String>> added = List.of(List.of(SOURCE_NAME, "e"), List.of(SOURCE_NAME, "f"));
    assertThat(actions).containsExactly(SourceNameRefreshAction.newRefreshFoldersAction(added));
  }

  @Test
  public void testReplacePluginAllowedDatabasesChange_Update_Delete() {
    List<String> currentFolders = List.of("a", "b", "c", "d");
    List<String> newFolders = List.of("a", "b");

    List<SourceNameRefreshAction> actions =
        ConnectionConfUtils.getNameRefreshActionsForFoldersChange(
            SOURCE_NAME, currentFolders, newFolders);

    List<List<String>> deleted = List.of(List.of(SOURCE_NAME, "c"), List.of(SOURCE_NAME, "d"));
    assertThat(actions).containsExactly(SourceNameRefreshAction.newDeleteFoldersAction(deleted));
  }

  @Test
  public void testReplacePluginAllowedDatabasesChange_Update_AddAndDelete() {
    List<String> currentFolders = List.of("a", "b", "c", "d");
    List<String> newFolders = List.of("a", "b", "e", "f");

    List<SourceNameRefreshAction> actions =
        ConnectionConfUtils.getNameRefreshActionsForFoldersChange(
            SOURCE_NAME, currentFolders, newFolders);

    List<List<String>> deleted = List.of(List.of(SOURCE_NAME, "c"), List.of(SOURCE_NAME, "d"));
    List<List<String>> added = List.of(List.of(SOURCE_NAME, "e"), List.of(SOURCE_NAME, "f"));
    assertThat(actions)
        .containsExactly(
            SourceNameRefreshAction.newDeleteFoldersAction(deleted),
            SourceNameRefreshAction.newRefreshFoldersAction(added));
  }

  @Test
  public void testReplacePluginAllowedDatabasesChange_Remove() {
    List<String> currentFolders = List.of("a", "b", "c", "d");
    List<String> newFolders = null;

    List<SourceNameRefreshAction> actions =
        ConnectionConfUtils.getNameRefreshActionsForFoldersChange(
            SOURCE_NAME, currentFolders, newFolders);

    assertThat(actions).containsExactly(SourceNameRefreshAction.newRefreshAllAction());
  }
}
