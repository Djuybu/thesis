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

import static com.dremio.exec.catalog.CatalogOptions.RESTCATALOG_VIEWS_SUPPORTED;
import static com.dremio.exec.store.IcebergCatalogPluginOptions.RESTCATALOG_ALLOWED_NS_SEPARATOR;
import static com.dremio.exec.store.IcebergCatalogPluginOptions.RESTCATALOG_PLUGIN_TABLE_CACHE_ENABLED;
import static com.dremio.exec.store.IcebergCatalogPluginOptions.RESTCATALOG_PLUGIN_TABLE_CACHE_EXPIRE_AFTER_WRITE_SECONDS;
import static com.dremio.exec.store.IcebergCatalogPluginOptions.RESTCATALOG_PLUGIN_TABLE_CACHE_SIZE_ITEMS;
import static com.dremio.exec.store.IcebergCatalogPluginOptions.RESTCATALOG_PLUGIN_VIEW_CACHE_ENABLED;

import com.dremio.common.exceptions.UserException;
import com.dremio.common.utils.PathUtils;
import com.dremio.connector.metadata.DatasetHandle;
import com.dremio.connector.metadata.DatasetHandleListing;
import com.dremio.connector.metadata.DatasetMetadata;
import com.dremio.connector.metadata.EntityPath;
import com.dremio.connector.metadata.GetDatasetOption;
import com.dremio.connector.metadata.GetMetadataOption;
import com.dremio.connector.metadata.ListPartitionChunkOption;
import com.dremio.connector.metadata.PartitionChunkListing;
import com.dremio.connector.metadata.options.ForceUpdateOption;
import com.dremio.connector.metadata.options.TimeTravelOption;
import com.dremio.context.RequestContext;
import com.dremio.context.UserContext;
import com.dremio.exec.store.iceberg.DremioFileIO;
import com.dremio.exec.store.iceberg.IcebergViewMetadata;
import com.dremio.exec.store.iceberg.SupportsFsCreation;
import com.dremio.exec.store.iceberg.SupportsIcebergRootPointer;
import com.dremio.exec.store.iceberg.TimeTravelProcessors;
import com.dremio.exec.store.iceberg.model.DremioBaseTable;
import com.dremio.options.OptionManager;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.apache.iceberg.BaseTransaction;
import org.apache.iceberg.HasTableOperations;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SupportsNamespaces;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.catalog.ViewCatalog;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.NamespaceNotEmptyException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchViewException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.rest.DremioRESTTableOperations;
import org.apache.iceberg.view.BaseView;
import org.apache.iceberg.view.View;
import org.apache.iceberg.view.ViewBuilder;
import org.apache.iceberg.view.ViewMetadata;

public abstract class AbstractRestCatalogAccessor implements CatalogAccessor {

  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(AbstractRestCatalogAccessor.class);

  private final OptionManager optionsManager;
  private final LoadingCache<CatalogAccessorTableCacheKey, Table> tableCache;
  private final LoadingCache<CatalogAccessorTableCacheKey, View> viewCache;
  private final Supplier<Catalog> icebergCatalogSupplier;
  private final Set<Namespace> allowedNamespaces;
  private final boolean isRecursiveAllowedNamespaces;
  public static final String DEFAULT_BASE_LOCATION = "default-base-location";

