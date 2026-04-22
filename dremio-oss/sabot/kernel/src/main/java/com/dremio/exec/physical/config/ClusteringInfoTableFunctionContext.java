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
package com.dremio.exec.physical.config;

import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.physical.base.ClusteringOptions;
import com.dremio.exec.record.BatchSchema;
import com.dremio.service.namespace.NamespaceKey;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeName("clustering-info")
public class ClusteringInfoTableFunctionContext extends TableFunctionContext {

  private final ClusteringOptions clusteringOptions;
  private final String tableName;
  private final String clusteringKeys;
  private final String metadataLocation;
  private final NamespaceKey dataSet;

  public ClusteringInfoTableFunctionContext(
      @JsonProperty("schema") BatchSchema outputSchema,
      @JsonProperty("projectedCols") List<SchemaPath> projectedCols,
      @JsonProperty("pluginId") StoragePluginId storagePluginId,
      @JsonProperty("clusteringOptions") ClusteringOptions clusteringOptions,
      @JsonProperty("tableName") String tableName,
      @JsonProperty("clusteringKeys") String clusteringKeys,
      @JsonProperty("metadataLocation") String metadataLocation,
      @JsonProperty("dataSet") NamespaceKey dataSet) {
    super(
        null,
        outputSchema,
        null,
        null,
        null,
        null,
        storagePluginId,
        null,
        projectedCols,
        null,
        null,
        false,
        false,
        false,
        null);
    this.clusteringOptions = clusteringOptions;
    this.tableName = tableName;
    this.clusteringKeys = clusteringKeys;
    this.metadataLocation = metadataLocation;
    this.dataSet = dataSet;
  }

  public ClusteringOptions getClusteringOptions() {
    return clusteringOptions;
  }

  public String getTableName() {
    return tableName;
  }

  public String getClusteringKeys() {
    return clusteringKeys;
  }

  public String getMetadataLocation() {
    return metadataLocation;
  }

  public NamespaceKey getDataSet() {
    return dataSet;
  }
}
