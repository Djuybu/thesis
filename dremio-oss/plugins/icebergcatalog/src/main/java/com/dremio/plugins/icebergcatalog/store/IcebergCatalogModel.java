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
package com.dremio.plugins.icebergcatalog.store;

import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.iceberg.model.IcebergBaseModel;
import com.dremio.exec.store.iceberg.model.IcebergCommand;
import com.dremio.exec.store.iceberg.model.IcebergCommitOrigin;
import com.dremio.exec.store.iceberg.model.IcebergOpCommitter;
import com.dremio.exec.store.iceberg.model.IcebergTableCreationCommitter;
import com.dremio.exec.store.iceberg.model.IcebergTableIdentifier;
import com.dremio.exec.store.metadatarefresh.committer.DatasetCatalogGrpcClient;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.context.OperatorStats;
import com.google.common.annotations.VisibleForTesting;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.io.FileIO;
import org.jetbrains.annotations.Nullable;

public class IcebergCatalogModel extends IcebergBaseModel {
  private final RestIcebergCatalogPlugin plugin;
  private final List<String> dataset;
  private final String queryUserName;
  private final String queryUserId;

  protected IcebergCatalogModel(
      @Nullable String namespace,
      Configuration configuration,
      FileIO fileIO,
      @Nullable OperatorContext operatorContext,
      @Nullable DatasetCatalogGrpcClient datasetCatalogGrpcClient,
      RestIcebergCatalogPlugin plugin,
      List<String> dataset,
      String queryUserName,
      String queryUserId) {
    super(namespace, configuration, fileIO, operatorContext, datasetCatalogGrpcClient);

    this.plugin = plugin;
    this.dataset = dataset;
    this.queryUserName = queryUserName;
    this.queryUserId = queryUserId;
  }

  @Override
  protected IcebergCommand getIcebergCommand(
      IcebergTableIdentifier tableIdentifier, @Nullable IcebergCommitOrigin commitOrigin) {
    final TableOperations tableOperations =
        plugin.createIcebergTableOperations(fileIO, tableIdentifier, queryUserName, queryUserId);
    final String icebergPath = getIcebergPath(tableIdentifier);

    return new IcebergCatalogCommand(configuration, icebergPath, tableOperations, currentQueryId());
  }

  @Override
  protected IcebergCommand getIcebergCommand(
      IcebergTableIdentifier tableIdentifier,
      String tableLocation,
      @Nullable IcebergCommitOrigin commitOrigin) {
    // TODO: DX-100231 - investigate tableLocation vs getIcebergPath()
    return getIcebergCommand(tableIdentifier, commitOrigin);
  }

  private IcebergCommand getIcebergCommand(
      IcebergTableIdentifier tableIdentifier, TableOperations tableOperations) {
    final String icebergPath = getIcebergPath(tableIdentifier);

    return new IcebergCatalogCommand(configuration, icebergPath, tableOperations, currentQueryId());
  }

  @Override
  public IcebergOpCommitter getCreateTableCommitter(
      String tableName,
      IcebergTableIdentifier tableIdentifier,
      BatchSchema batchSchema,
      List<String> partitionColumnNames,
      OperatorStats operatorStats,
      PartitionSpec partitionSpec,
      SortOrder sortOrder,
      Map<String, String> tableProperties,
      String tableLocation) {
    TableOperations tableOperations =
        plugin.createIcebergTableOperationsForCtas(
            fileIO, tableIdentifier, batchSchema, queryUserName, queryUserId);
    IcebergCommand icebergCommand = getIcebergCommand(tableIdentifier, tableOperations);

    return new IcebergTableCreationCommitter(
        tableName,
        batchSchema,
        partitionColumnNames,
        icebergCommand,
        tableProperties,
        operatorStats,
        partitionSpec,
        sortOrder);
  }

  @Override
  public IcebergTableIdentifier getTableIdentifier(String icebergPath) {
    return new IcebergCatalogTableIdentifier(dataset, icebergPath);
  }

  private String getIcebergPath(IcebergTableIdentifier tableIdentifier) {
    return ((IcebergCatalogTableIdentifier) tableIdentifier).getIcebergPath();
  }

  @VisibleForTesting
  public String getQueryUserId() {
    return queryUserId;
  }
}