  public AbstractRestCatalogAccessor(
      Supplier<Catalog> catalogSupplier,
      OptionManager optionsManager,
      @Nullable List<String> allowedNamespaces,
      boolean isRecursiveAllowedNamespaces) {
    this.optionsManager = optionsManager;
    this.icebergCatalogSupplier = catalogSupplier;
    this.tableCache =
        Caffeine.newBuilder()
            .maximumSize(optionsManager.getOption(RESTCATALOG_PLUGIN_TABLE_CACHE_SIZE_ITEMS))
            .expireAfterWrite(
                optionsManager.getOption(RESTCATALOG_PLUGIN_TABLE_CACHE_EXPIRE_AFTER_WRITE_SECONDS),
                TimeUnit.SECONDS)
            .build(
                catalogAccessorTableCacheKey -> {
                  if (logger.isDebugEnabled()) {
                    logger.debug(
                        "Catalog table cache: cache miss for user {} on table {}",
                        catalogAccessorTableCacheKey.userId(),
                        catalogAccessorTableCacheKey.tableIdentifier());
                  }
                  return getCatalog().loadTable(catalogAccessorTableCacheKey.tableIdentifier());
                });
    this.viewCache =
        Caffeine.newBuilder()
            .maximumSize(optionsManager.getOption(RESTCATALOG_PLUGIN_TABLE_CACHE_SIZE_ITEMS))
            .expireAfterWrite(
                optionsManager.getOption(RESTCATALOG_PLUGIN_TABLE_CACHE_EXPIRE_AFTER_WRITE_SECONDS),
                TimeUnit.SECONDS)
            .build(
                userViewIdentifier -> {
                  if (logger.isDebugEnabled()) {
                    logger.debug(
                        "Catalog view cache: cache miss for user {} on view {}",
                        userViewIdentifier.userId(),
                        userViewIdentifier.tableIdentifier());
                  }
                  return ((ViewCatalog) getCatalog())
                      .loadView(userViewIdentifier.tableIdentifier());
                });
    if (allowedNamespaces != null) {
      String separator = optionsManager.getOption(RESTCATALOG_ALLOWED_NS_SEPARATOR);
      this.allowedNamespaces =
          allowedNamespaces.stream()
              .filter(ns -> !ns.isEmpty())
              .map(s -> Namespace.of(s.split(separator)))
              .collect(Collectors.toSet());
      this.isRecursiveAllowedNamespaces = isRecursiveAllowedNamespaces;
    } else {
      this.allowedNamespaces = Sets.newHashSet(Namespace.empty());
      this.isRecursiveAllowedNamespaces = true;
    }
  }

  protected Catalog getCatalog() {
    return icebergCatalogSupplier.get();
  }

  @VisibleForTesting
  Table loadTable(TableIdentifier tableIdentifier, GetDatasetOption... options) {
    if (optionsManager.getOption(RESTCATALOG_PLUGIN_TABLE_CACHE_ENABLED)) {
      UserContext userContext = RequestContext.current().get(UserContext.CTX_KEY);
      if (userContext == null) {
        logger.warn("Missing user context when loading table");
      }
      String userId = userContext == null ? "" : userContext.getUserId();
      CatalogAccessorTableCacheKey catalogAccessorTableCacheKey =
          new CatalogAccessorTableCacheKey(userId, tableIdentifier);
      if (ForceUpdateOption.isForceUpdate(options)) {
        invalidateTableCacheForAllUsers(tableIdentifier);
      }
      return tableCache.get(catalogAccessorTableCacheKey);
    } else {
      return getCatalog().loadTable(tableIdentifier);
    }
  }

  @VisibleForTesting
  @Override
  public View loadView(TableIdentifier tableIdentifier, GetDatasetOption... options) {
    if (!viewsEnabled()) {
      throw UserException.unsupportedError()
          .message("Views are not supported in this catalog.")
          .buildSilently();
    }
    if (optionsManager.getOption(RESTCATALOG_PLUGIN_VIEW_CACHE_ENABLED)) {
      UserContext userContext = RequestContext.current().get(UserContext.CTX_KEY);
      if (userContext == null) {
        logger.warn("Missing user context when loading view");
      }
      String userId = userContext == null ? "" : userContext.getUserId();
      CatalogAccessorTableCacheKey catalogAccessorTableCacheKey =
          new CatalogAccessorTableCacheKey(userId, tableIdentifier);
      if (ForceUpdateOption.isForceUpdate(options)) {
        invalidateViewCacheForAllUsers(tableIdentifier);
      }
      return viewCache.get(catalogAccessorTableCacheKey);
    } else {
      return ((ViewCatalog) getCatalog()).loadView(tableIdentifier);
    }
  }

  @Override
  public void checkState() throws Exception {
    try {
      checkStateInternal();
    } catch (Exception e) {
      tableCache.invalidateAll();
      viewCache.invalidateAll();
      throw e;
    }
  }

  @Override
  public Set<TableIdentifier> listDatasetIdentifiers(List<String> pathWithRootName) {
    if (pathWithRootName.isEmpty()) {
      logger.error("Received empty path to list");
      throw new IllegalArgumentException("Path to list must not be empty");
    }
    Namespace namespaceToListFrom = namespaceFromPath(pathWithRootName);
    if (allowedNamespaces.equals(ImmutableSet.of(Namespace.empty()))
        || allowedNamespaces.contains(namespaceToListFrom)) {
      return Stream.concat(
              streamTables(
                  getCatalog(), ImmutableSet.of(namespaceToListFrom), isRecursiveAllowedNamespaces),
              streamViews(
                  getCatalog(), ImmutableSet.of(namespaceToListFrom), isRecursiveAllowedNamespaces))
          .collect(Collectors.toSet());
    }
    return ImmutableSet.of();
  }

