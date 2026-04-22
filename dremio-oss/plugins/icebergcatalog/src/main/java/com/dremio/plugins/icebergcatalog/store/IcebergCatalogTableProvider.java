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

import static com.dremio.plugins.icebergcatalog.store.IcebergCatalogPluginUtils.NAMESPACE_SEPARATOR;
import static com.dremio.service.namespace.dataset.proto.DatasetType.PHYSICAL_DATASET;

import com.dremio.connector.metadata.BytesOutput;
import com.dremio.connector.metadata.DatasetMetadata;
import com.dremio.connector.metadata.EntityPath;
import com.dremio.exec.store.iceberg.BaseIcebergExecutionDatasetAccessor;
import com.dremio.exec.store.iceberg.TableSchemaProvider;
import com.dremio.exec.store.iceberg.TableSnapshotProvider;
import com.dremio.options.OptionResolver;
import com.dremio.sabot.exec.store.iceberg.proto.IcebergProtobuf;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.file.proto.FileConfig;
import com.dremio.service.namespace.file.proto.IcebergFileConfig;
import com.dremio.service.namespace.file.proto.ParquetFileConfig;
import java.util.List;
import java.util.function.Supplier;
import org.apache.iceberg.Table;

public class IcebergCatalogTableProvider extends BaseIcebergExecutionDatasetAccessor {

  private final Supplier<Table> tableSupplier;

  public IcebergCatalogTableProvider(
      EntityPath datasetPath,
      Supplier<Table> tableSupplier,
      TableSnapshotProvider tableSnapshotProvider,
      TableSchemaProvider tableSchemaProvider,
      OptionResolver optionResolver) {
    super(datasetPath, tableSupplier, tableSnapshotProvider, tableSchemaProvider, optionResolver);
    this.tableSupplier = tableSupplier;
  }

  @Override
  protected FileConfig getFileConfig() {
    return new IcebergFileConfig()
        .setParquetDataFormat(new ParquetFileConfig())
        .asFileConfig()
        .setLocation(tableSupplier.get().location());
  }

  @Override
  protected IcebergProtobuf.IcebergDatasetSplitXAttr getIcebergDatasetSplitXAttr() {
    List<String> dataset = getDatasetPath().getComponents();
    String namespaceIdentifier =
        String.join(NAMESPACE_SEPARATOR, dataset.subList(1, dataset.size() - 1));
    String tableName = getDatasetPath().getName();
    String splitPath = getMetadataLocation();

    return IcebergProtobuf.IcebergDatasetSplitXAttr.newBuilder()
        .setPath(splitPath)
        .setDbName(namespaceIdentifier)
        .setTableName(tableName)
        .build();
  }

  @Override
  public BytesOutput provideSignature(DatasetMetadata metadata) {
    // metadata is not persisted
    return BytesOutput.NONE;
  }

  @Override
  public DatasetType getDatasetType() {
    return PHYSICAL_DATASET;
  }
}
