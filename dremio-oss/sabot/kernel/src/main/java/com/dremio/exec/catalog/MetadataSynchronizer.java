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
package com.dremio.exec.catalog;

import static com.dremio.exec.catalog.CatalogFolderUtils.getFolderConfigForNSUpdate;
import static com.dremio.exec.catalog.CatalogOptions.RESTCATALOG_FOLDERS_SUPPORTED;
import static com.dremio.exec.catalog.CatalogOptions.RESTCATALOG_LINEAGE_CALCULATION;
import static com.dremio.exec.catalog.CatalogOptions.RESTCATALOG_VIEWS_SUPPORTED;

import com.dremio.catalog.model.CatalogFolder;
import com.dremio.catalog.model.ImmutableCatalogFolder;
import com.dremio.common.collections.Tuple;
import com.dremio.common.utils.PathUtils;
import com.dremio.connector.ConnectorException;
import com.dremio.connector.metadata.BytesOutput;
import com.dremio.connector.metadata.DatasetHandle;
import com.dremio.connector.metadata.DatasetHandleListing;
import com.dremio.connector.metadata.DatasetMetadata;
import com.dremio.connector.metadata.DatasetNotFoundException;
import com.dremio.connector.metadata.GetDatasetOption;
import com.dremio.connector.metadata.SourceMetadata;
import com.dremio.connector.metadata.UnsupportedDatasetHandleListing;
import com.dremio.connector.metadata.ViewDatasetHandle;
import com.dremio.connector.metadata.extensions.SupportsListingDatasets;
import com.dremio.connector.metadata.extensions.SupportsReadSignature;
import com.dremio.connector.metadata.extensions.SupportsReadSignature.MetadataValidity;
import com.dremio.exec.catalog.lineage.SqlLineageExtractor;
import com.dremio.exec.catalog.lineage.TableLineage;
import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.planner.sql.CalciteArrowHelper;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.proto.UserProtos;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.server.SabotQueryContext;
import com.dremio.exec.server.options.SessionOptionManagerImpl;
import com.dremio.exec.store.DatasetRetrievalOptions;
import com.dremio.exec.store.metadatarefresh.SupportsUnlimitedSplits;
import com.dremio.exec.util.ViewFieldsHelper;
import com.dremio.options.OptionManager;
import com.dremio.sabot.rpc.user.UserSession;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceNotFoundException;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.dataset.proto.VirtualDataset;
import com.dremio.service.namespace.folder.FolderNamespaceService;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import com.dremio.service.namespace.source.proto.MetadataPolicy;
import com.dremio.service.namespace.source.proto.UpdateMode;
import com.dremio.service.namespace.space.proto.FolderConfig;
import com.dremio.service.orphanage.Orphanage;
import com.dremio.service.users.SystemUser;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Sets;
import io.opentelemetry.api.trace.Span;
import io.protostuff.ByteString;
import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.calcite.rel.type.RelDataType;

/** Synchronizes metadata from the connector to the namespace. */
public class MetadataSynchronizer {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(MetadataSynchronizer.class);

  private static final int NUM_RETRIES = 1;
  private final MetadataSynchronizerStatus metadataSynchronizerStatus =
      new MetadataSynchronizerStatus(true);

  private final NamespaceService systemNamespaceService;
  private final NamespaceKey sourceKey;
  private final SourceMetadata sourceMetadata;
  private final ManagedStoragePlugin.MetadataBridge bridge;
  private final DatasetSaver saver;
  private final DatasetRetrievalOptions datasetRetrievalOptions;

  private final UpdateMode updateMode;
  private final Set<NamespaceKey> ancestorsToKeep;
  private final List<Tuple<String, String>> failedDatasets;
  private final Orphanage orphanage;
  private final OptionManager optionManager;

  private Set<NamespaceKey> orphanedDatasets;
  private final Set<NamespaceKey> updatedViews;
  private final SabotQueryContext sabotQueryContext;
  private final boolean shouldRefreshAllViews;