  @Override
  public DatasetHandleListing listDatasetHandles(
      String rootName, SupportsIcebergRootPointer plugin) {
    Stream<DatasetHandle> tableStream =
        streamTables(getCatalog(), allowedNamespaces, isRecursiveAllowedNamespaces)
            .map(
                tableIdentifier -> {
                  List<String> dataset = new ArrayList<>();
                  Collections.addAll(dataset, rootName);
                  Collections.addAll(dataset, tableIdentifier.namespace().levels());
                  Collections.addAll(dataset, tableIdentifier.name());
                  return getTableHandleInternal(dataset, tableIdentifier, plugin);
                });
    Stream<DatasetHandle> viewStream =
        viewsEnabled()
            ? streamViews(getCatalog(), allowedNamespaces, isRecursiveAllowedNamespaces)
                .map(
                    viewIdentifier -> {
                      List<String> dataset = new ArrayList<>();
                      Collections.addAll(dataset, rootName);
                      Collections.addAll(dataset, viewIdentifier.namespace().levels());
                      Collections.addAll(dataset, viewIdentifier.name());
                      return getViewHandleInternal(dataset, viewIdentifier, plugin);
                    })
            : Stream.empty();

    return Stream.concat(tableStream, viewStream)::iterator;
  }

  @VisibleForTesting
  Stream<TableIdentifier> streamTables(
      Catalog catalog, Set<Namespace> allowedNamespaces, boolean isRecursiveAllowedNamespaces) {
    if (!isRecursiveAllowedNamespaces) {
      // we basically have a fixed set of namespaces to look for tables in
      return allowedNamespaces.stream().flatMap(ns -> streamCatalogTables(catalog, ns));
    } else {
      // we have a set of namespaces to start a recursive NS discovery from
      return allowedNamespaces.stream().flatMap(ns -> streamTablesRecursive(catalog, ns));
    }
  }

  private Stream<TableIdentifier> streamTablesRecursive(
      Catalog catalogInstance, Namespace namespace) {
    return Stream.concat(
        streamCatalogNamespaces(catalogInstance, namespace)
            .flatMap(ns -> streamTablesRecursive(catalogInstance, ns)),
        streamCatalogTables(catalogInstance, namespace));
  }

  private static Stream<Namespace> streamCatalogNamespaces(
      Catalog catalogInstance, Namespace root) {
    try {
      return ((SupportsNamespaces) catalogInstance).listNamespaces(root).stream();
    } catch (Exception ex) {
      logger.error("Error listing namespace {}", root.toString(), ex);
    }
    return Stream.empty();
  }

  protected Stream<TableIdentifier> streamCatalogTables(
      Catalog catalogInstance, Namespace namespace) {
    try {
      return catalogInstance.listTables(namespace).stream();
    } catch (Exception ex) {
      if (logger.isDebugEnabled()) {
        logger.debug("Error listing tables in namespace {}", namespace.toString(), ex);
      }
    }
    return Stream.empty();
  }

  @VisibleForTesting
  Stream<TableIdentifier> streamViews(
      Catalog catalog, Set<Namespace> allowedNamespaces, boolean isRecursiveAllowedNamespaces) {
    if (!isRecursiveAllowedNamespaces) {
      // we basically have a fixed set of namespaces to look for tables in
      return allowedNamespaces.stream().flatMap(ns -> streamCatalogViews(catalog, ns));
    } else {
      // we have a set of namespaces to start a recursive NS discovery from
      return allowedNamespaces.stream().flatMap(ns -> streamViewsRecursive(catalog, ns));
    }
  }

  private Stream<TableIdentifier> streamViewsRecursive(
      Catalog catalogInstance, Namespace namespace) {
    return Stream.concat(
        streamCatalogNamespaces(catalogInstance, namespace)
            .flatMap(ns -> streamViewsRecursive(catalogInstance, ns)),
        streamCatalogViews(catalogInstance, namespace));
  }

