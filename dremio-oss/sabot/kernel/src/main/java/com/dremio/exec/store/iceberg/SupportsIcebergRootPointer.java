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

import com.dremio.common.logical.FormatPluginConfig;
import com.dremio.connector.metadata.GetDatasetOption;
import com.dremio.exec.physical.base.OpProps;
import com.dremio.exec.physical.config.TableFunctionConfig;
import com.dremio.exec.store.BlockBasedSplitGenerator;
import com.dremio.exec.store.dfs.FormatPlugin;
import com.dremio.exec.store.iceberg.model.IcebergTableIdentifier;
import com.dremio.exec.store.parquet.ScanTableFunction;
import com.dremio.io.file.FileSystem;
import com.dremio.options.OptionManager;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.fragment.FragmentExecutionContext;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import java.util.List;
import javax.annotation.Nullable;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.view.View;

/** Interface for plugins that support reading of iceberg root pointers. */
public interface SupportsIcebergRootPointer extends SupportsFsCreation {

  /**
   * @return A copy of the configuration to use for the plugin.
   */
  Configuration getFsConfCopy();

  /** Checks if a metadata validity check is required. */
  boolean isMetadataValidityCheckRecentEnough(
      Long lastMetadataValidityCheckTime, Long currentTime, OptionManager optionManager);

  /**
   * @param formatConfig
   * @return Returns format plugin based on the formatConfig.
   */
  FormatPlugin getFormatPlugin(FormatPluginConfig formatConfig);

  default boolean supportsColocatedReads() {
    return true;
  }

  /**
   * Checks if the Iceberg Metadata present in the kvstore for the given dataset is valid or not.
   */
  boolean isIcebergMetadataValid(DatasetConfig config, NamespaceKey key);

  /**
   * Based on the source plugin, creates an instance of corresponding Iceberg Catalog table
   * operations.
   */
  TableOperations createIcebergTableOperations(
      FileIO fileIO,
      IcebergTableIdentifier tableIdentifier,
      @Nullable String queryUserName,
      @Nullable String queryUserId);

  FileIO createIcebergFileIO(
      FileSystem fs,
      OperatorContext context,
      List<String> dataset,
      String datasourcePluginUID,
      Long fileLength);

  /** Creates the plugin-specific split creator. */
  BlockBasedSplitGenerator.SplitCreator createSplitCreator(
      OperatorContext context, byte[] extendedBytes, boolean isInternalIcebergTable);

  /** Creates the plugin-specific Scan Table Function. */
  ScanTableFunction createScanTableFunction(
      FragmentExecutionContext fec,
      OperatorContext context,
      OpProps props,
      TableFunctionConfig functionConfig);

  /** Loads Iceberg Table Metadata for Iceberg Catalog table */
  default TableMetadata loadTableMetadata(
      FileIO io, OperatorContext context, List<String> dataset, String metadataLocation) {
    return IcebergUtils.loadTableMetadata(io, context, metadataLocation);
  }

  default View loadViewMetadata(TableIdentifier viewIdentifier, GetDatasetOption... options) {
    throw new UnsupportedOperationException("Loading view metadata is not supported");
  }
}
