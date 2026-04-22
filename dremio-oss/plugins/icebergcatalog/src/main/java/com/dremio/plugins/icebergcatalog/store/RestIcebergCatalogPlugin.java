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

import static com.dremio.exec.catalog.CatalogOptions.RESTCATALOG_FOLDERS_SUPPORTED;
import static com.dremio.exec.catalog.CatalogOptions.RESTCATALOG_VIEWS_SUPPORTED;
import static com.dremio.exec.store.IcebergCatalogPluginOptions.RESTCATALOG_PLUGIN_ENABLED;
import static com.dremio.exec.store.IcebergCatalogPluginOptions.RESTCATALOG_PLUGIN_MUTABLE_ENABLED;
import static com.dremio.plugins.icebergcatalog.store.IcebergCatalogPluginUtils.NAMESPACE_SEPARATOR;

import com.dremio.catalog.exception.CatalogEntityAlreadyExistsException;
import com.dremio.catalog.exception.CatalogEntityForbiddenException;
import com.dremio.catalog.exception.CatalogEntityNotFoundException;
import com.dremio.catalog.exception.CatalogFolderNotEmptyException;
import com.dremio.catalog.exception.CatalogUnsupportedOperationException;
import com.dremio.catalog.exception.InvalidStorageUriException;
import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.catalog.model.CatalogFolder;
import com.dremio.catalog.model.ImmutableCatalogFolder;
import com.dremio.catalog.model.ResolvedVersionContext;
import com.dremio.common.exceptions.UserException;
import com.dremio.context.RequestContext;
import com.dremio.context.UserContext;
import com.dremio.exec.catalog.AlterTableOption;
import com.dremio.exec.catalog.CreateTableOptions;
import com.dremio.exec.catalog.FolderListing;
import com.dremio.exec.catalog.PluginSabotContext;
import com.dremio.exec.catalog.RollbackOption;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.catalog.TableMutationOptions;
import com.dremio.exec.catalog.conf.DefaultCtasFormatSelection;
import com.dremio.exec.catalog.conf.Property;
import com.dremio.exec.dotfile.View;
import com.dremio.exec.ops.IcebergMetrics;
import com.dremio.exec.physical.base.ViewOptions;
import com.dremio.exec.physical.base.WriterOptions;
import com.dremio.exec.planner.logical.CreateTableEntry;
import com.dremio.exec.planner.logical.ViewTable;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.ReferenceNotFoundException;
import com.dremio.exec.store.SchemaConfig;
import com.dremio.exec.store.dfs.CreateParquetTableEntry;
import com.dremio.exec.store.dfs.IcebergTableProps;
import com.dremio.exec.store.iceberg.IcebergFeatureManager;
import com.dremio.exec.store.iceberg.IcebergUtils;
import com.dremio.exec.store.iceberg.SchemaConverter;
import com.dremio.exec.store.iceberg.SupportsFsCreation;
import com.dremio.exec.store.iceberg.model.IcebergCommandType;
import com.dremio.exec.store.iceberg.model.IcebergModel;
import com.dremio.exec.store.iceberg.model.IcebergTableIdentifier;
import com.dremio.io.file.FileSystem;
import com.dremio.io.file.Path;
import com.dremio.options.OptionManager;
import com.dremio.options.TypeValidators.BooleanValidator;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.service.namespace.NamespaceAttribute;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import javax.inject.Provider;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.exceptions.NamespaceNotEmptyException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.exceptions.NoSuchViewException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.rest.RESTCatalog;
import org.apache.iceberg.types.Types;

public class RestIcebergCatalogPlugin extends IcebergCatalogPlugin {
  private final List<String> allowedNamespaces;
  private final boolean isRecursiveAllowedNamespaces;
  private final String restEndpoint;
  private final OptionManager optionManager;
  private final Provider<StoragePluginId> pluginIdProvider;
  private final List<Property> configPropertyList;
  private final String name;

