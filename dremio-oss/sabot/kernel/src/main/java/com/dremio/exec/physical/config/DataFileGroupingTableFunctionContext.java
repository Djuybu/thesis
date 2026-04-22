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
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonTypeName("data-file-grouping")
public class DataFileGroupingTableFunctionContext extends TableFunctionContext {
  // the receiver of the clustering status OOB message. The receiver of the messages is
  // WriterCommitterOperator.
  // However, we only know its address (major/minor fragment ids) after SimpleParallelizer finish
  // the assignment, which happens after the plan is generated. We would inject the
  // clusteringStatusReceiver back to the plan then.
  private OperatorId clusteringStatusReceiver;

  private final ClusteringOptions clusteringOptions;

  public DataFileGroupingTableFunctionContext(
      @JsonProperty("schema") BatchSchema outputSchema,
      @JsonProperty("projectedCols") List<SchemaPath> projectedCols,
      @JsonProperty("pluginId") StoragePluginId storagePluginId,
      @JsonProperty("targetClusteringStatusReceiver") OperatorId clusteringStatusReceiver,
      @JsonProperty("clusteringOptions") ClusteringOptions clusteringOptions) {
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
    this.clusteringStatusReceiver = clusteringStatusReceiver;
    this.clusteringOptions = clusteringOptions;
  }

  @JsonProperty("targetClusteringStatusReceiver")
  public OperatorId getTargetClusteringStatusReceiver() {
    return clusteringStatusReceiver;
  }

  public ClusteringOptions getClusteringOptions() {
    return clusteringOptions;
  }

  @JsonIgnore
  public DataFileGroupingTableFunctionContext getNewWithTargetClusteringStatusReceiver(
      OperatorId operatorId) {
    return new DataFileGroupingTableFunctionContext(
        getFullSchema(), getColumns(), getPluginId(), operatorId, clusteringOptions);
  }
}
