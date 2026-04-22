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
package com.dremio.exec.tablefunctions.clusteringinfo;

import static com.dremio.exec.store.iceberg.IcebergUtils.convertListTablePropertiesToMap;

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.catalog.DremioPrepareTable;
import com.dremio.exec.catalog.SimpleCatalog;
import com.dremio.exec.ops.DelegatingPlannerCatalog;
import com.dremio.exec.ops.DremioCatalogReader;
import com.dremio.exec.store.iceberg.IcebergUtils;
import com.dremio.exec.tablefunctions.VersionedTableMacro;
import com.dremio.service.namespace.DatasetHelper;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.IcebergMetadata;
import java.util.List;
import java.util.Map;

/**
 * Internal structure to hold input parameters/options for clustering_information table function.
 */
public class ClusteringInfoContext {
  private final SimpleCatalog<?> catalog;
  private final String targetTableName;
  private DremioPrepareTable resolvedTargetTable;
  private String metadataLocation;
  private String sortOrder;
  private Map<String, String> tableProperties = null;

  public ClusteringInfoContext(SimpleCatalog<?> catalog, String targetTableName) {
    this.catalog = catalog;
    validateTargetTableName(targetTableName);
    this.targetTableName = targetTableName;
  }

  private void validateTargetTableName(String targetTableName) {
    DremioCatalogReader catalogReader =
        new DremioCatalogReader(DelegatingPlannerCatalog.newInstance(catalog));
    try {
      List<String> tablePath = VersionedTableMacro.splitTableIdentifier(targetTableName);
      resolvedTargetTable = catalogReader.getTable(tablePath);

      if (resolvedTargetTable == null) {
        throw UserException.resourceError()
            .message("Unable to find target table %s", targetTableName)
            .buildSilently();
      }
      DatasetConfig datasetConfig = resolvedTargetTable.getTable().getDatasetConfig();
      if (DatasetHelper.isIcebergDataset(datasetConfig)) {
        metadataLocation = IcebergUtils.getMetadataLocation(datasetConfig, null);
        sortOrder = IcebergUtils.getIcebergSortOrderIfSet(datasetConfig);
        IcebergMetadata metadata = datasetConfig.getPhysicalDataset().getIcebergMetadata();
        tableProperties = convertListTablePropertiesToMap(metadata.getTablePropertiesList());
        tableProperties.remove(IcebergUtils.CLUSTERING_INFO);
      }
    } catch (IllegalArgumentException e) {
      throw UserException.parseError(e)
          .message("Invalid table identifier %s", targetTableName)
          .buildSilently();
    }
  }

  public String getTargetTableName() {
    return targetTableName;
  }

  public DremioPrepareTable getResolvedTargetTable() {
    return resolvedTargetTable;
  }

  public String getMetadataLocation() {
    return metadataLocation;
  }

  public SimpleCatalog<?> getCatalog() {
    return catalog;
  }

  public String getSortOrder() {
    return sortOrder;
  }

  public Map<String, String> getTableProperties() {
    return tableProperties;
  }
}
