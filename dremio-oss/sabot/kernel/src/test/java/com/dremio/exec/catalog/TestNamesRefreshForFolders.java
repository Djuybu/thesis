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
import static com.dremio.exec.catalog.CatalogOptions.SINGLE_SPLIT_PARTITION_MAX;
import static com.dremio.exec.catalog.CatalogOptions.SPLIT_COMPRESSION_TYPE;
import static com.dremio.service.namespace.NamespaceOptions.DATASET_METADATA_CONSISTENCY_VALIDATE;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.catalog.model.ImmutableCatalogFolder;
import com.dremio.common.AutoCloseables;
import com.dremio.connector.metadata.GetDatasetOption;
import com.dremio.connector.metadata.SourceMetadata;
import com.dremio.connector.metadata.extensions.SupportsListingDatasets;
import com.dremio.datastore.LocalKVStoreProvider;
import com.dremio.datastore.api.LegacyKVStore;
import com.dremio.datastore.api.LegacyKVStoreProvider;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.store.DatasetRetrievalOptions;
import com.dremio.options.OptionManager;
import com.dremio.options.TypeValidators;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.NamespaceServiceImpl;
import com.dremio.service.namespace.NamespaceTestUtils;
import com.dremio.service.namespace.catalogpubsub.CatalogEventMessagePublisherProvider;
import com.dremio.service.namespace.catalogstatusevents.CatalogStatusEventsImpl;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.source.proto.MetadataPolicy;
import com.dremio.service.namespace.source.proto.SourceConfig;
import com.dremio.service.namespace.source.proto.UpdateMode;
import com.dremio.service.namespace.space.proto.FolderConfig;
import com.dremio.service.scheduler.ModifiableLocalSchedulerService;
import com.dremio.test.DremioTest;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

public class TestNamesRefreshForFolders {

  private static final String SOURCE = "test-source";
  private static final String TABLE = "test-source.public.test-table";
  private static final String VIEW = "test-source.test-view";
  private static final List<String> PARENT =
      ImmutableList.of("test-source", "private", "test-table");

  private static LegacyKVStoreProvider kvStoreProvider;
  private static NamespaceService namespaceService;
  private static MetadataPolicy metadataPolicy;
  private static OptionManager optionManager = mock(OptionManager.class);
  private static DatasetSaver datasetSaver;
  private static NamespaceKey sourceKey;
  private static SourceConfig sourceConfig;
  private static DatasetRetrievalOptions retrievalOptions;
  private static ModifiableLocalSchedulerService modifiableSchedulerService;
  private static @Mock MetadataRefreshInfoBroadcaster broadcaster;

  private static TypeValidators.PositiveLongValidator option =
      ExecConstants.MAX_CONCURRENT_METADATA_REFRESHES;