  public RestIcebergCatalogPlugin(
      RestIcebergCatalogPluginConfig pluginConfig,
      PluginSabotContext sabotContext,
      String name,
      Provider<StoragePluginId> pluginIdProvider) {
    super(pluginConfig, sabotContext, name);
    this.pluginIdProvider = pluginIdProvider;
    this.allowedNamespaces = pluginConfig.allowedNamespaces;
    this.isRecursiveAllowedNamespaces = pluginConfig.isRecursiveAllowedNamespaces;
    this.restEndpoint = pluginConfig.getRestEndpointURI(sabotContext.getDremioConfig());
    this.optionManager = sabotContext.getOptionManager();
    this.configPropertyList = getConfigPropertyList(pluginConfig);
    this.name = name;
  }

  private static List<Property> getConfigPropertyList(RestIcebergCatalogPluginConfig pluginConfig) {
    List<Property> props = Lists.newArrayList();
    if (pluginConfig.propertyList != null) {
      props.addAll(pluginConfig.propertyList);
    }
    if (pluginConfig.secretPropertyList != null) {
      props.addAll(pluginConfig.secretPropertyList);
    }
    return props;
  }

  @Override
  public CatalogAccessor createCatalog(Configuration config) {
    try {
      return new IcebergRestCatalogAccessor(
          createRestCatalog(config),
          optionManager,
          getAllowedNamespaces(),
          isRecursiveAllowedNamespaces());
    } catch (Exception e) {
      throw UserException.connectionError(e)
          .message("Can't connect to %s catalog. %s", restCatalogImpl().getSimpleName(), e)
          .buildSilently();
    }
  }

  @Override
  public BooleanValidator getEnableOption() {
    return RESTCATALOG_PLUGIN_ENABLED;
  }

