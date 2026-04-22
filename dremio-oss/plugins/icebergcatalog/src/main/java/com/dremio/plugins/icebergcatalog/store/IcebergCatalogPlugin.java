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

import static com.dremio.exec.store.IcebergCatalogPluginOptions.RESTCATALOG_PLUGIN_MUTABLE_ENABLED;

import com.dremio.common.config.SabotConfig;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.logical.FormatPluginConfig;
import com.dremio.connector.metadata.DatasetHandle;
import com.dremio.connector.metadata.DatasetHandleListing;
import com.dremio.connector.metadata.DatasetMetadata;
import com.dremio.connector.metadata.DatasetMetadataVerifyResult;
import com.dremio.connector.metadata.EntityPath;
import com.dremio.connector.metadata.GetDatasetOption;
import com.dremio.connector.metadata.GetMetadataOption;
import com.dremio.connector.metadata.ListPartitionChunkOption;
import com.dremio.connector.metadata.PartitionChunkListing;
import com.dremio.connector.metadata.ViewDatasetHandle;
import com.dremio.connector.metadata.extensions.SupportsListingDatasets;
import com.dremio.connector.metadata.extensions.SupportsMetadataVerify;
import com.dremio.connector.metadata.options.MetadataVerifyRequest;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.catalog.PluginSabotContext;
import com.dremio.exec.catalog.SupportsFolderIngestion;
import com.dremio.exec.catalog.SupportsMutatingFolders;
import com.dremio.exec.catalog.SupportsMutatingViews;
import com.dremio.exec.catalog.SupportsRefreshViews;
import com.dremio.exec.physical.base.OpProps;
import com.dremio.exec.physical.config.TableFunctionConfig;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.BlockBasedSplitGenerator;
import com.dremio.exec.store.ClassPathFileSystem;
import com.dremio.exec.store.LocalSyncableFileSystem;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.exec.store.StoragePluginRulesFactory;
import com.dremio.exec.store.dfs.FileSystemConfigurationUtils;
import com.dremio.exec.store.dfs.FileSystemRulesFactory;
import com.dremio.exec.store.dfs.FileSystemWrapper;
import com.dremio.exec.store.dfs.FormatPlugin;
import com.dremio.exec.store.dfs.MetadataVerifyHandle;
import com.dremio.exec.store.hive.exec.FileSystemConfUtil;
import com.dremio.exec.store.iceberg.DremioFileIO;
import com.dremio.exec.store.iceberg.IcebergFormatConfig;
import com.dremio.exec.store.iceberg.IcebergFormatPlugin;
import com.dremio.exec.store.iceberg.SchemaConverter;
import com.dremio.exec.store.iceberg.SupportsIcebergMutablePlugin;
import com.dremio.exec.store.iceberg.SupportsIcebergRestApi;
import com.dremio.exec.store.iceberg.SupportsIcebergRootPointer;
import com.dremio.exec.store.iceberg.model.IcebergTableIdentifier;
import com.dremio.exec.store.parquet.ParquetScanTableFunction;
import com.dremio.exec.store.parquet.ParquetSplitCreator;
import com.dremio.exec.store.parquet.ScanTableFunction;
import com.dremio.io.file.FileSystem;
import com.dremio.options.OptionManager;
import com.dremio.options.TypeValidators.BooleanValidator;
import com.dremio.plugins.icebergcatalog.dfs.DatasetFileSystemCache;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.fragment.FragmentExecutionContext;
import com.dremio.service.namespace.DatasetHelper;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.SourceState;
import com.dremio.service.namespace.capabilities.SourceCapabilities;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.google.common.annotations.VisibleForTesting;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.view.View;