  protected Stream<TableIdentifier> streamCatalogViews(
      Catalog catalogInstance, Namespace namespace) {
    try {
      return ((ViewCatalog) catalogInstance).listViews(namespace).stream();
    } catch (Exception ex) {
      if (logger.isDebugEnabled()) {
        logger.debug("Error listing views in namespace {}", namespace.toString(), ex);
      }
    }
    return Stream.empty();
  }

  @VisibleForTesting
  DatasetHandle getViewHandleInternal(
      List<String> viewPath,
      TableIdentifier viewIdentifier,
      SupportsIcebergRootPointer plugin,
      GetDatasetOption... options) {
    return new IcebergCatalogViewProvider(
        new EntityPath(viewPath), () -> plugin.loadViewMetadata(viewIdentifier, options));
  }

  @Override
  public Optional<DatasetHandle> getDatasetHandle(
      List<String> dataset, SupportsIcebergRootPointer plugin, GetDatasetOption... options) {
    TableIdentifier tableIdentifier = tableIdentifierFromDataset(dataset);
    if (getCatalog().tableExists(tableIdentifier)) {
      return Optional.of(getTableHandleInternal(dataset, tableIdentifier, plugin, options));
    }

    if (viewsEnabled() && ((ViewCatalog) getCatalog()).viewExists(tableIdentifier)) {
      return Optional.of(getViewHandleInternal(dataset, tableIdentifier, plugin, options));
    }

    logger.warn("DatasetHandle '{}' not found - table or view not found.", dataset);
    return Optional.empty();
  }

  @VisibleForTesting
  public DatasetHandle getTableHandleInternal(
      List<String> dataset,
      TableIdentifier tableIdentifier,
      SupportsIcebergRootPointer plugin,
      GetDatasetOption... options) {
    TimeTravelOption.TimeTravelRequest timeTravelRequest =
        Optional.ofNullable(TimeTravelOption.getTimeTravelOption(options))
            .map(TimeTravelOption::getTimeTravelRequest)
            .orElse(null);
    return new IcebergCatalogTableProvider(
        new EntityPath(dataset),
        () -> {
          Table baseTable = loadTable(tableIdentifier, options);
          // RESTSessionCatalog will provide a BaseTable for us with RESTTableOperations and
          // org.apache.iceberg.io.ResolvingFileIO as IO. We replace this with DremioFileIO
          // in order to provide our own FS constructs.
          try {
            DremioFileIO fileIO =
                (DremioFileIO)
                    plugin.createIcebergFileIO(
                        plugin.createFS(
                            SupportsFsCreation.builder()
                                .filePath(baseTable.location())
                                .withSystemUserName()
                                .withSystemUserId()
                                .dataset(dataset)),
                        null,
                        dataset,
                        null,
                        null);
            return new DremioBaseTable(
                new DremioRESTTableOperations(
                    fileIO, ((HasTableOperations) baseTable).operations()),
                baseTable.name());
          } catch (Exception e) {
            throw UserException.ioExceptionError(e)
                .message("Error while trying to access file system.")
                .buildSilently();
          } finally {
            baseTable.io().close();
          }
        },
        TimeTravelProcessors.getTableSnapshotProvider(dataset, timeTravelRequest),
        TimeTravelProcessors.getTableSchemaProvider(timeTravelRequest),
        optionsManager);
  }

  private static Namespace namespaceFromDataset(List<String> dataset) {
    Preconditions.checkNotNull(dataset);
    Preconditions.checkState(!dataset.isEmpty());
    int size = dataset.size();
    Preconditions.checkState(size >= 3, "A dataset must only be created underneath of a folder.");
    return Namespace.of(dataset.subList(1, size - 1).toArray(new String[] {}));
  }

  protected static TableIdentifier tableIdentifierFromDataset(List<String> dataset) {
    Namespace ns = namespaceFromDataset(dataset);
    return TableIdentifier.of(ns, dataset.get(dataset.size() - 1));
  }

  @Override
  public boolean datasetExists(List<String> dataset) {
    try {
      return getCatalog().tableExists(tableIdentifierFromDataset(dataset))
          || (viewsEnabled()
              && ((ViewCatalog) getCatalog()).viewExists(tableIdentifierFromDataset(dataset)));
    } catch (IllegalStateException e) {
      // This happens if the table identifier created is incorrect.
      return false;
    } catch (BadRequestException e) {
      // if the path to the dataset is wrong we get a BadRequestException containing "Invalid
      // request path" message. If this is the case, just ignore it and return false.
      if (e.getMessage().contains("Invalid request path")) {
        return false;
      }
      throw e;
    }
  }

