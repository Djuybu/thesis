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
package com.dremio.exec.store.parquet;

import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.catalog.SupportsFsMutablePlugin;
import com.dremio.exec.physical.base.CombineSmallFileOptions;
import com.dremio.exec.physical.base.OpProps;
import com.dremio.exec.physical.base.PhysicalOperator;
import com.dremio.exec.physical.base.WriterOptions;
import com.dremio.exec.proto.UserBitShared.CoreOperatorType;
import com.dremio.exec.store.StoragePluginResolver;
import com.dremio.exec.store.dfs.FileSystemWriter;
import com.fasterxml.jackson.annotation.JacksonInject;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import java.util.List;
import javax.annotation.Nullable;

@JsonTypeName("parquet-writer")
public class ParquetWriter extends FileSystemWriter {
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ParquetWriter.class);

  private final String location;
  private final SupportsFsMutablePlugin plugin;
  private final List<String> dataset;

  @JsonCreator
  public ParquetWriter(
      @JsonProperty("props") OpProps props,
      @JsonProperty("child") PhysicalOperator child,
      @JsonProperty("location") String location,
      @JsonProperty("options") WriterOptions options,
      @JsonProperty("pluginId") StoragePluginId pluginId,
      @JsonProperty("dataset") List<String> dataset,
      @JacksonInject StoragePluginResolver storagePluginResolver) {
    super(props, child, options);
    this.plugin = storagePluginResolver.getSource(pluginId);
    this.location = location;
    this.dataset = dataset;
  }

  public ParquetWriter(
      OpProps props,
      PhysicalOperator child,
      String location,
      WriterOptions options,
      SupportsFsMutablePlugin plugin,
      @Nullable List<String> dataset) {
    super(props, child, options);
    this.plugin = plugin;
    this.location = location;
    this.dataset = dataset;
  }

  public StoragePluginId getPluginId() {
    return plugin.getId();
  }

  @JsonProperty("location")
  public String getLocation() {
    return location;
  }

  public List<String> getDataset() {
    return dataset;
  }

  @Override
  protected PhysicalOperator getNewWithChild(PhysicalOperator child) {
    return new ParquetWriter(props, child, location, getOptions(), plugin, dataset);
  }

  @Override
  public int getOperatorType() {
    CombineSmallFileOptions combineSmallFileOptions = options.getCombineSmallFileOptions();
    if (combineSmallFileOptions != null && combineSmallFileOptions.getIsSmallFileWriter()) {
      return CoreOperatorType.SMALL_FILE_COMBINATION_WRITER_VALUE;
    }
    return CoreOperatorType.PARQUET_WRITER_VALUE;
  }

  @JsonIgnore
  public SupportsFsMutablePlugin getPlugin() {
    return this.plugin;
  }
}