  @Override
  public boolean viewsEnabled() {
    return optionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED);
  }

  @Override
  public String errorMessageWhenSupportKeyIsDisabled() {
    return "Iceberg Catalog Source is not supported.";
  }

  @Override
  public Optional<CatalogFolder> createFolder(CatalogEntityKey key, @Nullable String storageUri)
      throws CatalogEntityAlreadyExistsException, CatalogEntityForbiddenException {
    if (!optionManager.getOption(RESTCATALOG_FOLDERS_SUPPORTED)) {
      throw new UnsupportedOperationException(
          "Iceberg Catalog sources do not support creating folders.");
    }

    Map<String, String> propertiesToCreate = new HashMap<>();
    if (storageUri != null) {
      // TODO: DX-99112 - Handle the case of a non-null blank storageUri.
      propertiesToCreate.put("location", storageUri);
    }
    try {
      Map<String, String> updatedProperties =
          getCatalogAccessor().createFolder(key.getKeyComponents(), propertiesToCreate);
      CatalogFolder catalogFolder =
          new ImmutableCatalogFolder.Builder()
              .setFullPath(key.getKeyComponents())
              .setStorageUri(updatedProperties.get("location"))
              .build();
      return Optional.of(catalogFolder);
    } catch (AlreadyExistsException e) {
      throw new CatalogEntityAlreadyExistsException(
          String.format("Folder [%s] already exists.", key.getKeyComponents().toString()), e);
    } catch (ForbiddenException e) {
      throw new CatalogEntityForbiddenException(
          String.format(
              "Storage URI [%s] at folder %s conflicts with other existing namespace",
              storageUri, key.getKeyComponents().toString()),
          e);
    }
  }

  @Override
  public Optional<CatalogFolder> updateFolder(CatalogEntityKey key, @Nullable String storageUri)
      throws CatalogEntityNotFoundException,
          InvalidStorageUriException,
          CatalogEntityForbiddenException {
    if (!optionManager.getOption(RESTCATALOG_FOLDERS_SUPPORTED)) {
      throw new UnsupportedOperationException(
          "Iceberg Catalog sources do not support updating folders.");
    }
    validateStorageUri(storageUri);
    Map<String, String> propertiesToUpdate = new HashMap<>();
    Set<String> propertiesToRemove = ImmutableSet.of();

    if (storageUri == null) {
      propertiesToRemove = ImmutableSet.of("location");
    } else {
      propertiesToUpdate.put("location", storageUri);
    }
    try {
      Map<String, String> updatedProperties =
          getCatalogAccessor()
              .updateFolder(key.getKeyComponents(), propertiesToUpdate, propertiesToRemove);
      CatalogFolder catalogFolder =
          new ImmutableCatalogFolder.Builder()
              .setFullPath(key.getKeyComponents())
              .setStorageUri(updatedProperties.get("location"))
              .build();
      return Optional.of(catalogFolder);
    } catch (NoSuchNamespaceException e) {
      throw new CatalogEntityNotFoundException(
          String.format("Folder [%s] does not exist.", key.getKeyComponents().toString()), e);
    } catch (ForbiddenException e) {
      throw new CatalogEntityForbiddenException(
          String.format(
              "Storage URI [%s] at folder %s conflicts with other existing namespace",
              storageUri, key.getKeyComponents().toString()),
          e);
    }
  }

  protected void validateStorageUri(String storageUriString) throws InvalidStorageUriException {
    if (storageUriString != null) {
      throw new UnsupportedOperationException("Storage URI is not supported for folders");
    }
  }

  @Override
  public void deleteFolder(CatalogEntityKey key)
      throws ReferenceNotFoundException,
          UserException,
          CatalogFolderNotEmptyException,
          CatalogEntityNotFoundException {
    if (!optionManager.getOption(RESTCATALOG_FOLDERS_SUPPORTED)) {
      throw new UnsupportedOperationException(
          "Iceberg Catalog sources do not support deleting folders.");
    }
    try {
      boolean isDeleted = getCatalogAccessor().dropFolder(key.getKeyComponents());
      if (!isDeleted) {
        // TODO: DX-99112 - Throw the proper checked exception here and see if this happens only
        // when the folder does not exist.
        throw UserException.validationError()
            .message(
                String.format(
                    "Can not delete folder [%s]. There was an Catalog error.",
                    key.getKeyComponents().toString()))
            .buildSilently();
      }
    } catch (NamespaceNotEmptyException e) {
      throw new CatalogFolderNotEmptyException(
          String.format(
              "Folder [%s] cannot be deleted as it contains content.",
              key.getKeyComponents().toString()),
          e);
    } catch (NoSuchNamespaceException e) {
      throw new CatalogEntityNotFoundException(
          String.format(
              "Can not delete folder [%s]. Folder does not exist.",
              key.getKeyComponents().toString()),
          e);
    }
  }

  protected Class<?> restCatalogImpl() {
    return RESTCatalog.class;
  }

  protected Map<String, String> buildCatalogProperties(Configuration config) {
    Map<String, String> properties = new HashMap<>();

    String catalogImplClassName = restCatalogImpl().getName();
    properties.put(CatalogProperties.CATALOG_IMPL, catalogImplClassName);
    properties.put(CatalogProperties.URI, getRestEndpoint());

    config.set(IcebergUtils.ENABLE_AZURE_ABFSS_SCHEME, "true");
    properties.put(IcebergUtils.ENABLE_AZURE_ABFSS_SCHEME, "true");

    for (Property p : configPropertyList) {
      config.set(p.name, p.value);
      properties.put(p.name, p.value);
    }

    return properties;
  }

  protected Supplier<Catalog> createRestCatalog(Configuration config) {
    return () ->
        CatalogUtil.loadCatalog(
            restCatalogImpl().getName(), catalogName(), buildCatalogProperties(config), config);
  }

  protected String catalogName() {
    return null;
  }

  protected List<String> getAllowedNamespaces() {
    return allowedNamespaces;
  }

  protected boolean isRecursiveAllowedNamespaces() {
    return isRecursiveAllowedNamespaces;
  }

  protected String getRestEndpoint() {
    return restEndpoint;
  }

  // SupportsIcebergMutablePlugin Methods - START
  @Override
  public IcebergModel getIcebergModel(
      IcebergTableProps tableProps,
      String userName,
      OperatorContext context,
      FileIO fileIO,
      @org.jetbrains.annotations.Nullable String userId) {
    if (!optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)) {
      throw new UnsupportedOperationException(
          "This operation is unsupported for Iceberg Catalog sources.");
    }

    // TODO: DX-99790 - rename tableProps.getDatabaseName() to use namespace nomenclature
    List<String> dataset = new ArrayList<>();
    dataset.add(getName());
    dataset.addAll(List.of(tableProps.getDatabaseName().split(NAMESPACE_SEPARATOR)));
    dataset.add(tableProps.getTableName());
    return new IcebergCatalogModel(
        null, getFsConfCopy(), fileIO, context, null, this, dataset, userName, userId);
  }

  @VisibleForTesting
  IcebergModel getIcebergModel(String location, List<String> dataset, String userName) {
    final FileIO fileIO;
    try {
      Builder builder =
          SupportsFsCreation.builder().filePath(location).dataset(dataset).userName(userName);
      FileSystem fs = createFS(builder);
      fileIO = createIcebergFileIO(fs, null, dataset, null, null);
    } catch (IOException e) {
      throw UserException.validationError(e)
          .message("Failure creating File System instance for path %s", location)
          .buildSilently();
    }
    String userId =
        Optional.ofNullable(RequestContext.current().get(UserContext.CTX_KEY))
            .map(UserContext::getUserId)
            .orElse(null);

    return new IcebergCatalogModel(
        null, getFsConfCopy(), fileIO, null, null, this, dataset, userName, userId);
  }

  @Override
  public void createEmptyTable(
      NamespaceKey tableSchemaPath,
      SchemaConfig schemaConfig,
      BatchSchema batchSchema,
      WriterOptions writerOptions) {
    if (!optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)) {
      throw new UnsupportedOperationException(
          "This operation is unsupported for Iceberg Catalog sources.");
    }

    Schema schema = SchemaConverter.getBuilder().build().toIcebergSchema(batchSchema);
    SortOrder sortOrder = writerOptions.getDeserializedSortOrder();
    PartitionSpec partitionSpec = writerOptions.getPartitionSpec();
    if (partitionSpec == null) {
      partitionSpec =
          IcebergUtils.getIcebergPartitionSpec(
              batchSchema, writerOptions.getPartitionColumns(), schema);
    }
    Map<String, String> tableProperties =
        Optional.ofNullable(
                writerOptions
                    .getTableFormatOptions()
                    .getIcebergSpecificOptions()
                    .getIcebergTableProps())
            .map(IcebergTableProps::getTableProperties)
            .orElse(Collections.emptyMap());

    List<String> tablePathComponents = tableSchemaPath.getPathComponents();
    @Nullable
    String tableLocation =
        getTableLocationUri(getNewTableLocationFromCatalog(writerOptions, tablePathComponents));
    getCatalogAccessor()
        .createTable(
            tablePathComponents, schema, partitionSpec, sortOrder, tableLocation, tableProperties);

    IcebergMetrics.countFormatVersion(
        IcebergFeatureManager.getIcebergFormatVersion(tableProperties).getValue(),
        IcebergMetrics.OperationType.CREATE);
  }

  @Override
  public CreateTableEntry createNewTable(
      NamespaceKey tableSchemaPath,
      SchemaConfig schemaConfig,
      IcebergTableProps icebergTableProps,
      WriterOptions writerOptions,
      Map<String, Object> storageOptions,
      CreateTableOptions createTableOptions) {
    // TODO: DX-99828 & DX-99788 - related tech debt
    if (!optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)) {
      throw new UnsupportedOperationException(
          "This operation is unsupported for Iceberg Catalog sources.");
    }

    if (icebergTableProps == null
        || storageOptions != null
            && storageOptions.containsKey("type")
            && !storageOptions.get("type").equals("iceberg")) {
      throw new UnsupportedOperationException(
          "Iceberg Catalog sources can only be used to manage Iceberg tables.");
    }

    final boolean isCTAS = icebergTableProps.getIcebergOpType().equals(IcebergCommandType.CREATE);
    final List<String> dataset = tableSchemaPath.getPathComponents();
    String tableFolderLocation;

    if (isCTAS) {
      tableFolderLocation = getNewTableLocationFromCatalog(writerOptions, dataset);
      if (tableFolderLocation == null) {
        throw UserException.validationError()
            .message(
                "A table must be created within a valid folder. Please create a folder before creating a table.")
            .buildSilently();
      }
    } else {
      tableFolderLocation =
          getExistingTableLocationFromCatalog(tableSchemaPath.getPathComponents());
    }
    if (!tableFolderLocation.endsWith(Path.SEPARATOR)) {
      tableFolderLocation = tableFolderLocation.concat(Path.SEPARATOR);
    }

    String namespaceIdentifier =
        String.join(NAMESPACE_SEPARATOR, tableSchemaPath.getParent().getPathWithoutRoot());

    // TODO: DX-99832 - investigate the pattern here/elsewhere around recreating icebergTableProps
    icebergTableProps = new IcebergTableProps(icebergTableProps);
    icebergTableProps.setTableLocation(tableFolderLocation);
    icebergTableProps.setTableName(tableSchemaPath.getName());
    icebergTableProps.setDatabaseName(namespaceIdentifier);

    String tableDataFolderPath =
        Path.of(tableFolderLocation).resolve("data/" + icebergTableProps.getUuid()).toString();

    String userId =
        Optional.ofNullable(RequestContext.current().get(UserContext.CTX_KEY))
            .map(UserContext::getUserId)
            .orElse(null);

    return new CreateParquetTableEntry(
        schemaConfig.getUserName(),
        userId,
        this,
        tableDataFolderPath,
        icebergTableProps,
        writerOptions,
        tableSchemaPath);
  }

  @Override
  public void dropTable(
      NamespaceKey tableSchemaPath,
      SchemaConfig schemaConfig,
      TableMutationOptions tableMutationOptions)
      throws CatalogEntityNotFoundException {
    if (!optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)) {
      throw new UnsupportedOperationException(
          "This operation is unsupported for Iceberg Catalog sources.");
    }

    try {
      getCatalogAccessor().dropTable(tableSchemaPath.getPathComponents());
    } catch (NoSuchTableException e) {
      throw new CatalogEntityNotFoundException(
          String.format("Table %s not found", tableSchemaPath.getName()));
    }
  }

  @Override
  public void alterTable(
      NamespaceKey tableSchemaPath,
      DatasetConfig datasetConfig,
      AlterTableOption alterTableOption,
      SchemaConfig schemaConfig,
      TableMutationOptions tableMutationOptions) {
    if (!optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)) {
      throw new UnsupportedOperationException(
          "This operation is unsupported for Iceberg Catalog sources.");
    }

    final List<String> dataset = tableSchemaPath.getPathComponents();
    final String tableLocation = getExistingTableLocationFromCatalog(dataset);

    final IcebergModel icebergCatalogModel =
        getIcebergModel(tableLocation, dataset, schemaConfig.getUserName());
    icebergCatalogModel.alterTable(
        icebergCatalogModel.getTableIdentifier(tableLocation), alterTableOption);
  }

  @Override
  public void truncateTable(
      NamespaceKey tableSchemaPath,
      SchemaConfig schemaConfig,
      TableMutationOptions tableMutationOptions) {
    if (!optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)) {
      throw new UnsupportedOperationException(
          "This operation is unsupported for Iceberg Catalog sources.");
    }

    final List<String> dataset = tableSchemaPath.getPathComponents();
    final String tableLocation = getExistingTableLocationFromCatalog(dataset);

    final IcebergModel icebergCatalogModel =
        getIcebergModel(tableLocation, dataset, schemaConfig.getUserName());
    icebergCatalogModel.truncateTable(icebergCatalogModel.getTableIdentifier(tableLocation));
  }

  @Override
  public void rollbackTable(
      NamespaceKey tableSchemaPath,
      DatasetConfig datasetConfig,
      SchemaConfig schemaConfig,
      RollbackOption rollbackOption,
      TableMutationOptions tableMutationOptions) {
    if (!optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)) {
      throw new UnsupportedOperationException(
          "This operation is unsupported for Iceberg Catalog sources.");
    }

    final List<String> dataset = tableSchemaPath.getPathComponents();
    final String tableLocation = getExistingTableLocationFromCatalog(dataset);

    final IcebergModel icebergCatalogModel =
        getIcebergModel(tableLocation, dataset, schemaConfig.getUserName());
    icebergCatalogModel.rollbackTable(
        icebergCatalogModel.getTableIdentifier(tableLocation), rollbackOption);
  }

  @Override
  public void addColumns(
      NamespaceKey tableSchemaPath,
      DatasetConfig datasetConfig,
      SchemaConfig schemaConfig,
      List<Field> columnsToAdd,
      TableMutationOptions tableMutationOptions) {
    if (!optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)) {
      throw new UnsupportedOperationException(
          "This operation is unsupported for Iceberg Catalog sources.");
    }

    final List<String> dataset = tableSchemaPath.getPathComponents();
    final String tableLocation = getExistingTableLocationFromCatalog(dataset);
    final SchemaConverter schemaConverter = SchemaConverter.getBuilder().build();
    final List<Types.NestedField> icebergFields = schemaConverter.toIcebergFields(columnsToAdd);

    final IcebergModel icebergCatalogModel =
        getIcebergModel(tableLocation, dataset, schemaConfig.getUserName());
    icebergCatalogModel.addColumns(
        icebergCatalogModel.getTableIdentifier(tableLocation), icebergFields);
  }

  @Override
  public void dropColumn(
      NamespaceKey tableSchemaPath,
      DatasetConfig datasetConfig,
      SchemaConfig schemaConfig,
      String columnToDrop,
      TableMutationOptions tableMutationOptions) {
    if (!optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)) {
      throw new UnsupportedOperationException(
          "This operation is unsupported for Iceberg Catalog sources.");
    }

    final List<String> dataset = tableSchemaPath.getPathComponents();
    final String tableLocation = getExistingTableLocationFromCatalog(dataset);

    final IcebergModel icebergCatalogModel =
        getIcebergModel(tableLocation, dataset, schemaConfig.getUserName());
    icebergCatalogModel.dropColumn(
        icebergCatalogModel.getTableIdentifier(tableLocation), columnToDrop);
  }

  @Override
  public void changeColumn(
      NamespaceKey tableSchemaPath,
      DatasetConfig datasetConfig,
      SchemaConfig schemaConfig,
      String columnToChange,
      Field fieldFromSqlColDeclaration,
      TableMutationOptions tableMutationOptions) {
    if (!optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)) {
      throw new UnsupportedOperationException(
          "This operation is unsupported for Iceberg Catalog sources.");
    }

    final List<String> dataset = tableSchemaPath.getPathComponents();
    final String tableLocation = getExistingTableLocationFromCatalog(dataset);

    final IcebergModel icebergCatalogModel =
        getIcebergModel(tableLocation, dataset, schemaConfig.getUserName());
    icebergCatalogModel.changeColumn(
        icebergCatalogModel.getTableIdentifier(tableLocation),
        columnToChange,
        fieldFromSqlColDeclaration);
  }

  @Override
  public void addPrimaryKey(
      NamespaceKey table,
      DatasetConfig datasetConfig,
      SchemaConfig schemaConfig,
      List<Field> columns,
      ResolvedVersionContext versionContext) {
    if (!optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)) {
      throw new UnsupportedOperationException(
          "This operation is unsupported for Iceberg Catalog sources.");
    }

    // TODO: DX-99658 - This method currently stores primaryKeys as the iceberg spec schema property
    //  identifier-field-ids. This is in contrast to Dremio's table property dremio.primary_key.
    final List<String> dataset = table.getPathComponents();
    final String tableLocation = getExistingTableLocationFromCatalog(dataset);
    final IcebergModel icebergCatalogModel =
        getIcebergModel(tableLocation, dataset, schemaConfig.getUserName());

    icebergCatalogModel.updatePrimaryKey(
        icebergCatalogModel.getTableIdentifier(tableLocation), columns);
  }

  @Override
  public void dropPrimaryKey(
      NamespaceKey table,
      DatasetConfig datasetConfig,
      SchemaConfig schemaConfig,
      ResolvedVersionContext versionContext) {
    if (!optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)) {
      throw new UnsupportedOperationException(
          "This operation is unsupported for Iceberg Catalog sources.");
    }

    final List<String> dataset = table.getPathComponents();
    final String tableLocation = getExistingTableLocationFromCatalog(dataset);
    final IcebergModel icebergCatalogModel =
        getIcebergModel(tableLocation, dataset, schemaConfig.getUserName());

    icebergCatalogModel.updatePrimaryKey(
        icebergCatalogModel.getTableIdentifier(tableLocation), Collections.emptyList());
  }

  @Override
  public List<String> getPrimaryKey(
      NamespaceKey table,
      DatasetConfig datasetConfig,
      SchemaConfig schemaConfig,
      ResolvedVersionContext versionContext,
      boolean saveInKvStore) {
    if (!optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)) {
      throw new UnsupportedOperationException(
          "This operation is unsupported for Iceberg Catalog sources.");
    }

    final List<String> dataset = table.getPathComponents();
    final Schema tableSchema = getCatalogAccessor().getTableMetadata(dataset).schema();

    // TODO: DX-99658 - This method returns lowercase field names in order to match Dremio's
    //  dremio.primary_key implementation.  See IcebergUtils.getPrimaryKeyFromPropertyValue().
    return tableSchema.identifierFieldNames().stream()
        .map(columnName -> columnName.toLowerCase(Locale.ROOT))
        .collect(Collectors.toList());
  }

  @Override
  public StoragePluginId getId() {
    if (!optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)) {
      throw new UnsupportedOperationException(
          "This operation is unsupported for Iceberg Catalog sources.");
    }

    return pluginIdProvider.get();
  }

  @Override
  public void alterSortOrder(
      NamespaceKey table,
      DatasetConfig datasetConfig,
      BatchSchema batchSchema,
      SchemaConfig schemaConfig,
      List<String> sortOrderColumns,
      TableMutationOptions tableMutationOptions) {
    if (!optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)) {
      throw new UnsupportedOperationException(
          "This operation is unsupported for Iceberg Catalog sources.");
    }

    final List<String> dataset = table.getPathComponents();
    final String tableLocation = getExistingTableLocationFromCatalog(dataset);
    final IcebergModel icebergCatalogModel =
        getIcebergModel(tableLocation, dataset, schemaConfig.getUserName());

    icebergCatalogModel.replaceSortOrder(
        icebergCatalogModel.getTableIdentifier(tableLocation), sortOrderColumns);
  }

  @Override
  public void updateTableProperties(
      NamespaceKey table,
      DatasetConfig datasetConfig,
      BatchSchema schema,
      SchemaConfig schemaConfig,
      Map<String, String> tableProperties,
      TableMutationOptions tableMutationOptions,
      boolean isRemove) {
    if (!optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)) {
      throw new UnsupportedOperationException(
          "This operation is unsupported for Iceberg Catalog sources.");
    }

    final List<String> dataset = table.getPathComponents();
    final String tableLocation = getExistingTableLocationFromCatalog(dataset);
    final IcebergModel icebergCatalogModel =
        getIcebergModel(tableLocation, dataset, schemaConfig.getUserName());
    final IcebergTableIdentifier icebergTableIdentifier =
        icebergCatalogModel.getTableIdentifier(tableLocation);

    if (isRemove) {
      final List<String> propertyNameList = new ArrayList<>(tableProperties.keySet());
      icebergCatalogModel.removeTableProperties(icebergTableIdentifier, propertyNameList);
    } else {
      icebergCatalogModel.updateTableProperties(icebergTableIdentifier, tableProperties);
    }
  }

  @Override
  public String getDefaultCtasFormat() {
    if (!optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)) {
      throw new UnsupportedOperationException(
          "This operation is unsupported for Iceberg Catalog sources.");
    }

    return DefaultCtasFormatSelection.ICEBERG.getDefaultCtasFormat();
  }

  // SupportsIcebergMutablePlugin Methods - END

  protected @Nullable String getTableLocationUri(@Nullable String tableLocation) {
    return tableLocation != null && !tableLocation.isBlank() ? tableLocation : null;
  }

  private @Nullable String getNewTableLocationFromCatalog(
      WriterOptions writerOptions, List<String> dataset) {
    // Passed in by user through the LOCATION SQL parameter
    if (StringUtils.isNotBlank(writerOptions.getTableLocation())) {
      return writerOptions.getTableLocation();
    }

    return getCatalogAccessor().getDatasetLocationFromExistingNamespaceLocationUri(dataset);
  }

  private String getExistingTableLocationFromCatalog(List<String> dataset) {
    return getCatalogAccessor().getTableMetadata(dataset).location();
  }

  @Override
  public boolean createOrUpdateView(
      NamespaceKey tableSchemaPath,
      SchemaConfig schemaConfig,
      View view,
      ViewOptions viewOptions,
      NamespaceAttribute... attributes)
      throws IOException,
          CatalogUnsupportedOperationException,
          CatalogEntityAlreadyExistsException,
          CatalogEntityNotFoundException {
    // TODO(DX-99994): we might need to change the function return a representation of view instead
    // of boolean.
    if (!viewsEnabled()) {
      throw new UnsupportedOperationException(
          "This operation is unsupported for Iceberg Catalog sources.");
    }
    Preconditions.checkNotNull(viewOptions, "view options must present");
    Preconditions.checkNotNull(viewOptions.getBatchSchema(), "BatchSchema must present");
    SchemaConverter schemaConverter = SchemaConverter.getBuilder().build();
    Schema schema = schemaConverter.toIcebergSchema(viewOptions.getBatchSchema());
    if (viewOptions.isViewCreate()) {
      return createView(tableSchemaPath, view, schema);
    }
    return updateView(tableSchemaPath, view, schema);
  }

  private boolean createView(NamespaceKey tableSchemaPath, View view, Schema schema)
      throws CatalogEntityAlreadyExistsException, CatalogEntityNotFoundException {
    try {
      getCatalogAccessor()
          .createView(
              tableSchemaPath.getPathComponents(),
              null,
              view.getWorkspaceSchemaPath(),
              schema,
              view.getSql());
      return true;
    } catch (NoSuchNamespaceException e) {
      throw new CatalogEntityNotFoundException(
          "Please create the folders in the path before creating the view. "
              + "One or more folders are missing in the path.",
          e);
    } catch (AlreadyExistsException e) {
      throw new CatalogEntityAlreadyExistsException(
          String.format("View already exists: %s", tableSchemaPath.getName()), e);
    }
  }

  private boolean updateView(NamespaceKey tableSchemaPath, View view, Schema schema)
      throws CatalogEntityNotFoundException {
    try {
      getCatalogAccessor()
          .updateView(
              tableSchemaPath.getPathComponents(),
              null,
              view.getWorkspaceSchemaPath(),
              schema,
              view.getSql());
      return true;
    } catch (NoSuchViewException e) {
      throw new CatalogEntityNotFoundException(
          String.format("View %s cannot be found", tableSchemaPath.getName()));
    }
  }

  @Override
  public void dropView(
      NamespaceKey tableSchemaPath, ViewOptions viewOptions, SchemaConfig schemaConfig)
      throws IOException, CatalogEntityNotFoundException {
    if (!viewsEnabled()) {
      throw new UnsupportedOperationException(
          "This operation is unsupported for Iceberg Catalog sources.");
    }
    try {
      getCatalogAccessor().dropView(tableSchemaPath.getPathComponents());
    } catch (NoSuchViewException e) {
      throw new CatalogEntityNotFoundException(
          String.format("View %s cannot be found", tableSchemaPath.getName()));
    }
  }

  @Override
  public Optional<ViewTable> getView(List<String> tableSchemaPath, SchemaConfig schemaConfig) {
    // TODO(DX-100232): Deprecate getView from SupportsReadingViews
    return Optional.empty();
  }

  @Override
  public FolderListing getFolderListing() {
    if (!optionManager.getOption(RESTCATALOG_FOLDERS_SUPPORTED)) {
      throw new UnsupportedOperationException(
          "This operation is unsupported for Iceberg Catalog sources.");
    }
    return getFolderListingFromIterator(name, getCatalogAccessor().getFolderStream());
  }

  private static FolderListing getFolderListingFromIterator(
      String name, Stream<IcebergNamespaceWithProperties> folderStream) {
    return folderStream.map(
            folder ->
                new ImmutableCatalogFolder.Builder()
                    .setFullPath(getFullPath(name, folder.getNamespace().levels()))
                    .setStorageUri(folder.getProperties().get("location"))
                    .build())
        ::iterator;
  }

  private static List<String> getFullPath(String name, String[] namespaceLevels) {
    List<String> fullPath = new ArrayList<>();
    fullPath.add(name);
    fullPath.addAll(Arrays.asList(namespaceLevels));
    return fullPath;
  }
}