  @Override
  public boolean namespaceExists(List<String> namespace) {
    return ((SupportsNamespaces) getCatalog()).namespaceExists(namespaceFromPath(namespace));
  }

  @Override
  public TableMetadata getTableMetadata(List<String> dataset) {
    TableIdentifier tableIdentifier = tableIdentifierFromDataset(dataset);
    Table table = loadTable(tableIdentifier);
    Preconditions.checkState(table instanceof HasTableOperations);
    return ((HasTableOperations) table).operations().current();
  }

  @Override
  public ViewMetadata getViewMetadata(List<String> dataset) {
    TableIdentifier tableIdentifier = tableIdentifierFromDataset(dataset);
    BaseView baseView = (BaseView) loadView(tableIdentifier);
    return baseView.operations().current();
  }

  @Override
  public PartitionChunkListing listPartitionChunks(
      IcebergCatalogTableProvider icebergTableProvider, ListPartitionChunkOption[] options) {
    return icebergTableProvider.listPartitionChunks(options);
  }

  @Override
  public DatasetMetadata getTableMetadata(
      IcebergCatalogTableProvider icebergTableProvider, GetMetadataOption[] options) {
    return icebergTableProvider.getDatasetMetadata(options);
  }

  @Override
  public DatasetMetadata getViewMetadata(DatasetHandle viewHandle) {
    return viewHandle.unwrap(IcebergCatalogViewProvider.class);
  }

  boolean viewsEnabled() {
    return optionsManager.getOption(RESTCATALOG_VIEWS_SUPPORTED);
  }

  /**
   * Invalidates all table cache entries for the specified TableIdentifier, regardless of the
   * userId. This is useful when you need to invalidate a table for all users (e.g., when table
   * metadata changes).
   *
   * @param tableIdentifier the table identifier to invalidate for all users
   */
  public void invalidateTableCacheForAllUsers(TableIdentifier tableIdentifier) {
    if (tableIdentifier == null) {
      throw new IllegalArgumentException("TableIdentifier cannot be null");
    }

    // Get all cache keys and filter for matching table identifiers
    Set<CatalogAccessorTableCacheKey> keysToInvalidate =
        tableCache.asMap().keySet().stream()
            .filter(key -> key.tableIdentifier().equals(tableIdentifier))
            .collect(Collectors.toSet());

    // Invalidate all matching keys
    tableCache.invalidateAll(keysToInvalidate);

    if (logger.isDebugEnabled()) {
      logger.debug(
          "Invalidated {} table cache entries for table: {}",
          keysToInvalidate.size(),
          tableIdentifier);
    }
  }

  /**
   * Invalidates all view cache entries for the specified TableIdentifier, regardless of the userId.
   * This is useful when you need to invalidate a view for all users (e.g., when view metadata
   * changes).
   *
   * @param tableIdentifier the table identifier to invalidate for all users
   */
  public void invalidateViewCacheForAllUsers(TableIdentifier tableIdentifier) {
    if (tableIdentifier == null) {
      throw new IllegalArgumentException("TableIdentifier cannot be null");
    }

    // Get all cache keys and filter for matching table identifiers
    Set<CatalogAccessorTableCacheKey> keysToInvalidate =
        viewCache.asMap().keySet().stream()
            .filter(key -> key.tableIdentifier().equals(tableIdentifier))
            .collect(Collectors.toSet());

    // Invalidate all matching keys
    viewCache.invalidateAll(keysToInvalidate);

    if (logger.isDebugEnabled()) {
      logger.debug(
          "Invalidated {} view cache entries for view: {}",
          keysToInvalidate.size(),
          tableIdentifier);
    }
  }

  @Override
  public void close() throws Exception {
    if (icebergCatalogSupplier instanceof Closeable) {
      ((Closeable) icebergCatalogSupplier).close();
    }
    tableCache.invalidateAll();
    tableCache.cleanUp();
  }

  protected abstract void checkStateInternal() throws Exception;

  @Override
  public Stream<IcebergNamespaceWithProperties> getFolderStream() {
    return streamNamespaceWithPropertiesWithRoot(
        (SupportsNamespaces) getCatalog(), allowedNamespaces, isRecursiveAllowedNamespaces);
  }

