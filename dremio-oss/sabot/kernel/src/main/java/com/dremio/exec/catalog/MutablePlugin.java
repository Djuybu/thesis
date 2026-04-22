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

import com.dremio.catalog.model.ResolvedVersionContext;
import com.dremio.exec.physical.base.WriterOptions;
import com.dremio.exec.planner.logical.CreateTableEntry;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.SchemaConfig;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.exec.store.dfs.IcebergTableProps;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import java.util.List;
import java.util.Map;
import org.apache.arrow.vector.types.pojo.Field;

/** Interface for plugins that support table-level and column-level CRUD operations. */
public interface MutablePlugin extends StoragePlugin, SupportsDroppingTables {

  void createEmptyTable(
      NamespaceKey tableSchemaPath,
      final SchemaConfig schemaConfig,
      BatchSchema batchSchema,
      final WriterOptions writerOptions);

  CreateTableEntry createNewTable(
      final NamespaceKey tableSchemaPath,
      final SchemaConfig schemaConfig,
      final IcebergTableProps icebergTableProps,
      final WriterOptions writerOptions,
      final Map<String, Object> storageOptions,
      final CreateTableOptions createTableOptions);

  void alterTable(
      NamespaceKey tableSchemaPath,
      DatasetConfig datasetConfig,
      AlterTableOption alterTableOption,
      SchemaConfig schemaConfig,
      TableMutationOptions tableMutationOptions);

  void truncateTable(
      NamespaceKey tableSchemaPath,
      SchemaConfig schemaConfig,
      TableMutationOptions tableMutationOptions);

  void rollbackTable(
      NamespaceKey tableSchemaPath,
      DatasetConfig datasetConfig,
      SchemaConfig schemaConfig,
      RollbackOption rollbackOption,
      TableMutationOptions tableMutationOptions);

  void addColumns(
      NamespaceKey tableSchemaPath,
      DatasetConfig datasetConfig,
      SchemaConfig schemaConfig,
      List<Field> columnsToAdd,
      TableMutationOptions tableMutationOptions);

  void dropColumn(
      NamespaceKey tableSchemaPath,
      DatasetConfig datasetConfig,
      SchemaConfig schemaConfig,
      String columnToDrop,
      TableMutationOptions tableMutationOptions);

  void changeColumn(
      NamespaceKey tableSchemaPath,
      DatasetConfig datasetConfig,
      SchemaConfig schemaConfig,
      String columnToChange,
      Field fieldFromSqlColDeclaration,
      TableMutationOptions tableMutationOptions);

  void addPrimaryKey(
      NamespaceKey table,
      DatasetConfig datasetConfig,
      SchemaConfig schemaConfig,
      List<Field> columns,
      ResolvedVersionContext versionContext);

  void dropPrimaryKey(
      NamespaceKey table,
      DatasetConfig datasetConfig,
      SchemaConfig schemaConfig,
      ResolvedVersionContext versionContext);

  List<String> getPrimaryKey(
      NamespaceKey key,
      DatasetConfig datasetConfig,
      SchemaConfig schemaConfig,
      ResolvedVersionContext versionContext,
      boolean saveInKvStore);

  StoragePluginId getId();

  void alterSortOrder(
      NamespaceKey table,
      DatasetConfig datasetConfig,
      BatchSchema batchSchema,
      SchemaConfig schemaConfig,
      List<String> sortOrderColumns,
      TableMutationOptions tableMutationOptions);

  void updateTableProperties(
      NamespaceKey table,
      DatasetConfig datasetConfig,
      BatchSchema schema,
      SchemaConfig schemaConfig,
      Map<String, String> tableProperties,
      TableMutationOptions tableMutationOptions,
      boolean isRemove);

  default boolean isSupportUserDefinedSchema(DatasetConfig dataset) {
    return false;
  }

  /**
   * @return The default ctas format to use for the plugin.
   */
  default String getDefaultCtasFormat() {
    throw new UnsupportedOperationException("getDefaultCtasFormat is not implemented");
  }
}
