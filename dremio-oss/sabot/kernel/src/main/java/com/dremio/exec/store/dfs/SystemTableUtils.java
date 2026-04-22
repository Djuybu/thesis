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
package com.dremio.exec.store.dfs;

import com.dremio.service.namespace.NamespaceKey;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Maintains the list of systems tables and schema that are internally created and managed by Dremio
 * (these exist as "internal" sources).
 */
public class SystemTableUtils {
  private static final List<String> SYSTEM_TABLES_AND_SCHEMA =
      Arrays.stream(DremioSystemTables.values())
          .map(DremioSystemTables::getTableName)
          .collect(Collectors.toList());

  public static boolean isSystemTableOrScratch(final NamespaceKey namespaceKey) {
    if (namespaceKey == null || namespaceKey.getPathComponents().isEmpty()) {
      return false;
    }

    return isSystemTableOrScratch(namespaceKey.getPathComponents().get(0));
  }

  public static boolean isSystemTableOrScratch(final String tableName) {
    if (tableName == null) {
      return false;
    }
    return SYSTEM_TABLES_AND_SCHEMA.contains(tableName);
  }

  public static boolean isSystemStoragePlugin(final String tableName) {
    return Arrays.stream(DremioSystemTables.values())
        .filter(table -> table.getTableName().equals(tableName.trim()))
        .anyMatch(DremioSystemTables::isSystemStoragePlugin);
  }

  public enum DremioSystemTables {
    SYS("sys", false),
    INFORMATION_SCHEMA("INFORMATION_SCHEMA", false),
    SCRATCH("$scratch", true),
    JOB_RESULTS_STORE("__jobResultsStore", true),
    LOGS("__logs", true),
    SUPPORT("__support", true),
    HISTORY("__history", true),
    DATASET_DOWNLOAD("__datasetDownload", true),
    HOME("__home", true),
    METADATA("__metadata", true),
    ACCELERATOR("__accelerator", true),
    SYSTEM_MANAGED_VIEWS("__system_managed_views", true),
    COPY_INTO_ERRORS_STORAGE("__copy_into_errors_storage", true);

    private final String tableName;
    private final boolean isSystemStoragePlugin;

    DremioSystemTables(String tableName, boolean isSystemStoragePlugin) {
      this.tableName = tableName;
      this.isSystemStoragePlugin = isSystemStoragePlugin;
    }

    public String getTableName() {
      return tableName;
    }

    public boolean isSystemStoragePlugin() {
      return isSystemStoragePlugin;
    }
  }
}
