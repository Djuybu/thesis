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
package com.dremio.exec.store.dfs.easy;

import com.dremio.common.logical.FormatPluginConfig;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.physical.base.OpProps;
import com.dremio.exec.physical.base.PhysicalOperator;
import com.dremio.exec.physical.base.WriterOptions;
import com.dremio.exec.store.StoragePluginResolver;
import com.dremio.exec.store.dfs.FileSystemPlugin;
import com.dremio.exec.store.dfs.FileSystemWriter;
import com.dremio.exec.store.iceberg.SupportsFsCreation;
import com.fasterxml.jackson.annotation.JacksonInject;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.google.common.base.Preconditions;
import java.util.List;

@JsonTypeName("fs-writer")
public class EasyWriter extends FileSystemWriter {
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(EasyWriter.class);

  private final String location;
  private final FileSystemPlugin fileSystemPlugin;
  private final EasyFormatPlugin<?> easyFormatPlugin;
  private final StoragePluginId pluginId;

  @JsonCreator
  public EasyWriter(
      @JsonProperty("props") OpProps props,
      @JsonProperty("child") PhysicalOperator child,
      @JsonProperty("userName") String userName,
      @JsonProperty("location") String location,
      @JsonProperty("options") WriterOptions options,
      @JsonProperty("sortColumns") List<String> sortColumns,
      @JsonProperty("pluginId") StoragePluginId pluginId,
      @JsonProperty("format") FormatPluginConfig formatConfig,
      @JacksonInject StoragePluginResolver storagePluginResolver) {
    super(props, child, options);
    this.fileSystemPlugin = storagePluginResolver.getSource(pluginId);
    this.easyFormatPlugin =
        (EasyFormatPlugin<?>) this.fileSystemPlugin.getFormatPlugin(formatConfig);
    Preconditions.checkNotNull(
        easyFormatPlugin, "Unable to load format plugin for provided format config.");
    this.location = location;
    this.pluginId = pluginId;
  }

  public EasyWriter(
      OpProps props,
      PhysicalOperator child,
      String userName,
      String location,
      WriterOptions options,
      FileSystemPlugin<?> fileSystemPlugin,
      EasyFormatPlugin<?> easyFormatPlugin) {
    super(props, child, options);
    this.fileSystemPlugin = fileSystemPlugin;
    this.easyFormatPlugin = easyFormatPlugin;
    Preconditions.checkNotNull(
        easyFormatPlugin, "Unable to load format plugin for provided format config.");
    this.location = location;
    this.pluginId = fileSystemPlugin.getId();
  }

  @JsonProperty("location")
  public String getLocation() {
    return location;
  }

  public StoragePluginId getPluginId() {
    return pluginId;
  }

  @JsonIgnore
  public SupportsFsCreation getFileSystemCreator() {
    return fileSystemPlugin;
  }

  @JsonProperty("format")
  public FormatPluginConfig getFormatConfig() {
    return easyFormatPlugin.getConfig();
  }

  @JsonIgnore
  public EasyFormatPlugin<?> getEasyFormatPlugin() {
    return easyFormatPlugin;
  }

  @Override
  protected PhysicalOperator getNewWithChild(PhysicalOperator child) {
    return new EasyWriter(
        props, child, props.getUserName(), location, options, fileSystemPlugin, easyFormatPlugin);
  }

  @Override
  public int getOperatorType() {
    return easyFormatPlugin.getWriterOperatorType();
  }
}