  private static Stream<IcebergNamespaceWithProperties> streamNamespaceWithPropertiesWithRoot(
      SupportsNamespaces catalog,
      Set<Namespace> allowedNamespaces,
      boolean isRecursiveAllowedNamespaces) {
    return Stream.concat(
        getRootNamespaceStream(catalog, allowedNamespaces),
        streamNamespaceWithProperties(catalog, allowedNamespaces, isRecursiveAllowedNamespaces));
  }

  private static Stream<IcebergNamespaceWithProperties> streamNamespaceWithProperties(
      SupportsNamespaces catalog,
      Set<Namespace> allowedNamespaces,
      boolean isRecursiveAllowedNamespaces) {
    if (!isRecursiveAllowedNamespaces) {
      return allowedNamespaces.stream()
          .flatMap(ns -> getNamespacesWithProperties(catalog, getSubNamespaces(catalog, ns)));
    }
    return allowedNamespaces.stream()
        .flatMap(ns -> streamNamespaceWithPropertiesRecursive(catalog, ns));
  }

  private static Stream<IcebergNamespaceWithProperties> getRootNamespaceStream(
      SupportsNamespaces catalog, Set<Namespace> allowedNamespaces) {
    Stream<IcebergNamespaceWithProperties> allowedNamespacesStream = Stream.empty();
    if (!(allowedNamespaces.size() == 1 && allowedNamespaces.contains(Namespace.empty()))) {
      allowedNamespacesStream = getNamespacesWithProperties(catalog, allowedNamespaces);
    }
    return allowedNamespacesStream;
  }

  private static Stream<IcebergNamespaceWithProperties> streamNamespaceWithPropertiesRecursive(
      SupportsNamespaces catalog, Namespace namespace) throws NoSuchNamespaceException {
    List<Namespace> namespaces = getSubNamespaces(catalog, namespace);
    if (namespaces.isEmpty()) {
      return Stream.empty();
    }
    return Stream.concat(
        getNamespacesWithProperties(catalog, namespaces),
        namespaces.stream().flatMap(ns -> streamNamespaceWithPropertiesRecursive(catalog, ns)));
  }

  private static List<Namespace> getSubNamespaces(SupportsNamespaces catalog, Namespace namespace) {
    try {
      return catalog.listNamespaces(namespace);
    } catch (NoSuchNamespaceException e) {
      logger.error(
          "Error listing namespace {}. This could occur if the namespace was deleted while"
              + " refreshing metadata.",
          namespace.toString(),
          e);
      return Collections.emptyList();
    } catch (Exception e) {
      logger.debug(
          "Error listing namespace {}. This could occur if we are not authorized to access it.",
          namespace.toString(),
          e);
      return Collections.emptyList();
    }
  }

  private static Stream<IcebergNamespaceWithProperties> getNamespacesWithProperties(
      SupportsNamespaces catalog, Collection<Namespace> namespaces) {
    return namespaces.stream()
        .map(ns -> getNamespaceWithProperties(catalog, ns).orElse(null))
        .filter(Objects::nonNull);
  }

  private static Optional<IcebergNamespaceWithProperties> getNamespaceWithProperties(
      SupportsNamespaces catalog, Namespace namespace) throws NoSuchNamespaceException {
    try {
      return Optional.of(
          new IcebergNamespaceWithProperties(namespace, catalog.loadNamespaceMetadata(namespace)));
    } catch (NoSuchNamespaceException e) {
      logger.error(
          "Error listing namespace {}. This could occur if the namespace was deleted while"
              + " refreshing metadata.",
          namespace.toString(),
          e);
      return Optional.empty();
    } catch (Exception e) {
      logger.debug(
          "Error listing namespace {}. This could occur if we are not authorized to access it.",
          namespace.toString(),
          e);
      return Optional.empty();
    }
  }

  // SupportsIcebergDatasetCUD Methods - START

  @Override
  public Table createTable(
      List<String> tablePathComponents,
      Schema schema,
      PartitionSpec partitionSpec,
      SortOrder sortOrder,
      @Nullable String location,
      Map<String, String> tableProperties) {
    TableIdentifier tableIdentifier = tableIdentifierFromDataset(tablePathComponents);

    return getCatalog()
        .buildTable(tableIdentifier, schema)
        .withPartitionSpec(partitionSpec)
        .withSortOrder(sortOrder)
        .withLocation(location)
        .withProperties(tableProperties)
        .create();
  }