  abstract class TestSourceMetadata
      implements SupportsFolderIngestion, SupportsListingDatasets, SourceMetadata {}

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
    datasetSaver = new DatasetSaverImpl(namespaceService, (NamespaceKey key) -> {}, optionManager);
    retrievalOptions =
        DatasetRetrievalOptions.DEFAULT.toBuilder().setDeleteUnavailableDatasets(true).build();
    modifiableSchedulerService =
        new ModifiableLocalSchedulerService(
            1, "modifiable-scheduler-", option, () -> optionManager);
  }

  @AfterAll
  public static void teardownClass() throws Exception {
    AutoCloseables.close(kvStoreProvider);
  }

  private void setupFoldersInNamespace() throws Exception {
    NamespaceTestUtils.addFolder(namespaceService, "test-source.test-folder1");
    NamespaceTestUtils.addFolder(namespaceService, "test-source.test-folder2");
  }

  private void setupDatasetsInNamespace() throws Exception {
    NamespaceTestUtils.addPhysicalDS(namespaceService, TABLE);
    NamespaceTestUtils.addVirtualDS(
        namespaceService, VIEW, "SELECT * FROM test-source.public.test-table");
  }

  @BeforeEach
  public void setupTest() throws Exception {
    // Using the default values of these support keys. They do not really affect the tests.
    when(optionManager.getOption(SPLIT_COMPRESSION_TYPE)).thenReturn("SNAPPY");
    when(optionManager.getOption(SINGLE_SPLIT_PARTITION_MAX)).thenReturn(500L);
    when(optionManager.getOption(DATASET_METADATA_CONSISTENCY_VALIDATE)).thenReturn(false);

    // Support keys that will be really used in some tests
    when(optionManager.getOption(RESTCATALOG_FOLDERS_SUPPORTED)).thenReturn(true);
    setupDatasetsInNamespace();
    setupFoldersInNamespace();
  }

  @AfterEach
  public void cleanupTest() throws Exception {
    namespaceService.deleteSourceChildren(
        sourceKey, sourceConfig.getTag(), (DatasetConfig datasetConfig) -> {});
  }

  @Test
  public void testNamesRefreshOnSourceWithFolders() throws Exception {
    ImmutableCatalogFolder folderFromPlugin = mock(ImmutableCatalogFolder.class);
    when(folderFromPlugin.fullPath())
        .thenReturn(ImmutableList.of("test-source", "new-folder-from-plugin"));
    FolderListing folderListing = mock(FolderListing.class);
    when(folderListing.iterator()).thenReturn(Lists.newArrayList(folderFromPlugin).iterator());
    SupportsListingDatasets sourceMetadata = mock(TestSourceMetadata.class);
    when(sourceMetadata.listDatasetHandles(any(GetDatasetOption[].class)))
        .thenReturn(() -> Collections.emptyIterator());

    when(optionManager.getOption(RESTCATALOG_FOLDERS_SUPPORTED)).thenReturn(true);

    SupportsFolderIngestion supportsFolderIngestion = (SupportsFolderIngestion) sourceMetadata;
    when(supportsFolderIngestion.getFolderListing()).thenReturn(folderListing);
    ManagedStoragePlugin.MetadataBridge bridge = mock(ManagedStoragePlugin.MetadataBridge.class);
    when(bridge.getNamespaceService()).thenReturn(namespaceService);
    when(bridge.getMetadata()).thenReturn(Optional.of((SourceMetadata) sourceMetadata));
    when(bridge.getDefaultRetrievalOptions()).thenReturn(retrievalOptions);
    List<FolderConfig> folderConfigList =
        namespaceService.getFolders(new NamespaceKey(List.of("test-source")));

    // Before doing refresh where we added 3 folders in setup which will no longer present after
    // refresh as source only returns "new-folder-from-plugin"
    assertThat(folderConfigList.size()).isEqualTo(3);

    // Test refreshDatasetNames for source with folder
    SourceMetadataManager sourceMetadataManager =
        new SourceMetadataManager(
            new NamespaceKey("test-source"),
            modifiableSchedulerService,
            true,
            mock(LegacyKVStore.class),
            bridge,
            optionManager,
            CatalogServiceMonitor.DEFAULT,
            () -> broadcaster);

    // Test names refresh
    sourceMetadataManager.refresh(
        SourceUpdateType.NAMES,
        new MetadataPolicy().setAutoPromoteDatasets(true).setDeleteUnavailableDatasets(true),
        true);

    // Verify the total folders after refresh, which is only "new-folder-from-plugin"; refreshed by
    // source metadata manager
    folderConfigList = namespaceService.getFolders(new NamespaceKey(List.of("test-source")));
    assertThat(folderConfigList.size()).isEqualTo(1);
    FolderConfig folderConfig = folderConfigList.get(0);
    Assertions.assertNotNull(folderConfig);
  }

  @Test
  public void testNamesRefreshWithEmptyFolderListing() throws Exception {
    when(optionManager.getOption(RESTCATALOG_FOLDERS_SUPPORTED)).thenReturn(true);

    int numFoldersBeforeRefresh =
        namespaceService.getFolders(new NamespaceKey(List.of("test-source"))).size();
    assertThat(numFoldersBeforeRefresh).isEqualTo(3);

    FolderListing folderListing = mock(FolderListing.class);
    when(folderListing.iterator()).thenReturn(Collections.emptyIterator());

    SupportsFolderIngestion supportsFolderIngestion = mock(TestSourceMetadata.class);
    when(supportsFolderIngestion.getFolderListing()).thenReturn(folderListing);

    ManagedStoragePlugin.MetadataBridge bridge = mock(ManagedStoragePlugin.MetadataBridge.class);
    when(bridge.getNamespaceService()).thenReturn(namespaceService);
    when(bridge.getMetadata()).thenReturn(Optional.of((SourceMetadata) supportsFolderIngestion));
    when(bridge.getDefaultRetrievalOptions()).thenReturn(retrievalOptions);

    SourceMetadataManager sourceMetadataManager =
        new SourceMetadataManager(
            new NamespaceKey("test-source"),
            modifiableSchedulerService,
            true,
            mock(LegacyKVStore.class),
            bridge,
            optionManager,
            CatalogServiceMonitor.DEFAULT,
            () -> broadcaster);

    sourceMetadataManager.refresh(
        SourceUpdateType.NAMES,
        new MetadataPolicy().setAutoPromoteDatasets(true).setDeleteUnavailableDatasets(true),
        true);

    List<FolderConfig> folderConfigs =
        namespaceService.getFolders(new NamespaceKey(List.of("test-source")));
    assertThat(folderConfigs.size()).isEqualTo(0);
  }
}