public abstract class IcebergCatalogPlugin
    implements StoragePlugin,
        SupportsIcebergRootPointer,
        SupportsListingDatasets,
        SupportsMutatingViews,
        SupportsMutatingFolders,
        SupportsFolderIngestion,
        SupportsIcebergMutablePlugin,
        SupportsRefreshViews,
        SupportsIcebergRestApi,
        SupportsMetadataVerify {

  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(IcebergCatalogPlugin.class);

  private final IcebergCatalogPluginConfig config;
  private final String name;
  private final IcebergCatalogFileSystemConfigurationAdapter fsConfAdapter;
  private DatasetFileSystemCache hadoopFs;
  private CatalogAccessor catalogAccessor = null;
  private final AtomicBoolean isOpen = new AtomicBoolean(false);
  private final OptionManager optionManager;
  private final SabotConfig sabotConfig;
  private final FileSystemWrapper fileSystemWrapper;
  private final boolean isExecutor;
  private final PluginSabotContext pluginSabotContext;

  public IcebergCatalogPlugin(
      IcebergCatalogPluginConfig config, PluginSabotContext pluginSabotContext, String name) {
    this.config = config;
    this.name = name;
    this.optionManager = pluginSabotContext.getOptionManager();
    this.fsConfAdapter = initializeFileSystemConfigurationAdapter(optionManager);
    this.sabotConfig = pluginSabotContext.getConfig();
    this.fileSystemWrapper = pluginSabotContext.getFileSystemWrapper();
    this.isExecutor = pluginSabotContext.isExecutor();
    this.pluginSabotContext = pluginSabotContext;
  }

  private static IcebergCatalogFileSystemConfigurationAdapter
      initializeFileSystemConfigurationAdapter(OptionManager optionManager) {
    Configuration fsConf = FileSystemConfigurationUtils.getNewFsConf(optionManager);
    initializeHadoopConf(fsConf, optionManager);
    return new IcebergCatalogFileSystemConfigurationAdapter(fsConf);
  }

  private static void initializeHadoopConf(Configuration hadoopConf, OptionManager optionManager) {
    hadoopConf.set("fs.classpath.impl", ClassPathFileSystem.class.getName());
    hadoopConf.set("fs.dremio-local.impl", LocalSyncableFileSystem.class.getName());
    FileSystemConfUtil.FS_CACHE_DISABLES.forEach(hadoopConf::set);
    FileSystemConfUtil.S3_PROPS.forEach(hadoopConf::set);
    FileSystemConfUtil.WASB_PROPS.forEach(hadoopConf::set);
    FileSystemConfUtil.ABFS_PROPS.forEach(hadoopConf::set);

    hadoopConf.set(
        ExecConstants.ENABLE_S3_V2_CLIENT.getOptionName(),
        Boolean.toString(optionManager.getOption(ExecConstants.ENABLE_S3_V2_CLIENT)));
  }

  protected DatasetFileSystemCache createFSCache() {
    return new DatasetFileSystemCache((noop) -> getFsConfCopy(), optionManager);
  }

  public String getName() {
    return name;
  }

  public CatalogAccessor getCatalogAccessor() {
    if (!isOpen.get()) {
      throw UserException.sourceInBadState()
          .message("Iceberg Catalog Source %s is either not started or already closed", getName())
          .addContext("name", getName())
          .buildSilently();
    }
    return catalogAccessor;
  }

  @Override
  public DatasetHandleListing listDatasetHandles(GetDatasetOption... options) {
    return getCatalogAccessor().listDatasetHandles(getName(), this);
  }

  @Override
  public Optional<DatasetHandle> getDatasetHandle(
      EntityPath datasetPath, GetDatasetOption... options) {
    List<String> components = datasetPath.getComponents();
    if (components.size() < 3) {
      return Optional.empty();
    }

    return getCatalogAccessor().getDatasetHandle(components, this, options);
  }

  @Override
  public PartitionChunkListing listPartitionChunks(
      DatasetHandle datasetHandle, ListPartitionChunkOption... options) {

    if (datasetHandle instanceof ViewDatasetHandle) {
      return Collections::emptyIterator;
    }

    IcebergCatalogTableProvider icebergTableProvider =
        datasetHandle.unwrap(IcebergCatalogTableProvider.class);
    return getCatalogAccessor().listPartitionChunks(icebergTableProvider, options);
  }

  @Override
  public DatasetMetadata getDatasetMetadata(
      DatasetHandle datasetHandle,
      PartitionChunkListing chunkListing,
      GetMetadataOption... options) {

    if (viewsEnabled() && datasetHandle instanceof ViewDatasetHandle) {
      return getCatalogAccessor().getViewMetadata(datasetHandle);
    }
    IcebergCatalogTableProvider icebergTableProvider =
        datasetHandle.unwrap(IcebergCatalogTableProvider.class);
    return getCatalogAccessor().getTableMetadata(icebergTableProvider, options);
  }

  @Override
  public boolean containerExists(EntityPath containerPath, GetMetadataOption... options) {
    try {
      return getCatalogAccessor().datasetExists(containerPath.getComponents())
          || getCatalogAccessor().namespaceExists(containerPath.getComponents());
    } catch (BadRequestException e) {
      return false;
    }
  }

  @Override
  public boolean hasAccessPermission(String user, NamespaceKey key, DatasetConfig datasetConfig) {
    // TODO: implement RBAC
    return true;
  }

  @Override
  public SourceState getState() {
    if (!isOpen.get()) {
      logger.error("Iceberg Catalog Source {} is either not started or already closed.", getName());
      return SourceState.badState(
          String.format(
              "Could not connect to %s, check your connection information and credentials",
              getName()),
          String.format("Iceberg Catalog Source %s has not been started.", getName()));
    }

    try {
      getCatalogAccessor().checkState();
      return SourceState.GOOD;
    } catch (Exception ex) {
      logger.debug(
          "Caught exception while trying to get status of Iceberg Catalog Source {}, error: ",
          getName(),
          ex);
      return SourceState.badState(
          String.format(
              "Could not connect to %s, check your connection information and credentials",
              getName()),
          String.format("Failure connecting to source: %s", ex.getMessage()));
    }
  }

  @Override
  public SourceCapabilities getSourceCapabilities() {
    return SourceCapabilities.NONE;
  }

  @Override
  public Class<? extends StoragePluginRulesFactory> getRulesFactoryClass() {
    return sabotConfig.getClass(
        "dremio.plugins.dfs.rulesfactory",
        StoragePluginRulesFactory.class,
        FileSystemRulesFactory.class);
  }

  @Override
  public void start() throws IOException {
    validateOnStart();
    catalogAccessor = createCatalog(fsConfAdapter.getConfiguration());
    hadoopFs = createFSCache();
    isOpen.set(true);
  }

  @Override
  public void close() throws Exception {
    if (!isOpen.getAndSet(false)) {
      return;
    }

    try {
      catalogAccessor.close();
    } catch (Exception e) {
      logger.warn("Failed to close catalog instance", e);
    }

    try {
      getHadoopFileSystemCache().close();
    } catch (Exception e) {
      logger.warn("Failed to close file system provider", e);
    }
  }

  @Override
  public boolean isMetadataValidityCheckRecentEnough(
      Long lastMetadataValidityCheckTime, Long currentTime, OptionManager optionManager) {
    final long metadataAggressiveExpiryTime =
        optionManager.getOption(PlannerSettings.METADATA_EXPIRY_CHECK_INTERVAL_SECS) * 1000;
    // dataset metadata validity was checked too long ago (or never)
    return lastMetadataValidityCheckTime != null
        && lastMetadataValidityCheckTime + metadataAggressiveExpiryTime >= currentTime;
  }

  @Override
  public FormatPlugin getFormatPlugin(FormatPluginConfig formatConfig) {
    if (formatConfig instanceof IcebergFormatConfig) {
      return new IcebergFormatPlugin(
          "iceberg", pluginSabotContext, (IcebergFormatConfig) formatConfig, this);
    }
    throw new UnsupportedOperationException(
        "Format plugins for non iceberg use cases are not supported.");
  }

  @Override
  public Configuration getFsConfCopy() {
    return new Configuration(fsConfAdapter.getConfiguration());
  }

  private FileSystem newFileSystem(
      String filePath,
      String userName,
      String userId,
      OperatorContext operatorContext,
      List<String> dataset) {
    return new IcebergCatalogFileSystem(
        getHadoopFileSystemCache()
            .load(
                filePath,
                userName,
                userId,
                dataset,
                operatorContext == null ? null : operatorContext.getStats(),
                config.isAsyncEnabled()));
  }

  @Override
  public FileSystem createFS(Builder b) throws IOException {
    return fileSystemWrapper.wrap(
        newFileSystem(b.filePath(), b.userName(), b.userId(), b.operatorContext(), b.dataset()),
        getName(),
        config,
        b.operatorContext(),
        config.isAsyncEnabled(),
        false);
  }

  @Override
  public BlockBasedSplitGenerator.SplitCreator createSplitCreator(
      OperatorContext context, byte[] extendedBytes, boolean isInternalIcebergTable) {
    return new ParquetSplitCreator(context, false);
  }

  @Override
  public ScanTableFunction createScanTableFunction(
      FragmentExecutionContext fec,
      OperatorContext context,
      OpProps props,
      TableFunctionConfig functionConfig) {
    return new ParquetScanTableFunction(fec, context, props, functionConfig);
  }

  @Override
  public boolean isIcebergMetadataValid(DatasetConfig config, NamespaceKey tableSchemaPath) {
    return DatasetHelper.isIcebergView(config)
        ? isIcebergViewMetadataValid(config, tableSchemaPath)
        : isIcebergTableMetadataValid(config, tableSchemaPath);
  }

  private boolean isIcebergTableMetadataValid(DatasetConfig config, NamespaceKey tableSchemaPath) {
    if (config.getPhysicalDataset().getIcebergMetadata() == null
        || config.getPhysicalDataset().getIcebergMetadata().getMetadataFileLocation() == null
        || config.getPhysicalDataset().getIcebergMetadata().getMetadataFileLocation().isEmpty()) {
      return false;
    }

    List<String> tableSchemaPathPathComponents = tableSchemaPath.getPathComponents();

    String existingRootPointer =
        config.getPhysicalDataset().getIcebergMetadata().getMetadataFileLocation();
    try {
      String latestRootPointer =
          getCatalogAccessor()
              .getTableMetadata(tableSchemaPathPathComponents)
              .metadataFileLocation();
      if (!existingRootPointer.equals(latestRootPointer)) {
        logger.debug(
            "Iceberg Dataset {} metadata is not valid. Existing root pointer in catalog: {}. Latest Iceberg table root pointer: {}.",
            tableSchemaPath,
            existingRootPointer,
            latestRootPointer);
        return false;
      }
    } catch (NoSuchTableException e) {
      throw UserException.ioExceptionError(e)
          .message(String.format("Dataset path '%s', table not found.", tableSchemaPath))
          .buildSilently();
    }
    return true;
  }

  private boolean isIcebergViewMetadataValid(DatasetConfig config, NamespaceKey viewPath) {
    if (config.getVirtualDataset() == null
        || config.getVirtualDataset().getIcebergViewAttributes() == null
        || config.getVirtualDataset().getIcebergViewAttributes().getMetadataLocation() == null
        || config.getVirtualDataset().getIcebergViewAttributes().getMetadataLocation().isEmpty()) {
      return false;
    }

    List<String> viewPathComponents = viewPath.getPathComponents();

    String existingRootPointer =
        config.getVirtualDataset().getIcebergViewAttributes().getMetadataLocation();
    try {
      String latestRootPointer =
          getCatalogAccessor().getViewMetadata(viewPathComponents).metadataFileLocation();
      if (!existingRootPointer.equals(latestRootPointer)) {
        logger.debug(
            "Iceberg view {} metadata is not valid. Existing root pointer in catalog: {}. Latest Iceberg view root pointer: {}.",
            viewPath,
            existingRootPointer,
            latestRootPointer);
        return false;
      }
    } catch (NoSuchTableException e) {
      throw UserException.ioExceptionError(e)
          .message(String.format("View path '%s', not found.", viewPath))
          .buildSilently();
    }
    return true;
  }

  @Override
  public TableOperations createIcebergTableOperations(
      FileIO fileIO,
      IcebergTableIdentifier tableIdentifier,
      @Nullable String queryUserName,
      @Nullable String queryUserId) {
    if (!optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)) {
      throw new UnsupportedOperationException(
          "This operation is unsupported for Iceberg Catalog sources.");
    }

    List<String> dataset = ((IcebergCatalogTableIdentifier) tableIdentifier).getDataset();
    return getCatalogAccessor()
        .createIcebergTableOperations(fileIO, dataset, queryUserName, queryUserId);
  }

  public TableOperations createIcebergTableOperationsForCtas(
      FileIO fileIO,
      IcebergTableIdentifier tableIdentifier,
      BatchSchema batchSchema,
      @Nullable String userName,
      @Nullable String userId) {
    if (!optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)) {
      throw new UnsupportedOperationException(
          "This operation is unsupported for Iceberg Catalog sources.");
    }

    Schema schema = SchemaConverter.getBuilder().build().toIcebergSchema(batchSchema);
    List<String> dataset = ((IcebergCatalogTableIdentifier) tableIdentifier).getDataset();
    return getCatalogAccessor()
        .createIcebergTableOperationsForCtas(fileIO, dataset, schema, userName, userId);
  }

  @Override
  public FileIO createIcebergFileIO(
      FileSystem fs,
      OperatorContext context,
      List<String> dataset,
      String datasourcePluginUID,
      Long fileLength) {

    Configuration wrappedFsConf = fs.unwrap(org.apache.hadoop.fs.FileSystem.class).getConf();
    return new DremioFileIO(
        fs,
        context,
        dataset,
        datasourcePluginUID,
        fileLength,
        new IcebergCatalogFileSystemConfigurationAdapter(wrappedFsConf));
  }

  public DatasetFileSystemCache getHadoopFileSystemCache() {
    return hadoopFs;
  }

  @VisibleForTesting
  public void validateOnStart() {
    if (isExecutor) {
      // Executor only reads default options at startup time since it does not have access
      // to the KV store so there is no point validating the default options. We only
      // evaluate the options at coordinator.
      return;
    }

    // Don't let the plugin start if the feature flag is disabled
    if (!optionManager.getOption(getEnableOption())) {
      throw UserException.unsupportedError()
          .message(errorMessageWhenSupportKeyIsDisabled())
          .buildSilently();
    }
  }

  @Nonnull
  @Override
  public Optional<DatasetMetadataVerifyResult> verifyMetadata(
      DatasetHandle datasetHandle, MetadataVerifyRequest metadataVerifyRequest) {
    if (datasetHandle instanceof MetadataVerifyHandle) {
      return datasetHandle.unwrap(MetadataVerifyHandle.class).verifyMetadata(metadataVerifyRequest);
    }
    return Optional.empty();
  }

  @Override
  public View loadViewMetadata(TableIdentifier viewIdentifier, GetDatasetOption... options) {
    return getCatalogAccessor().loadView(viewIdentifier, options);
  }

  public abstract CatalogAccessor createCatalog(Configuration fsConf);

  public abstract BooleanValidator getEnableOption();

  public abstract boolean viewsEnabled();

  public abstract String errorMessageWhenSupportKeyIsDisabled();
}