  @Override
  public View createView(
      List<String> viewPathComponents,
      @Nullable String location,
      List<String> workspaceSchemaPath,
      Schema schema,
      String sql)
      throws AlreadyExistsException, NoSuchNamespaceException {
    TableIdentifier viewIdentifier = tableIdentifierFromDataset(viewPathComponents);
    if (((ViewCatalog) getCatalog()).viewExists(viewIdentifier)) {
      throw new AlreadyExistsException("View [%s] already exists with the source.", viewIdentifier);
    }
    Preconditions.checkArgument(!viewPathComponents.isEmpty(), "View path cannot be empty.");
    return getViewBuilder(
            viewIdentifier, schema, location, workspaceSchemaPath, viewPathComponents.get(0), sql)
        .create();
  }

  @Override
  public void dropView(List<String> viewPathComponents) throws NoSuchViewException {
    ((ViewCatalog) getCatalog()).dropView(tableIdentifierFromDataset(viewPathComponents));
  }

  @Override
  public View updateView(
      List<String> viewPathComponents,
      @Nullable String location,
      List<String> workSchemaPath,
      Schema schema,
      String sql)
      throws NoSuchViewException {
    TableIdentifier viewIdentifier = tableIdentifierFromDataset(viewPathComponents);
    if (!((ViewCatalog) getCatalog()).viewExists(viewIdentifier)) {
      throw new NoSuchViewException("Cannot find View [%s] in the source.", viewIdentifier);
    }
    // TODO(DX-99998) Add retryer
    return getViewBuilder(
            viewIdentifier, schema, location, workSchemaPath, viewPathComponents.get(0), sql)
        .replace();
  }

  private String getRootOfWorkspaceSchemaPath(List<String> workspaceSchemaPath) {
    if (workspaceSchemaPath != null && !workspaceSchemaPath.isEmpty()) {
      return workspaceSchemaPath.get(0);
    }
    return null;
  }

  private String deriveDefaultCatalog(String parentCatalogName, List<String> workspaceSchemaPath) {
    String root = getRootOfWorkspaceSchemaPath(workspaceSchemaPath);
    if (root != null && !root.equals(parentCatalogName)) {
      return root;
    }
    return null;
  }

  private Namespace getNamespaceFromSchemaPath(List<String> workspaceSchemaPath) {
    if (workspaceSchemaPath.isEmpty()) {
      return Namespace.empty();
    }
    List<String> schemaPath = workspaceSchemaPath.subList(1, workspaceSchemaPath.size());
    return Namespace.of(schemaPath.toArray(new String[schemaPath.size()]));
  }

  private ViewBuilder getViewBuilder(
      TableIdentifier identifier,
      Schema schema,
      @Nullable String location,
      List<String> workspaceSchemaPath,
      String catalog,
      String sql) {
    String resolvedLocation = getViewLocation(location, identifier);

    return ((ViewCatalog) getCatalog())
        .buildView(identifier)
        .withSchema(schema)
        .withLocation(resolvedLocation)
        .withDefaultNamespace(getNamespaceFromSchemaPath(workspaceSchemaPath))
        .withDefaultCatalog(deriveDefaultCatalog(catalog, workspaceSchemaPath))
        .withQuery(IcebergViewMetadata.SupportedViewDialectsForRead.DREMIOSQL.toString(), sql);
  }

  @VisibleForTesting
  String getViewLocation(@Nullable String location, TableIdentifier identifier) {
    if (location != null) {
      return location;
    }

    ViewCatalog viewCatalog = (ViewCatalog) getCatalog();
    if (viewCatalog.viewExists(identifier)) {
      return viewCatalog.loadView(identifier).location();
    }

    SupportsNamespaces namespaceCatalog = (SupportsNamespaces) getCatalog();
    return getDatasetLocationFromExistingNamespaceLocationUri(identifier, namespaceCatalog);
  }

  @Override
  public void dropTable(List<String> dataset) {
    // This implementation does NOT purge the data.
    // This is following what Spark does by default for "external tables".
    getCatalog().dropTable(tableIdentifierFromDataset(dataset), false);
  }

  @Override
  public TableOperations createIcebergTableOperations(
      FileIO fileIO, List<String> dataset, @Nullable String userName, @Nullable String userId) {
    return new DremioRESTTableOperations((DremioFileIO) fileIO, tableOperationsHelper(dataset));
  }