  MetadataSynchronizer(
      NamespaceService systemNamespaceService,
      NamespaceKey sourceKey,
      ManagedStoragePlugin.MetadataBridge bridge,
      MetadataPolicy metadataPolicy,
      DatasetSaver saver,
      DatasetRetrievalOptions datasetRetrievalOptions,
      OptionManager optionManager,
      SabotQueryContext sabotQueryContext) {
    this.systemNamespaceService = Preconditions.checkNotNull(systemNamespaceService);
    this.sourceKey = Preconditions.checkNotNull(sourceKey);
    this.bridge = Preconditions.checkNotNull(bridge);
    Preconditions.checkArgument(bridge.getMetadata().isPresent());
    this.sourceMetadata = bridge.getMetadata().get();
    this.saver = saver;
    this.datasetRetrievalOptions = datasetRetrievalOptions;
    this.optionManager = optionManager;
    this.updateMode = metadataPolicy.getDatasetUpdateMode();
    this.ancestorsToKeep = new HashSet<>();
    this.failedDatasets = new ArrayList<>();
    this.orphanage = bridge.getOrphanage();
    this.updatedViews = new HashSet<>();
    this.sabotQueryContext = sabotQueryContext;
    this.shouldRefreshAllViews = optionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED);
  }

  /**
   * Perform synchronization.
   *
   * @return status
   */
  MetadataSynchronizerStatus go() {
    Stopwatch stopwatch = Stopwatch.createStarted();
    logger.debug("Source '{}' sync started", sourceKey);
    validateUpdateMode();
    try {
      performSync();
    } catch (ManagedStoragePlugin.StoragePluginChanging e) {
      logger.info(
          "Source '{}' sync aborted due to plugin changing during the sync. Will try again later",
          sourceKey);
    } catch (Exception e) {
      logger.warn("Source '{}' sync failed unexpectedly. Will try again later", sourceKey, e);
    } finally {
      if (!failedDatasets.isEmpty()) {
        logger.warn(
            "Source '{}' sync failed for {} datasets. Few failed datasets and reasons:\n{}",
            sourceKey,
            failedDatasets.size(),
            failedDatasets.stream()
                .map(tuple -> "\t" + tuple.first + ": " + tuple.second)
                .limit(10)
                .collect(Collectors.joining("\n")));
      }
      logger.debug(
          "Source '{}' sync ended. Took {} milliseconds",
          sourceKey,
          stopwatch.elapsed(TimeUnit.MILLISECONDS));
    }

    return metadataSynchronizerStatus;
  }

  private void performSync() throws NamespaceException, ConnectorException {
    markDatasetsAndFoldersForDeletion();
    updateNamespaceServiceWithDatasetMetadataFromSource();
    boolean retrievedFolderMetadataSuccessfully =
        updateNamespaceServiceWithFolderMetadataFromSource(
            optionManager, sourceMetadata, sourceKey, ancestorsToKeep, systemNamespaceService);
    if (retrievedFolderMetadataSuccessfully) {
      removeNamespaceServiceFoldersThatContainNoDatasets();
    }
    deleteNamespaceServiceDatasetsNoLongerInSource();
    if (optionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED)
        && optionManager.getOption(RESTCATALOG_LINEAGE_CALCULATION)) {
      updateLineageMetadataForViewsInNamespace();
    }
  }

  private void validateUpdateMode() {
    Preconditions.checkState(
        updateMode == UpdateMode.PREFETCH || updateMode == UpdateMode.PREFETCH_QUERIED,
        "only PREFETCH and PREFETCH_QUERIED are supported");
  }

  private void markDatasetsAndFoldersForDeletion() {
    // Initially we assume all datasets are orphaned, until we discover they still exist in the
    // source
    orphanedDatasets = Sets.newHashSet(systemNamespaceService.getAllDatasets(sourceKey));
    ancestorsToKeep.add(sourceKey);

    logger.debug("Source '{}' sync setup ({} datasets)", sourceKey, orphanedDatasets.size());
    logger.trace("Source '{}' has datasets: '{}'", sourceKey, orphanedDatasets);
  }

  private DatasetHandleListing getDatasetHandleListing(GetDatasetOption... getDatasetOptions)
      throws ConnectorException {
    if (sourceMetadata instanceof SupportsListingDatasets) {
      return ((SupportsListingDatasets) sourceMetadata).listDatasetHandles(getDatasetOptions);
    }

    return new NamespaceListing(
        systemNamespaceService, sourceKey, sourceMetadata, this.datasetRetrievalOptions);
  }

  /**
   * Brings the NamespaceService up to date by gathering metadata from the source about existing and
   * new datasets.
   *
   * @throws NamespaceException if it cannot be handled due to namespace error
   * @throws ConnectorException if it cannot be handled due to an error in the source connection
   */
  private void updateNamespaceServiceWithDatasetMetadataFromSource()
      throws NamespaceException, ConnectorException {
    logger.debug("Source '{}' syncing datasets", sourceKey);
    try (DatasetHandleListing datasetListing =
        getDatasetHandleListing(datasetRetrievalOptions.asGetDatasetOptions(null))) {
      if (datasetListing instanceof UnsupportedDatasetHandleListing) {
        logger.debug(
            "Source '{}' does not support listing datasets, assuming all are valid", sourceKey);
        orphanedDatasets.clear();
        return;
      }
      final Iterator<? extends DatasetHandle> iterator = datasetListing.iterator();
      long entityCount = 0L;
      do {
        try {
          // DX-60601, the current theory is that iterator exit earlier while we still have datasets
          // not refreshed yet.
          // Hence, it causes them to be deleted following this method. Let's log them for now
          // specifically in
          // handleExistingDataset when something bad happened to see if we still have datasets to
          // be refreshed.

          // Note: This can throw ConnectorRuntimeException or DatasetMetadataTooLargeException.
          if (!iterator.hasNext()) {
            break;
          }
          ++entityCount;

          // Note: This can throw ConnectorRuntimeException or DatasetMetadataTooLargeException.
          final DatasetHandle handle = iterator.next();
          final NamespaceKey datasetKey =
              MetadataObjectsUtils.toNamespaceKey(handle.getDatasetPath());
          final boolean existing = orphanedDatasets.remove(datasetKey);
          if (logger.isTraceEnabled()) {
            logger.trace(
                "Dataset '{}' sync started ({})", datasetKey, existing ? "existing" : "new");
          }
          if (existing) {
            addAncestors(datasetKey, ancestorsToKeep);
            handleExistingDataset(datasetKey, handle, iterator);
          } else {
            handleNewDataset(datasetKey, handle);
          }
        } catch (DatasetMetadataTooLargeException e) {
          final boolean existing =
              orphanedDatasets.remove(new NamespaceKey(PathUtils.parseFullPath(e.getMessage())));
          logger.error(
              "Dataset {} sync failed ({}) due to Metadata too large. Please check.",
              e.getMessage(),
              existing ? "existing" : "new");
        }
      } while (true);
      logger.info("Source '{}' iterated through {} entities", sourceKey, entityCount);
    }
  }

  /**
   * Brings the NamespaceService up to date by gathering metadata from the source about new and
   * existing folders.
   */
  @VisibleForTesting
  static boolean updateNamespaceServiceWithFolderMetadataFromSource(
      OptionManager optionManager,
      SourceMetadata sourceMetadata,
      NamespaceKey sourceKey,
      Set<NamespaceKey> ancestorsToKeep,
      FolderNamespaceService systemNamespaceService) {
    if (!optionManager.getOption(RESTCATALOG_FOLDERS_SUPPORTED)) {
      return true;
    }

    if (!(sourceMetadata instanceof SupportsFolderIngestion)) {
      return true;
    }

    logger.debug("Source '{}' syncing folders", sourceKey);
    try (FolderListing folderListing =
        ((SupportsFolderIngestion) sourceMetadata).getFolderListing()) {
      final Iterator<ImmutableCatalogFolder> iterator = folderListing.iterator();
      long folderCount = 0L;
      try {
        while (iterator.hasNext()) {
          ++folderCount;
          CatalogFolder folderFromPlugin = iterator.next();
          NamespaceKey folderKey = new NamespaceKey(folderFromPlugin.fullPath());
          ancestorsToKeep.add(folderKey);
          try {
            FolderConfig updatedFolderConfig =
                getFolderConfigForNSUpdate(systemNamespaceService, folderKey, folderFromPlugin);
            systemNamespaceService.addOrUpdateFolder(folderKey, updatedFolderConfig);
          } catch (NamespaceException | ConcurrentModificationException e) {
            logger.warn(
                "There was a NamespaceException while trying to add or update folders in NamespaceService cache.",
                e);
          }
        }
      } catch (ConnectorRuntimeException connectorRuntimeException) {
        logger.warn(
            "There was a ConnectorRuntimeException while trying to get the next folder from the source.",
            connectorRuntimeException);
        return false;
      }
      logger.info("Source '{}' iterated through {} folders", sourceKey, folderCount);
    }
    return true;
  }

  /**
   * Handle metadata sync for the given existing dataset.
   *
   * @param datasetKey dataset key
   * @param handle dataset handle
   * @param iterator dataset handle iterator
   */
  private void handleExistingDataset(
      NamespaceKey datasetKey, DatasetHandle handle, Iterator<? extends DatasetHandle> iterator) {
    int tryCount = 0;
    while (true) {
      if (tryCount++ > NUM_RETRIES) {
        logger.debug(
            "Dataset '{}' sync failed {} times (CME). Will retry next sync",
            datasetKey,
            NUM_RETRIES);
        break;
      }

      final Stopwatch stopwatch = Stopwatch.createStarted();
      try {
        tryHandleExistingDataset(datasetKey, handle);
        break;
      } catch (ConcurrentModificationException ignored) {
        // retry
        // continue;
      } catch (DatasetNotFoundException | NamespaceNotFoundException e) {
        // race condition: metadata will be removed from catalog in next sync
        logger.debug(
            "Dataset '{}' is no longer valid, skipping sync. Has next? {}",
            datasetKey,
            iterator.hasNext(),
            e);
        failedDatasets.add(Tuple.of(datasetKey.getSchemaPath(), e.getMessage()));
        metadataSynchronizerStatus.incrementDatasetExtendedUnreadable();
        break;
      } catch (Exception e) {
        // TODO: this should not be an Exception. Once exception handling is defined, change this.
        // This is unfortunately
        //  the current behavior.
        logger.debug(
            "Dataset '{}' sync failed unexpectedly. Will retry next sync. Has next? {}",
            datasetKey,
            iterator.hasNext(),
            e);
        failedDatasets.add(Tuple.of(datasetKey.getSchemaPath(), e.getMessage()));
        metadataSynchronizerStatus.incrementDatasetExtendedUnreadable();
        break;
      } finally {
        if (logger.isDebugEnabled()) {
          logger.debug(
              "Dataset '{}' sync took {} milliseconds",
              datasetKey,
              stopwatch.elapsed(TimeUnit.MILLISECONDS));
        }
      }
    }
  }

  /**
   * Try handling metadata sync for the given existing dataset.
   *
   * @param datasetKey dataset key
   * @param datasetHandle dataset handle
   * @throws NamespaceException if it cannot be handled
   */
  private void tryHandleExistingDataset(NamespaceKey datasetKey, DatasetHandle datasetHandle)
      throws NamespaceException, ConnectorException {
    // invariant: only metadata attributes of currentConfig are overwritten, and then the same
    // currentConfig is saved,
    // so the rest of the attributes are as is; so CME is handled by retrying this entire block

    final DatasetConfig currentConfig = systemNamespaceService.getDataset(datasetKey);

    if (updateMode == UpdateMode.PREFETCH_QUERIED && shouldSkipRefreshingDataset(currentConfig)) {
      logger.trace("Dataset '{}' has not been queried , skipping", datasetKey);
      metadataSynchronizerStatus.incrementDatasetShallowUnchanged();
      return;
    }

    if (!MetadataObjectsUtils.isShallowTable(currentConfig)
        && sourceMetadata instanceof SupportsReadSignature) {
      String user = SystemUser.SYSTEM_USERNAME;
      if (datasetRetrievalOptions.datasetRefreshQuery().isPresent()) {
        user = datasetRetrievalOptions.datasetRefreshQuery().get().getUser();
      }
      boolean supportsIcebergMetadata =
          (sourceMetadata instanceof SupportsUnlimitedSplits)
              && ((SupportsUnlimitedSplits) sourceMetadata)
                  .allowUnlimitedSplits(datasetHandle, currentConfig, user);
      final boolean isIcebergMetadata =
          currentConfig.getPhysicalDataset() != null
              && Boolean.TRUE.equals(
                  currentConfig.getPhysicalDataset().getIcebergMetadataEnabled());
      final boolean forceUpdateNotRequired = !supportsIcebergMetadata || isIcebergMetadata;

      if (forceUpdateNotRequired) {
        final SupportsReadSignature supportsReadSignature = (SupportsReadSignature) sourceMetadata;
        final DatasetMetadata currentExtended = new DatasetMetadataAdapter(currentConfig);
        final ByteString readSignature = currentConfig.getReadDefinition().getReadSignature();
        final MetadataValidity metadataValidity =
            supportsReadSignature.validateMetadata(
                readSignature == null || readSignature.isEmpty()
                    ? BytesOutput.NONE
                    : os -> ByteString.writeTo(os, readSignature),
                datasetHandle,
                currentExtended);
        if (metadataValidity == MetadataValidity.VALID) {
          logger.trace("Dataset '{}' metadata is valid, skipping", datasetKey);
          metadataSynchronizerStatus.incrementDatasetExtendedUnchanged();
          return;
        }
      }
    }

    saver.save(currentConfig, datasetHandle, sourceMetadata, false, datasetRetrievalOptions);
    logger.trace("Dataset '{}' metadata saved to namespace", datasetKey);
    if (currentConfig.getType() == DatasetType.VIRTUAL_DATASET) {
      updatedViews.add(datasetKey);
    }
    metadataSynchronizerStatus.setWasSuccessfullyRefreshed();
    metadataSynchronizerStatus.incrementDatasetExtendedChanged();
  }

  /**
   * If the RESTCATALOG_VIEWS_SUPPORTED flag is on, the metadata of a view will always be refreshed.
   *
   * <p>Otherwise (the flag is off), if the dataset has never been queried then the metadata would
   * be shallow. In that case, we do not need to refresh the metadata.
   */
  private boolean shouldSkipRefreshingDataset(DatasetConfig datasetConfig) {
    if (datasetConfig.getType() == DatasetType.VIRTUAL_DATASET) {
      return !shouldRefreshAllViews && MetadataObjectsUtils.isShallowView(datasetConfig);
    } else {
      return MetadataObjectsUtils.isShallowTable(datasetConfig);
    }
  }

  /**
   * Handle new dataset based on the metadata policy.
   *
   * @param datasetKey dataset key
   * @param handle dataset handle
   * @throws NamespaceException if it cannot be handled
   */
  private void handleNewDataset(NamespaceKey datasetKey, DatasetHandle handle)
      throws NamespaceException {
    switch (updateMode) {
      case PREFETCH:
      // this mode will soon be deprecated, for now save, perform name sync

      // fall-through

      case PREFETCH_QUERIED:
        {
          final DatasetConfig newConfig = MetadataObjectsUtils.newShallowConfig(handle);
          if (handle instanceof ViewDatasetHandle && shouldRefreshAllViews) {
            saver.save(newConfig, handle, sourceMetadata, false, datasetRetrievalOptions);
            updatedViews.add(datasetKey);
          } else {
            try {
              systemNamespaceService.addOrUpdateDataset(datasetKey, newConfig);
              metadataSynchronizerStatus.setWasSuccessfullyRefreshed();
              metadataSynchronizerStatus.incrementDatasetShallowAdded();
            } catch (ConcurrentModificationException ignored) {
              // race condition
              logger.debug("Dataset '{}' add failed (CME)", datasetKey);
            }
          }
          return;
        }

      default:
        throw new IllegalStateException("unknown dataset update mode: " + updateMode);
    }
  }

  private void removeNamespaceServiceFoldersThatContainNoDatasets() {
    if (!datasetRetrievalOptions.deleteUnavailableDatasets()) {
      logger.debug(
          "Source '{}' in state {} may have orphaned folders, not deleting them as orphaned datasets are not deleted.",
          sourceKey,
          bridge.getState());
      return;
    }

    logger.debug("Source '{}' recursively deleting orphan folders", sourceKey);
    for (NamespaceKey toBeDeleted : orphanedDatasets) {

      final Iterator<NamespaceKey> ancestors = getAncestors(toBeDeleted);

      while (ancestors.hasNext()) {
        final NamespaceKey ancestorKey = ancestors.next();
        if (ancestorsToKeep.contains(ancestorKey) || !ancestorKey.hasParent()) {
          continue;
        }

        try {
          final NameSpaceContainer container = systemNamespaceService.getEntityByPath(ancestorKey);
          if (container == null || container.getType() != NameSpaceContainer.Type.FOLDER) {
            throw new NamespaceNotFoundException(ancestorKey, "not found");
          }
          // If a folder is shared, it shouldn't be deleted. Neither its parent(s).
          if (isFolderShared(container)) {
            break;
          }
          final FolderConfig folderConfig = container.getFolder();
          systemNamespaceService.deleteFolder(ancestorKey, folderConfig.getTag());
          logger.trace("Folder '{}' deleted", ancestorKey);
          metadataSynchronizerStatus.setWasSuccessfullyRefreshed();
        } catch (NamespaceNotFoundException ignored) {
          // either race condition, or ancestorKey is not a folder
          logger.debug("Folder '{}' not found", ancestorKey);
        } catch (NamespaceException ex) {
          logger.debug("Folder '{}' delete failed", ancestorKey, ex);
        }
      }
    }
  }

  protected boolean isFolderShared(NameSpaceContainer container) {
    return false;
  }

  /** Deleted orphan datasets. These are datasets that are no longer present in the source. */
  private void deleteNamespaceServiceDatasetsNoLongerInSource() {
    if (!datasetRetrievalOptions.deleteUnavailableDatasets()) {
      logger.debug(
          "Source '{}' in state {} has {} unavailable datasets, but not deleted: {}",
          sourceKey,
          bridge.getState(),
          orphanedDatasets.size(),
          orphanedDatasets);
      return;
    }

    if (!orphanedDatasets.isEmpty()) {
      logger.info(
          "Source '{}' in state {} has {} unavailable datasets to be deleted: {}",
          sourceKey,
          bridge.getState(),
          orphanedDatasets.size(),
          orphanedDatasets.stream()
              .limit(Math.min(orphanedDatasets.size(), 100))
              .collect(Collectors.toSet()));
    }

    for (NamespaceKey toBeDeleted : orphanedDatasets) {

      final DatasetConfig datasetConfig;
      try {
        datasetConfig = systemNamespaceService.getDataset(toBeDeleted);
        if (CatalogUtil.hasIcebergMetadata(datasetConfig)) {
          CatalogUtil.addIcebergMetadataOrphan(datasetConfig, orphanage);
        }
        systemNamespaceService.deleteDataset(toBeDeleted, datasetConfig.getTag());
        metadataSynchronizerStatus.setWasSuccessfullyRefreshed();
        if (datasetConfig.getReadDefinition() == null) {
          metadataSynchronizerStatus.incrementDatasetShallowDeleted();
        } else {
          metadataSynchronizerStatus.incrementDatasetExtendedDeleted();
        }
        logger.trace("Dataset '{}' deleted", toBeDeleted);
      } catch (NamespaceNotFoundException ignored) {
        // race condition - this is expected if the dataset was contained in an orphaned folder
        // recursively deleted
        logger.debug("Dataset '{}' not found", toBeDeleted);
        // continue;
      } catch (NamespaceException e) {
        logger.warn("Dataset '{}' to be deleted, but lookup failed", toBeDeleted, e);
        failedDatasets.add(Tuple.of(toBeDeleted.getSchemaPath(), e.getMessage()));
        // continue;
      }
    }
  }

  private static void addAncestors(NamespaceKey datasetKey, Set<NamespaceKey> ancestors) {
    NamespaceKey key = datasetKey.getParent();
    while (key.hasParent()) {
      ancestors.add(key);
      key = key.getParent();
    }
  }

  private static Iterator<NamespaceKey> getAncestors(NamespaceKey datasetKey) {
    return new Iterator<>() {
      private NamespaceKey currentKey = datasetKey;

      @Override
      public boolean hasNext() {
        return currentKey.hasParent();
      }

      @Override
      public NamespaceKey next() {
        if (!currentKey.hasParent()) {
          throw new NoSuchElementException();
        }
        currentKey = currentKey.getParent();
        return currentKey;
      }
    };
  }

  /**
   * Update lineage metadata for views in Namespace. These views should already have other metadata
   * gathered from the source.
   */
  private void updateLineageMetadataForViewsInNamespace() {
    logger.debug("Source '{}' start updating lineage metadata.", sourceKey);
    final Stopwatch stopwatch = Stopwatch.createStarted();
    long failedLinageUpdates = 0;

    for (NamespaceKey datasetKey : updatedViews) {
      try {
        final DatasetConfig currentConfig = systemNamespaceService.getDataset(datasetKey);
        if (currentConfig.getType() != DatasetType.VIRTUAL_DATASET) {
          logger.warn(
              "Dataset '{}' has the wrong type {}, while expecting VIRTUAL_DATASET.",
              datasetKey,
              currentConfig.getType());
          continue;
        }
        if (currentConfig.getVirtualDataset() == null) {
          logger.warn("Dataset '{}' has null VirtualDataset.", datasetKey);
          continue;
        }

        updateDatasetLineageMetadata(currentConfig);
        systemNamespaceService.addOrUpdateDataset(datasetKey, currentConfig);
      } catch (ConcurrentModificationException ignored) {
        failedLinageUpdates++;
        logger.debug(
            "Dataset '{}' is being updated by another thread. Skip in this cycle. Will be updated later.",
            datasetKey);
      } catch (NamespaceNotFoundException ignored) {
        failedLinageUpdates++;
        logger.debug(
            "Dataset '{}' is no longer valid while updating lineage metadata. No further work needed",
            datasetKey);
      } catch (Exception e) {
        failedLinageUpdates++;
        logger.info(
            "Dataset '{}' updating lineage metadata failed unexpectedly. Will retry next cycle.",
            datasetKey,
            e);
      }
    }
    long refreshLineageMetadataDuration = stopwatch.elapsed(TimeUnit.MILLISECONDS);
    Span.current()
        .setAttribute(
            "dremio.catalog.refreshMetadata.updateLineageMetadataForViewsInNamespace",
            refreshLineageMetadataDuration);
    logger.debug(
        "Source '{}' end updating lineage metadata. Took {} milliseconds",
        sourceKey,
        refreshLineageMetadataDuration);
    if (failedLinageUpdates != 0) {
      logger.warn(
          "Source '{}' lineage metadata update failed to update {} views.",
          sourceKey,
          failedLinageUpdates);
    }
  }

  private void updateDatasetLineageMetadata(DatasetConfig datasetConfig) {
    String user = SystemUser.SYSTEM_USERNAME;

    VirtualDataset virtualDataset = datasetConfig.getVirtualDataset();
    QueryContext queryContext = newQueryContext(user, virtualDataset.getContextList());
    try {
      TableLineage tableLineage =
          SqlLineageExtractor.extractLineage(queryContext, virtualDataset.getSql());
      virtualDataset.setParentsList(tableLineage.getUpstreams());
      virtualDataset.setFieldUpstreamsList(tableLineage.getFieldUpstreams());

      RelDataType fields = tableLineage.getFields();
      BatchSchema batchSchema = CalciteArrowHelper.fromCalciteRowType(fields);
      datasetConfig.setRecordSchema(batchSchema.toByteString());
      virtualDataset.setSqlFieldsList(ViewFieldsHelper.getBatchSchemaFields(batchSchema));

    } catch (Exception e) {
      logger.debug(
          "Dataset '{}' updating lineage metadata failed when extracting lineage.",
          datasetConfig.getFullPathList(),
          e);
    }
  }

  private QueryContext newQueryContext(String username, List<String> context) {
    UserBitShared.QueryId queryId = UserBitShared.QueryId.newBuilder().build();
    UserSession session =
        UserSession.Builder.newBuilder()
            .withSessionOptionManager(
                new SessionOptionManagerImpl(sabotQueryContext.getOptionValidatorListing()),
                sabotQueryContext.getOptionManager())
            .withCredentials(
                UserBitShared.UserCredentials.newBuilder().setUserName(username).build())
            .withUserProperties(UserProtos.UserProperties.getDefaultInstance())
            .withDefaultSchema(context)
            .withSourceVersionMapping(Collections.emptyMap())
            .build();
    return sabotQueryContext
        .getQueryContextCreator()
        .createNewQueryContext(
            session, queryId, null, Long.MAX_VALUE, Predicates.alwaysTrue(), null, null);
  }
}
