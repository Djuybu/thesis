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
package com.dremio.exec.store.iceberg;

import com.dremio.context.UserContext;
import com.dremio.exec.physical.config.TableFunctionConfig;
import com.dremio.exec.planner.logical.CreateTableEntry;
import com.dremio.exec.store.dfs.IcebergTableProps;
import com.dremio.io.file.FileSystem;
import com.dremio.sabot.exec.context.OperatorContext;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public interface SupportsFsCreation {

  /**
   * Creates a FileSystem according to the details in the builder object.
   *
   * @param b the FS builder instance that describes what FileSystem is needed
   * @return the created FileSystem instance
   * @throws IOException
   */
  FileSystem createFS(Builder b) throws IOException;

  class Builder {

    private boolean isWithAsyncOptions;
    private boolean isForMetadataRefresh;
    private boolean isWithoutHDFSCache;
    private String filePath;
    private String userName;
    private String userId;
    private OperatorContext operatorContext;
    private List<String> dataset;

    private Builder() {}

    public Builder withAsyncOptions(boolean isWithAsyncOptions) {
      this.isWithAsyncOptions = isWithAsyncOptions;
      ensureNotAsyncAndNoHdfsCache();
      return this;
    }

    public boolean isWithAsyncOptions() {
      return this.isWithAsyncOptions;
    }

    public Builder isForMetadataRefresh(boolean isForMetadataRefresh) {
      this.isForMetadataRefresh = isForMetadataRefresh;
      return this;
    }

    public boolean isForMetadataRefresh() {
      return this.isForMetadataRefresh;
    }

    public Builder withoutHDFSCache(boolean isWithoutHDFSCache) {
      this.isWithoutHDFSCache = isWithoutHDFSCache;
      ensureNotAsyncAndNoHdfsCache();
      return this;
    }

    public boolean isWithoutHDFSCache() {
      return this.isWithoutHDFSCache;
    }

    public Builder filePath(String filePath) {
      this.filePath = filePath;
      return this;
    }

    public String filePath() {
      return this.filePath;
    }

    public Builder userName(String userName) {
      this.userName = userName;
      return this;
    }

    public Builder withSystemUserName() {
      this.userName = UserContext.SYSTEM_USER_NAME;
      return this;
    }

    public Builder withSystemUserId() {
      this.userId = UserContext.SYSTEM_USER_CONTEXT_ID;
      return this;
    }

    public String userName() {
      return this.userName;
    }

    public Builder userId(String userId) {
      this.userId = userId;
      return this;
    }

    public String userId() {
      return this.userId;
    }

    public Builder operatorContext(OperatorContext operatorContext) {
      this.operatorContext = operatorContext;
      return this;
    }

    public OperatorContext operatorContext() {
      return this.operatorContext;
    }

    public Builder dataset(List<String> dataset) {
      this.dataset = dataset;
      return this;
    }

    public Builder datasetFromTablePaths(Collection<List<String>> tablePath) {
      if (tablePath != null) {
        return dataset(Iterables.getFirst(tablePath, null));
      }
      return this;
    }

    public Builder datasetFromTableFunctionConfig(TableFunctionConfig tableFunctionConfig) {
      if (tableFunctionConfig != null && tableFunctionConfig.getFunctionContext() != null) {
        return datasetFromTablePaths(tableFunctionConfig.getFunctionContext().getTablePath());
      }
      return this;
    }

    public Builder datasetFromCreateTableEntry(CreateTableEntry createTableEntry) {
      if (createTableEntry != null && createTableEntry.getDatasetPath() != null) {
        return dataset(createTableEntry.getDatasetPath().getPathComponents());
      }
      return this;
    }

    public Builder datasetFromIcebergTableProps(IcebergTableProps icebergTableProps) {
      if (icebergTableProps != null && icebergTableProps.getDatabaseName() != null) {
        // TODO: DX-99790 - investigate if icebergTableProps can store dbName as a List
        List<String> dataset = new ArrayList<>();
        dataset.add(null);
        dataset.addAll(List.of(icebergTableProps.getDatabaseName().split("\u001f")));
        dataset.add(icebergTableProps.getTableName());
        return dataset(dataset);
      }
      return this;
    }

    public List<String> dataset() {
      return this.dataset;
    }

    private void ensureNotAsyncAndNoHdfsCache() {
      Preconditions.checkState(!isWithAsyncOptions || !isWithoutHDFSCache);
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("filePath=")
          .append(filePath)
          .append(' ')
          .append("userName=")
          .append(userName)
          .append(' ')
          .append("operatorContext=")
          .append(operatorContext)
          .append(' ')
          .append("isWithAsyncOptions=")
          .append(isWithAsyncOptions)
          .append(' ')
          .append("isForMetadataRefresh=")
          .append(isForMetadataRefresh)
          .append(' ')
          .append("isWithoutHDFSCache=")
          .append(isWithoutHDFSCache)
          .append(' ')
          .append("dataset")
          .append(dataset);
      return sb.toString();
    }
  }

  static Builder builder() {
    return new Builder();
  }
}