  protected TableOperations tableOperationsHelper(List<String> dataset) {
    final TableIdentifier tableIdentifier = tableIdentifierFromDataset(dataset);
    final Table table = loadTable(tableIdentifier);
    return ((HasTableOperations) table).operations();
  }

  @Override
  public TableOperations createIcebergTableOperationsForCtas(
      FileIO fileIO,
      List<String> dataset,
      Schema schema,
      @Nullable String userName,
      @Nullable String userId) {
    return new DremioRESTTableOperations(
        (DremioFileIO) fileIO, tableOperationsHelperForCtas(dataset, schema));
  }

  protected TableOperations tableOperationsHelperForCtas(List<String> dataset, Schema schema) {
    final TableIdentifier tableIdentifier = tableIdentifierFromDataset(dataset);
    final Transaction transaction = createTableTransactionForNewTable(tableIdentifier, schema);

    Preconditions.checkState(
        transaction instanceof BaseTransaction, "Error - Plugin does not support this operation.");
    return ((BaseTransaction) transaction).underlyingOps();
  }

  @Override
  public @Nullable String getDatasetLocationFromExistingNamespaceLocationUri(List<String> dataset) {
    TableIdentifier tableIdentifier = tableIdentifierFromDataset(dataset);
    SupportsNamespaces namespaceCatalog = (SupportsNamespaces) getCatalog();

    return getDatasetLocationFromExistingNamespaceLocationUri(tableIdentifier, namespaceCatalog);
  }

  private static @Nullable String getDatasetLocationFromExistingNamespaceLocationUri(
      TableIdentifier tableIdentifier, SupportsNamespaces namespaceCatalog) {
    String locationUri;
    try {
      locationUri =
          namespaceCatalog.loadNamespaceMetadata(tableIdentifier.namespace()).get("location");
    } catch (NoSuchNamespaceException e) {
      logger.warn("There was no namespace at {}.", tableIdentifier.namespace().toString(), e);
      return null;
    }
    if (locationUri != null && !locationUri.isBlank()) {
      return PathUtils.removeTrailingSlash(locationUri) + '/' + tableIdentifier.name();
    }

    // The location can be null if there is no location in the namespace metadata, however we do not
    // throw an error because the Iceberg spec will accept a null location.
    return null;
  }

  // SupportsIcebergDatasetCUD Methods - END

  // SupportsIcebergFolderCUD Methods - START

  @Override
  public Map<String, String> createFolder(
      List<String> folderPathWithSourceName, Map<String, String> properties)
      throws AlreadyExistsException {
    Namespace namespace = namespaceFromPath(folderPathWithSourceName);
    SupportsNamespaces supportsNamespaces = (SupportsNamespaces) getCatalog();
    supportsNamespaces.createNamespace(namespace, properties);
    return supportsNamespaces.loadNamespaceMetadata(namespace);
  }

  @Override
  public Map<String, String> updateFolder(
      List<String> folderPathWithSourceName,
      Map<String, String> propertiesToUpdate,
      Set<String> propertiesToRemove)
      throws NoSuchNamespaceException {
    Namespace namespace = namespaceFromPath(folderPathWithSourceName);
    SupportsNamespaces supportsNamespaces = (SupportsNamespaces) getCatalog();
    if (!propertiesToUpdate.isEmpty()) {
      supportsNamespaces.setProperties(namespace, propertiesToUpdate);
    }
    if (!propertiesToRemove.isEmpty()) {
      supportsNamespaces.removeProperties(namespace, propertiesToRemove);
    }
    return supportsNamespaces.loadNamespaceMetadata(namespace);
  }

  @Override
  public boolean dropFolder(List<String> folderPathWithSourceName)
      throws NamespaceNotEmptyException {
    Namespace namespace = namespaceFromPath(folderPathWithSourceName);
    SupportsNamespaces supportsNamespaces = (SupportsNamespaces) getCatalog();
    return supportsNamespaces.dropNamespace(namespace);
  }

  // SupportsIcebergFolderCUD Methods - END

  private static Namespace namespaceFromPath(List<String> folderPathWithSourceName) {
    return Namespace.of(
        folderPathWithSourceName
            .subList(1, folderPathWithSourceName.size())
            .toArray(new String[] {}));
  }
}
