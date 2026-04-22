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

import static com.dremio.exec.catalog.CatalogOptions.RESTCATALOG_FOLDERS_SUPPORTED;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.catalog.model.ImmutableCatalogFolder;
import com.dremio.common.AutoCloseables;
import com.dremio.connector.metadata.DatasetHandle;
import com.dremio.connector.metadata.EntityPath;
import com.dremio.connector.metadata.GetDatasetOption;
import com.dremio.connector.metadata.SourceMetadata;
import com.dremio.connector.metadata.ViewDatasetHandle;
import com.dremio.connector.metadata.extensions.SupportsListingDatasets;
import com.dremio.datastore.LocalKVStoreProvider;
import com.dremio.datastore.api.LegacyKVStoreProvider;
import com.dremio.exec.store.DatasetRetrievalOptions;
import com.dremio.options.OptionManager;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceNotFoundException;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.NamespaceServiceImpl;
import com.dremio.service.namespace.NamespaceTestUtils;
import com.dremio.service.namespace.catalogpubsub.CatalogEventMessagePublisherProvider;
import com.dremio.service.namespace.catalogstatusevents.CatalogStatusEventsImpl;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.source.proto.MetadataPolicy;
import com.dremio.service.namespace.source.proto.SourceConfig;
import com.dremio.service.namespace.source.proto.UpdateMode;
import com.dremio.test.DremioTest;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class TestMetadataSynchronizerForFoldersIntegration {

  private static final String SOURCE = "test-source";
  private static final String FOLDER0 = "test-source.folder0";
  private static final String FOLDER1 = "test-source.folder1";
  private static final String FOLDER12 = "test-source.folder1.folder2";
  private static final String TABLE = "test-source.folder0.test-table";
  private static final String VIEW = "test-source.folder0.test-view";
  private static final List<String> TABLE_PATH = List.of("test-source", "folder0", "test-table");
  private static final List<String> VIEW_PATH = List.of("test-source", "folder0", "test-view");
  private static final List<String> FOLDER0_PATH = Arrays.asList("test-source", "folder0");
  private static final List<String> FOLDER1_PATH = Arrays.asList("test-source", "folder1");
  private static final List<String> FOLDER12_PATH =
      Arrays.asList("test-source", "folder1", "folder2");
  private static final String FOLDER0_STORAGE_URI = "file:///tmp/folder0";
  private static final String FOLDER1_STORAGE_URI = "file:///tmp/folder1";
  private static final String FOLDER12_STORAGE_URI = "file:///tmp/folder1/folder2";

  private static LegacyKVStoreProvider kvStoreProvider;
  private static NamespaceService namespaceService;
  private static MetadataPolicy metadataPolicy;
  private static OptionManager optionManager;
  private static DatasetSaver datasetSaver;
  private static NamespaceKey sourceKey;
  private static SourceConfig sourceConfig;
  private static DatasetRetrievalOptions retrievalOptions;

  abstract static class MockSourceMetadata
      implements SupportsListingDatasets, SourceMetadata, SupportsFolderIngestion {}

  @BeforeAll
  public static void setup() throws Exception {
    LocalKVStoreProvider storeProvider =
        new LocalKVStoreProvider(DremioTest.CLASSPATH_SCAN_RESULT, null, true, false);
    storeProvider.start();
    kvStoreProvider = storeProvider.asLegacy();
    kvStoreProvider.start();
    namespaceService =
        new NamespaceServiceImpl(
            storeProvider,
            new CatalogStatusEventsImpl(),
            CatalogEventMessagePublisherProvider.NO_OP);
    sourceKey = new NamespaceKey(SOURCE);
    sourceConfig = NamespaceTestUtils.addSource(namespaceService, SOURCE);
    metadataPolicy =
        new MetadataPolicy()
            .setDatasetUpdateMode(UpdateMode.PREFETCH_QUERIED)
            .setDeleteUnavailableDatasets(true);
    optionManager = mock(OptionManager.class);
    when(optionManager.getOption(RESTCATALOG_FOLDERS_SUPPORTED)).thenReturn(true);
    datasetSaver = new DatasetSaverImpl(namespaceService, (NamespaceKey key) -> {}, optionManager);
    retrievalOptions =
        DatasetRetrievalOptions.DEFAULT.toBuilder().setDeleteUnavailableDatasets(true).build();
  }

  @AfterAll
  public static void teardownClass() throws Exception {
    AutoCloseables.close(kvStoreProvider);
  }

  @AfterEach
  public void cleanupTest() throws Exception {
    namespaceService.deleteSourceChildren(
        sourceKey, sourceConfig.getTag(), (DatasetConfig datasetConfig) -> {});
  }

  @Test
  public void validateNewSourceHappyPath() throws Exception {
    ManagedStoragePlugin.MetadataBridge bridge = mock(ManagedStoragePlugin.MetadataBridge.class);
    MockSourceMetadata sourceMetadata = mock(MockSourceMetadata.class);
    when(bridge.getMetadata()).thenReturn(Optional.of(sourceMetadata));
    DatasetHandle datasetHandle = mock(DatasetHandle.class);
    when(datasetHandle.getDatasetPath()).thenReturn(new EntityPath(TABLE_PATH));
    ViewDatasetHandle viewDatasetHandle = mock(ViewDatasetHandle.class);
    when(viewDatasetHandle.getDatasetPath()).thenReturn(new EntityPath(VIEW_PATH));
    when(sourceMetadata.listDatasetHandles(any(GetDatasetOption[].class)))
        .thenReturn(() -> Arrays.asList(datasetHandle, viewDatasetHandle).iterator());
    when(sourceMetadata.getFolderListing())
        .thenReturn(
            new MockFolderListing(
                Arrays.asList(
                    (new ImmutableCatalogFolder.Builder())
                        .setFullPath(FOLDER0_PATH)
                        .setStorageUri(FOLDER0_STORAGE_URI)
                        .build(),
                    (new ImmutableCatalogFolder.Builder())
                        .setFullPath(FOLDER1_PATH)
                        .setStorageUri(FOLDER1_STORAGE_URI)
                        .build(),
                    (new ImmutableCatalogFolder.Builder())
                        .setFullPath(FOLDER12_PATH)
                        .setStorageUri(FOLDER12_STORAGE_URI)
                        .build())));

    MetadataSynchronizer synchronizeRun =
        new MetadataSynchronizer(
            namespaceService,
            sourceKey,
            bridge,
            metadataPolicy,
            datasetSaver,
            retrievalOptions,
            optionManager,
            null);
    synchronizeRun.go();

    Assertions.assertNotNull(namespaceService.getDataset(new NamespaceKey(TABLE_PATH)));
    Assertions.assertNotNull(namespaceService.getDataset(new NamespaceKey(VIEW_PATH)));
    Assertions.assertEquals(
        namespaceService.getFolder(new NamespaceKey(FOLDER0_PATH)).getStorageUri(),
        FOLDER0_STORAGE_URI);
    Assertions.assertEquals(
        namespaceService.getFolder(new NamespaceKey(FOLDER1_PATH)).getStorageUri(),
        FOLDER1_STORAGE_URI);
    Assertions.assertEquals(
        namespaceService.getFolder(new NamespaceKey(FOLDER12_PATH)).getStorageUri(),
        FOLDER12_STORAGE_URI);
  }

  @Test
  public void validateExistingSourceHappyPath() throws Exception {
    createNamespaceServiceWithEntities();
    ManagedStoragePlugin.MetadataBridge bridge = mock(ManagedStoragePlugin.MetadataBridge.class);
    MockSourceMetadata sourceMetadata = mock(MockSourceMetadata.class);
    when(bridge.getMetadata()).thenReturn(Optional.of(sourceMetadata));
    DatasetHandle datasetHandle = mock(DatasetHandle.class);
    when(datasetHandle.getDatasetPath()).thenReturn(new EntityPath(TABLE_PATH));
    ViewDatasetHandle viewDatasetHandle = mock(ViewDatasetHandle.class);
    when(viewDatasetHandle.getDatasetPath()).thenReturn(new EntityPath(VIEW_PATH));
    when(sourceMetadata.listDatasetHandles(any(GetDatasetOption[].class)))
        .thenReturn(() -> Arrays.asList(datasetHandle, viewDatasetHandle).iterator());
    when(sourceMetadata.getFolderListing())
        .thenReturn(
            new MockFolderListing(
                Arrays.asList(
                    (new ImmutableCatalogFolder.Builder())
                        .setFullPath(FOLDER0_PATH)
                        .setStorageUri(FOLDER0_STORAGE_URI)
                        .build(),
                    (new ImmutableCatalogFolder.Builder())
                        .setFullPath(FOLDER1_PATH)
                        .setStorageUri(FOLDER1_STORAGE_URI)
                        .build(),
                    (new ImmutableCatalogFolder.Builder())
                        .setFullPath(FOLDER12_PATH)
                        .setStorageUri(FOLDER12_STORAGE_URI)
                        .build())));

    MetadataSynchronizer synchronizeRun =
        new MetadataSynchronizer(
            namespaceService,
            sourceKey,
            bridge,
            metadataPolicy,
            datasetSaver,
            retrievalOptions,
            optionManager,
            null);
    synchronizeRun.go();

    Assertions.assertNotNull(namespaceService.getDataset(new NamespaceKey(TABLE_PATH)));
    Assertions.assertNotNull(namespaceService.getDataset(new NamespaceKey(VIEW_PATH)));
    Assertions.assertEquals(
        namespaceService.getFolder(new NamespaceKey(FOLDER0_PATH)).getStorageUri(),
        FOLDER0_STORAGE_URI);
    Assertions.assertEquals(
        namespaceService.getFolder(new NamespaceKey(FOLDER1_PATH)).getStorageUri(),
        FOLDER1_STORAGE_URI);
    Assertions.assertEquals(
        namespaceService.getFolder(new NamespaceKey(FOLDER12_PATH)).getStorageUri(),
        FOLDER12_STORAGE_URI);
  }

  @Test
  public void validateConnectorException() throws Exception {
    ManagedStoragePlugin.MetadataBridge bridge = mock(ManagedStoragePlugin.MetadataBridge.class);
    MockSourceMetadata sourceMetadata = mock(MockSourceMetadata.class);
    when(bridge.getMetadata()).thenReturn(Optional.of(sourceMetadata));
    DatasetHandle datasetHandle = mock(DatasetHandle.class);
    when(datasetHandle.getDatasetPath()).thenReturn(new EntityPath(TABLE_PATH));
    ViewDatasetHandle viewDatasetHandle = mock(ViewDatasetHandle.class);
    when(viewDatasetHandle.getDatasetPath()).thenReturn(new EntityPath(VIEW_PATH));
    when(sourceMetadata.listDatasetHandles(any(GetDatasetOption[].class)))
        .thenReturn(() -> Arrays.asList(datasetHandle, viewDatasetHandle).iterator());
    FolderListing folderListing = mock(FolderListing.class);
    Iterator<ImmutableCatalogFolder> errorIterator = mock(Iterator.class);
    when(errorIterator.hasNext()).thenThrow(new ConnectorRuntimeException("error"));
    when(folderListing.iterator()).thenReturn(errorIterator);
    when(sourceMetadata.getFolderListing()).thenReturn(folderListing);

    MetadataSynchronizer synchronizeRun =
        new MetadataSynchronizer(
            namespaceService,
            sourceKey,
            bridge,
            metadataPolicy,
            datasetSaver,
            retrievalOptions,
            optionManager,
            null);
    synchronizeRun.go();

    Assertions.assertNotNull(namespaceService.getDataset(new NamespaceKey(TABLE_PATH)));
    Assertions.assertNotNull(namespaceService.getDataset(new NamespaceKey(VIEW_PATH)));
    Assertions.assertNull(
        namespaceService.getFolder(new NamespaceKey(FOLDER0_PATH)).getStorageUri());
    Assertions.assertThrows(
        NamespaceNotFoundException.class,
        () -> namespaceService.getFolder(new NamespaceKey(FOLDER1_PATH)));
  }

  private void createNamespaceServiceWithEntities() throws Exception {
    NamespaceTestUtils.addFolder(namespaceService, FOLDER0);
    NamespaceTestUtils.addFolder(namespaceService, FOLDER1);
    NamespaceTestUtils.addFolder(namespaceService, FOLDER12);
    NamespaceTestUtils.addPhysicalDS(namespaceService, TABLE);
    NamespaceTestUtils.addVirtualDS(
        namespaceService, VIEW, "SELECT * FROM test-source.public.test-table");
  }
}
