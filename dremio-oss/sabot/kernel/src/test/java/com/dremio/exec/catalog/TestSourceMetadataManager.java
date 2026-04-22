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

import static com.dremio.exec.catalog.CatalogOptions.RESTCATALOG_VIEWS_SUPPORTED;
import static com.dremio.exec.catalog.CatalogOptions.SOURCE_METADATA_MANAGER_WAKEUP_FREQUENCY_MS;
import static com.dremio.exec.proto.UserBitShared.DremioPBError.ErrorType.VALIDATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.catalog.exception.SourceMalfunctionException;
import com.dremio.common.AutoCloseables;
import com.dremio.connector.ConnectorException;
import com.dremio.connector.metadata.BytesOutput;
import com.dremio.connector.metadata.DatasetHandle;
import com.dremio.connector.metadata.DatasetMetadata;
import com.dremio.connector.metadata.DatasetNotFoundException;
import com.dremio.connector.metadata.DatasetStats;
import com.dremio.connector.metadata.EntityPath;
import com.dremio.connector.metadata.GetDatasetOption;
import com.dremio.connector.metadata.GetMetadataOption;
import com.dremio.connector.metadata.ListPartitionChunkOption;
import com.dremio.connector.metadata.PartitionChunkListing;
import com.dremio.connector.metadata.SourceMetadata;
import com.dremio.connector.metadata.ViewDatasetHandle;
import com.dremio.connector.metadata.extensions.SupportsReadSignature;
import com.dremio.connector.metadata.extensions.ValidateMetadataOption;
import com.dremio.context.RequestContext;
import com.dremio.context.UserContext;
import com.dremio.datastore.api.LegacyKVStore;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.planner.cost.ScanCostFactor;
import com.dremio.exec.store.DatasetRetrievalOptions;
import com.dremio.exec.store.SchemaConfig;
import com.dremio.options.OptionManager;
import com.dremio.options.TypeValidators.PositiveLongValidator;
import com.dremio.service.namespace.DatasetMetadataSaver;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceNotFoundException;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.SourceState;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.dataset.proto.IcebergViewAttributes;
import com.dremio.service.namespace.dataset.proto.ReadDefinition;
import com.dremio.service.namespace.dataset.proto.VirtualDataset;
import com.dremio.service.namespace.proto.EntityId;
import com.dremio.service.namespace.proto.NameSpaceContainer;
import com.dremio.service.namespace.source.proto.MetadataPolicy;
import com.dremio.service.namespace.source.proto.SourceChangeState;
import com.dremio.service.namespace.source.proto.SourceConfig;
import com.dremio.service.namespace.source.proto.SourceInternalData;
import com.dremio.service.namespace.source.proto.UpdateMode;
import com.dremio.service.orphanage.Orphanage;
import com.dremio.service.scheduler.Cancellable;
import com.dremio.service.scheduler.ModifiableLocalSchedulerService;
import com.dremio.service.scheduler.ModifiableSchedulerService;
import com.dremio.service.scheduler.Schedule;
import com.dremio.test.UserExceptionAssert;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;
import org.apache.arrow.vector.types.pojo.Schema;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

public class TestSourceMetadataManager {
  private static final int MAX_COLUMNS = 800;
  private static final int MAX_NESTED_LEVELS = 16;
  private OptionManager optionManager;
  private final MetadataRefreshInfoBroadcaster broadcaster =
      mock(MetadataRefreshInfoBroadcaster.class);
  private ModifiableSchedulerService modifiableSchedulerService;

  @Captor private ArgumentCaptor<Runnable> runnableCaptor;

  @BeforeEach
  public void setup() {
    MockitoAnnotations.openMocks(this);
    optionManager = mock(OptionManager.class);
    when(optionManager.getOption(eq(CatalogOptions.SPLIT_COMPRESSION_TYPE)))
        .thenAnswer((Answer) invocation -> NamespaceService.SplitCompression.SNAPPY.toString());
    doReturn(SOURCE_METADATA_MANAGER_WAKEUP_FREQUENCY_MS.getDefault())
        .when(optionManager)
        .getOption(SOURCE_METADATA_MANAGER_WAKEUP_FREQUENCY_MS.getOptionName());

    doNothing().when(broadcaster).communicateChange(any());

    PositiveLongValidator option = ExecConstants.MAX_CONCURRENT_METADATA_REFRESHES;
    when(optionManager.getOption(option)).thenReturn(1L);
    modifiableSchedulerService =
        new ModifiableLocalSchedulerService(
            1, "modifiable-scheduler-", option, () -> optionManager);
  }

  @AfterEach
  public void tearDown() throws Exception {
    AutoCloseables.close(modifiableSchedulerService);
  }

  @Test
  public void testWakeupTaskWrappedWithSystemUser() throws Exception {
    ModifiableSchedulerService modifiableSchedulerService = mock(ModifiableSchedulerService.class);
    LegacyKVStore<NamespaceKey, SourceInternalData> sourceDataStore = mock(LegacyKVStore.class);
    ManagedStoragePlugin.MetadataBridge bridge = mock(ManagedStoragePlugin.MetadataBridge.class);
    CatalogServiceMonitor catalogServiceMonitor = mock(CatalogServiceMonitor.class);
    Cancellable cancellable = mock(Cancellable.class);
    when(modifiableSchedulerService.schedule(any(Schedule.class), any(Runnable.class)))
        .thenReturn(cancellable);
    doNothing().when(catalogServiceMonitor).onWakeup();
    SourceMetadataManager manager =
        new SourceMetadataManager(
            new NamespaceKey("test-source"),
            modifiableSchedulerService,
            true,
            sourceDataStore,
            bridge,
            optionManager,
            catalogServiceMonitor,
            () -> null);
    verify(modifiableSchedulerService).schedule(any(Schedule.class), runnableCaptor.capture());
    Runnable wakeupWorker = runnableCaptor.getValue();
    doAnswer(
            invocation -> {
              UserContext userContext = RequestContext.current().get(UserContext.CTX_KEY);
              assertEquals(UserContext.SYSTEM_USER_CONTEXT, userContext);
              return null;
            })
        .when(catalogServiceMonitor)
        .onWakeup();
    wakeupWorker.run();
    verify(catalogServiceMonitor).onWakeup();
  }

  @Test
  public void testRefreshDataset_deleteUnavailableDataset() throws Exception {
    NamespaceService ns = mock(NamespaceService.class);
    Orphanage orphanage = mock(Orphanage.class);

    when(ns.getDataset(any()))
        .thenReturn(
            new DatasetConfig()
                .setTag("0")
                .setReadDefinition(new ReadDefinition())
                .setFullPathList(ImmutableList.of("one", "two")));

    boolean[] deleted = new boolean[] {false};
    doAnswer(
            invocation -> {
              deleted[0] = true;
              return null;
            })
        .when(ns)
        .deleteDataset(any(), anyString());

    ExtendedStoragePlugin sp = mock(ExtendedStoragePlugin.class);
    when(sp.getDatasetHandle(any(), any(), any())).thenReturn(Optional.empty());

    ManagedStoragePlugin.MetadataBridge msp = mock(ManagedStoragePlugin.MetadataBridge.class);
    when(msp.getMetadata()).thenReturn(Optional.of(sp));
    when(msp.getMetadataPolicy())
        .thenReturn(new MetadataPolicy().setDeleteUnavailableDatasets(false));
    when(msp.getMaxMetadataColumns()).thenReturn(MAX_COLUMNS);
    when(msp.getMaxNestedLevels()).thenReturn(MAX_NESTED_LEVELS);
    when(msp.getNamespaceService()).thenReturn(ns);
    when(msp.getOrphanage()).thenReturn(orphanage);

    //noinspection unchecked
    SourceMetadataManager manager =
        new SourceMetadataManager(
            new NamespaceKey("joker"),
            modifiableSchedulerService,
            true,
            mock(LegacyKVStore.class),
            msp,
            optionManager,
            CatalogServiceMonitor.DEFAULT,
            () -> broadcaster);

    assertEquals(
        DatasetCatalog.UpdateStatus.DELETED,
        manager.refreshDataset(new NamespaceKey(""), DatasetRetrievalOptions.DEFAULT));
    assertTrue(deleted[0]);
  }

  @Test
  public void testRefreshDataset_doNotDeleteUnavailableDataset() throws Exception {
    NamespaceService ns = mock(NamespaceService.class);
    when(ns.getDataset(any()))
        .thenReturn(
            new DatasetConfig()
                .setReadDefinition(new ReadDefinition())
                .setFullPathList(ImmutableList.of("one", "two")));

    doThrow(new IllegalStateException("should not invoke deleteDataset()"))
        .when(ns)
        .deleteDataset(any(), anyString());

    ExtendedStoragePlugin sp = mock(ExtendedStoragePlugin.class);
    when(sp.getDatasetHandle(any(), any(), any())).thenReturn(Optional.empty());

    ManagedStoragePlugin.MetadataBridge msp = mock(ManagedStoragePlugin.MetadataBridge.class);
    when(msp.getMetadata()).thenReturn(Optional.of(sp));
    when(msp.getMetadataPolicy())
        .thenReturn(new MetadataPolicy().setDeleteUnavailableDatasets(false));
    when(msp.getMaxMetadataColumns()).thenReturn(MAX_COLUMNS);
    when(msp.getMaxNestedLevels()).thenReturn(MAX_NESTED_LEVELS);
    when(msp.getNamespaceService()).thenReturn(ns);

    //noinspection unchecked
    SourceMetadataManager manager =
        new SourceMetadataManager(
            new NamespaceKey("joker"),
            modifiableSchedulerService,
            true,
            mock(LegacyKVStore.class),
            msp,
            optionManager,
            CatalogServiceMonitor.DEFAULT,
            () -> broadcaster);

    assertEquals(
        DatasetCatalog.UpdateStatus.UNCHANGED,
        manager.refreshDataset(
            new NamespaceKey(""),
            DatasetRetrievalOptions.DEFAULT.toBuilder()
                .setDeleteUnavailableDatasets(false)
                .build()));
  }

  @Test
  public void testRefreshDataset_deleteUnavailableDatasetWithoutDefinition() throws Exception {
    NamespaceService ns = mock(NamespaceService.class);
    Orphanage orphanage = mock(Orphanage.class);
    when(ns.getDataset(any()))
        .thenReturn(
            new DatasetConfig().setTag("0").setFullPathList(ImmutableList.of("one", "two")));

    boolean[] deleted = new boolean[] {false};
    doAnswer(
            invocation -> {
              deleted[0] = true;
              return null;
            })
        .when(ns)
        .deleteDataset(any(), anyString());

    ExtendedStoragePlugin sp = mock(ExtendedStoragePlugin.class);
    when(sp.getDatasetHandle(any(), any(), any())).thenReturn(Optional.empty());

    ManagedStoragePlugin.MetadataBridge msp = mock(ManagedStoragePlugin.MetadataBridge.class);
    when(msp.getMetadata()).thenReturn(Optional.of(sp));
    when(msp.getMetadataPolicy())
        .thenReturn(new MetadataPolicy().setDeleteUnavailableDatasets(false));
    when(msp.getMaxMetadataColumns()).thenReturn(MAX_COLUMNS);
    when(msp.getMaxNestedLevels()).thenReturn(MAX_NESTED_LEVELS);
    when(msp.getNamespaceService()).thenReturn(ns);
    when(msp.getOrphanage()).thenReturn(orphanage);

    //noinspection unchecked
    SourceMetadataManager manager =
        new SourceMetadataManager(
            new NamespaceKey("joker"),
            modifiableSchedulerService,
            true,
            mock(LegacyKVStore.class),
            msp,
            optionManager,
            CatalogServiceMonitor.DEFAULT,
            () -> broadcaster);

    assertEquals(
        DatasetCatalog.UpdateStatus.DELETED,
        manager.refreshDataset(new NamespaceKey(""), DatasetRetrievalOptions.DEFAULT));
    assertTrue(deleted[0]);
  }

  @Test
  public void testRefreshDataset_doNotDeleteUnavailableDatasetWithoutDefinition() throws Exception {
    NamespaceService ns = mock(NamespaceService.class);
    when(ns.getDataset(any()))
        .thenReturn(new DatasetConfig().setFullPathList(ImmutableList.of("one", "two")));

    doThrow(new IllegalStateException("should not invoke deleteDataset()"))
        .when(ns)
        .deleteDataset(any(), anyString());

    ExtendedStoragePlugin sp = mock(ExtendedStoragePlugin.class);
    when(sp.getDatasetHandle(any(), any(), any())).thenReturn(Optional.empty());

    ManagedStoragePlugin.MetadataBridge msp = mock(ManagedStoragePlugin.MetadataBridge.class);
    when(msp.getMetadata()).thenReturn(Optional.of(sp));
    when(msp.getMetadataPolicy())
        .thenReturn(new MetadataPolicy().setDeleteUnavailableDatasets(false));
    when(msp.getMaxMetadataColumns()).thenReturn(MAX_COLUMNS);
    when(msp.getMaxNestedLevels()).thenReturn(MAX_NESTED_LEVELS);
    when(msp.getNamespaceService()).thenReturn(ns);

    //noinspection unchecked
    SourceMetadataManager manager =
        new SourceMetadataManager(
            new NamespaceKey("joker"),
            modifiableSchedulerService,
            true,
            mock(LegacyKVStore.class),
            msp,
            optionManager,
            CatalogServiceMonitor.DEFAULT,
            () -> broadcaster);

    assertEquals(
        DatasetCatalog.UpdateStatus.UNCHANGED,
        manager.refreshDataset(
            new NamespaceKey(""),
            DatasetRetrievalOptions.DEFAULT.toBuilder()
                .setDeleteUnavailableDatasets(false)
                .build()));
  }

  @Test
  public void testRefreshDataset_handleUnavailableSourceDataset() throws Exception {
    NamespaceService ns = mock(NamespaceService.class);

    doThrow(new NamespaceNotFoundException(new NamespaceKey(""), "not found"))
        .when(ns)
        .getDataset(any());

    ExtendedStoragePlugin sp = mock(ExtendedStoragePlugin.class);
    when(sp.getDatasetHandle(any(), any(), any())).thenReturn(Optional.empty());

    ManagedStoragePlugin.MetadataBridge msp = mock(ManagedStoragePlugin.MetadataBridge.class);
    when(msp.getMetadata()).thenReturn(Optional.of(sp));
    when(msp.getMetadataPolicy())
        .thenReturn(new MetadataPolicy().setDeleteUnavailableDatasets(false));
    when(msp.getMaxMetadataColumns()).thenReturn(MAX_COLUMNS);
    when(msp.getMaxNestedLevels()).thenReturn(MAX_NESTED_LEVELS);
    when(msp.getNamespaceService()).thenReturn(ns);

    SourceMetadataManager manager =
        new SourceMetadataManager(
            new NamespaceKey("joker"),
            modifiableSchedulerService,
            true,
            mock(LegacyKVStore.class),
            msp,
            optionManager,
            CatalogServiceMonitor.DEFAULT,
            () -> broadcaster);

    assertThatThrownBy(
            () ->
                manager.refreshDataset(
                    new NamespaceKey("three"),
                    DatasetRetrievalOptions.DEFAULT.toBuilder()
                        .setForceUpdate(true)
                        .setMaxMetadataLeafColumns(1)
                        .build()))
        .isInstanceOf(DatasetNotFoundException.class);
  }

  @Test
  public void testRefreshDataset_checkForceUpdate() throws Exception {
    NamespaceService ns = mock(NamespaceService.class);
    when(ns.getDataset(any())).thenReturn(null);

    DatasetMetadataSaver saver = mock(DatasetMetadataSaver.class);
    doNothing().when(saver).saveDataset(any(), anyBoolean(), any(), any());
    when(ns.newDatasetMetadataSaver(any(), any(), any(), anyLong(), anyBoolean()))
        .thenReturn(saver);

    ExtendedStoragePlugin sp = mock(ExtendedStoragePlugin.class);
    DatasetHandle handle = () -> new EntityPath(Lists.newArrayList("one"));
    when(sp.getDatasetHandle(any(), any(GetDatasetOption[].class))).thenReturn(Optional.of(handle));
    when(sp.provideSignature(any(), any())).thenReturn(BytesOutput.NONE);

    final boolean[] forced = new boolean[] {false};
    doAnswer(
            invocation -> {
              forced[0] = true;
              return DatasetMetadata.of(
                  DatasetStats.of(0, ScanCostFactor.OTHER.getFactor()),
                  new Schema(new ArrayList<>()));
            })
        .when(sp)
        .getDatasetMetadata(
            any(DatasetHandle.class),
            any(PartitionChunkListing.class),
            any(GetMetadataOption[].class));
    when(sp.listPartitionChunks(any(), any(ListPartitionChunkOption[].class)))
        .thenReturn(Collections::emptyIterator);
    when(sp.validateMetadata(any(), any(), any(), any(ValidateMetadataOption[].class)))
        .thenReturn(SupportsReadSignature.MetadataValidity.VALID);

    ManagedStoragePlugin.MetadataBridge msp = mock(ManagedStoragePlugin.MetadataBridge.class);
    when(msp.getMetadata()).thenReturn(Optional.of(sp));
    when(msp.getMetadataPolicy())
        .thenReturn(new MetadataPolicy().setDeleteUnavailableDatasets(false));
    when(msp.getMaxMetadataColumns()).thenReturn(MAX_COLUMNS);
    when(msp.getMaxNestedLevels()).thenReturn(MAX_NESTED_LEVELS);
    when(msp.getNamespaceService()).thenReturn(ns);

    //noinspection unchecked
    SourceMetadataManager manager =
        new SourceMetadataManager(
            new NamespaceKey("joker"),
            modifiableSchedulerService,
            true,
            mock(LegacyKVStore.class),
            msp,
            optionManager,
            CatalogServiceMonitor.DEFAULT,
            () -> broadcaster);

    manager.refreshDataset(
        new NamespaceKey(""),
        DatasetRetrievalOptions.DEFAULT.toBuilder().setForceUpdate(true).build());

    assertTrue(forced[0]);
  }

  @Test
  public void testRefreshDataset_dataSetPathCaseSensitivity() throws Exception {
    final String qualifier = "inspector";
    final String original = "testPath";
    final String capital = "TESTPATH";
    final ImmutableList<String> fullPathList = ImmutableList.of(qualifier, original);
    final EntityPath originalPath = new EntityPath(fullPathList);
    final EntityPath capitalPath = new EntityPath(ImmutableList.of(qualifier, capital));
    final DatasetHandle datasetHandle = () -> originalPath;
    final NamespaceKey dataSetKey = new NamespaceKey(ImmutableList.of(qualifier, capital));

    ExtendedStoragePlugin mockStoragePlugin = mock(ExtendedStoragePlugin.class);
    when(mockStoragePlugin.listDatasetHandles(any(GetDatasetOption[].class)))
        .thenReturn(Collections::emptyIterator);
    when(mockStoragePlugin.getDatasetHandle(eq(capitalPath), any(GetDatasetOption[].class)))
        .thenReturn(Optional.empty());
    when(mockStoragePlugin.getDatasetHandle(eq(originalPath), any(GetDatasetOption[].class)))
        .thenReturn(Optional.of(datasetHandle));
    when(mockStoragePlugin.getState()).thenReturn(SourceState.GOOD);
    when(mockStoragePlugin.listPartitionChunks(any(), any(ListPartitionChunkOption[].class)))
        .thenReturn(Collections::emptyIterator);
    when(mockStoragePlugin.validateMetadata(any(), any(), any()))
        .thenReturn(SupportsReadSignature.MetadataValidity.VALID);
    when(mockStoragePlugin.provideSignature(any(), any())).thenReturn(BytesOutput.NONE);
    final boolean[] forced = new boolean[] {false};
    doAnswer(
            invocation -> {
              forced[0] = true;
              return DatasetMetadata.of(
                  DatasetStats.of(0, ScanCostFactor.OTHER.getFactor()),
                  new Schema(new ArrayList<>()));
            })
        .when(mockStoragePlugin)
        .getDatasetMetadata(any(DatasetHandle.class), any(), any(), any(), any());

    NamespaceService ns = mock(NamespaceService.class);
    when(ns.getDataset(any())).thenReturn(MetadataObjectsUtils.newShallowConfig(datasetHandle));

    DatasetMetadataSaver saver = mock(DatasetMetadataSaver.class);
    doNothing().when(saver).saveDataset(any(), anyBoolean(), any(), any());
    when(ns.newDatasetMetadataSaver(any(), any(), any(), anyLong(), anyBoolean()))
        .thenReturn(saver);

    ManagedStoragePlugin.MetadataBridge msp = mock(ManagedStoragePlugin.MetadataBridge.class);
    when(msp.getMetadata()).thenReturn(Optional.of(mockStoragePlugin));
    when(msp.getMetadataPolicy()).thenReturn(new MetadataPolicy());
    when(msp.getNamespaceService()).thenReturn(ns);

    SourceMetadataManager manager =
        new SourceMetadataManager(
            dataSetKey,
            modifiableSchedulerService,
            true,
            mock(LegacyKVStore.class),
            msp,
            optionManager,
            CatalogServiceMonitor.DEFAULT,
            () -> broadcaster);

    assertEquals(
        DatasetCatalog.UpdateStatus.CHANGED,
        manager.refreshDataset(dataSetKey, DatasetRetrievalOptions.DEFAULT.toBuilder().build()));
  }

  @Test
  public void testRefreshDataset_exceedMaxColumnLimit() throws Exception {
    NamespaceService ns = mock(NamespaceService.class);
    when(ns.getDataset(any())).thenReturn(null);

    ExtendedStoragePlugin sp = mock(ExtendedStoragePlugin.class);

    DatasetHandle handle = () -> new EntityPath(Lists.newArrayList("one"));
    when(sp.getDatasetHandle(any(), any(GetDatasetOption[].class))).thenReturn(Optional.of(handle));
    when(sp.listPartitionChunks(any(), any(ListPartitionChunkOption[].class)))
        .thenReturn(Collections::emptyIterator);

    when(sp.validateMetadata(any(), eq(handle), any(), any(ValidateMetadataOption[].class)))
        .thenReturn(SupportsReadSignature.MetadataValidity.INVALID);
    doThrow(new ColumnCountTooLargeException(1))
        .when(sp)
        .getDatasetMetadata(eq(handle), any(), any(GetMetadataOption[].class));

    ManagedStoragePlugin.MetadataBridge msp = mock(ManagedStoragePlugin.MetadataBridge.class);
    when(msp.getMetadata()).thenReturn(Optional.of(sp));
    when(msp.getMetadataPolicy()).thenReturn(new MetadataPolicy());
    when(msp.getNamespaceService()).thenReturn(ns);

    //noinspection unchecked
    SourceMetadataManager manager =
        new SourceMetadataManager(
            new NamespaceKey("joker"),
            modifiableSchedulerService,
            true,
            mock(LegacyKVStore.class),
            msp,
            optionManager,
            CatalogServiceMonitor.DEFAULT,
            () -> broadcaster);

    UserExceptionAssert.assertThatThrownBy(
            () ->
                manager.refreshDataset(
                    new NamespaceKey(""),
                    DatasetRetrievalOptions.DEFAULT.toBuilder()
                        .setForceUpdate(true)
                        .setMaxMetadataLeafColumns(1)
                        .build()))
        .hasErrorType(VALIDATION)
        .hasMessageContaining("exceeded the maximum number of fields of 1");
  }

  @Test
  public void testIsStillValid_disableCheckMetadataValidity() throws Exception {
    NamespaceService ns = mock(NamespaceService.class);
    when(ns.getDataset(any())).thenReturn(null);

    ExtendedStoragePlugin sp = mock(ExtendedStoragePlugin.class);

    DatasetHandle handle = () -> new EntityPath(Lists.newArrayList("one"));
    when(sp.getDatasetHandle(any(), any(), any())).thenReturn(Optional.of(handle));
    when(sp.listPartitionChunks(any(), any(), any())).thenReturn(Collections::emptyIterator);

    MetadataPolicy metadataPolicy = new MetadataPolicy();
    metadataPolicy.setDatasetDefinitionExpireAfterMs(1000L);
    ManagedStoragePlugin.MetadataBridge msp = mock(ManagedStoragePlugin.MetadataBridge.class);
    when(msp.getMetadata()).thenReturn(Optional.of(sp));
    when(msp.getMetadataPolicy()).thenReturn(metadataPolicy);
    when(msp.getNamespaceService()).thenReturn(ns);

    //noinspection unchecked
    SourceMetadataManager manager =
        new SourceMetadataManager(
            new NamespaceKey("joker"),
            modifiableSchedulerService,
            true,
            mock(LegacyKVStore.class),
            msp,
            optionManager,
            CatalogServiceMonitor.DEFAULT,
            () -> broadcaster);

    final ReadDefinition readDefinition = new ReadDefinition();
    readDefinition.setSplitVersion(0L);

    final DatasetConfig datasetConfig = new DatasetConfig();
    datasetConfig.setType(DatasetType.PHYSICAL_DATASET);
    datasetConfig.setId(new EntityId("test"));
    datasetConfig.setFullPathList(ImmutableList.of("test", "file", "foobar"));
    datasetConfig.setReadDefinition(readDefinition);
    datasetConfig.setTotalNumSplits(0);

    final MetadataRequestOptions metadataRequestOptions =
        ImmutableMetadataRequestOptions.newBuilder()
            .setNewerThan(0L)
            .setSchemaConfig(SchemaConfig.newBuilder(CatalogUser.from("dremio")).build())
            .setCheckValidity(false)
            .build();
    assertTrue(manager.isStillValid(metadataRequestOptions, datasetConfig, null));
  }

  @Test
  public void testRefreshDataset_refreshNonexistentDataset() throws Exception {
    ManagedStoragePlugin.MetadataBridge msp = mock(ManagedStoragePlugin.MetadataBridge.class);
    NamespaceService ns = mock(NamespaceService.class);
    ExtendedStoragePlugin sp = mock(ExtendedStoragePlugin.class);

    when(sp.getDatasetHandle(any(), any(), any())).thenReturn(Optional.empty());
    when(msp.getMetadata()).thenReturn(Optional.of(sp));
    when(msp.getNamespaceService()).thenReturn(ns);
    doThrow(new NamespaceNotFoundException("not found")).when(ns).getDataset(any());

    SourceMetadataManager manager =
        new SourceMetadataManager(
            new NamespaceKey("testing"),
            modifiableSchedulerService,
            true,
            mock(LegacyKVStore.class),
            msp,
            optionManager,
            CatalogServiceMonitor.DEFAULT,
            () -> broadcaster);

    assertThatThrownBy(
            () ->
                manager.refreshDataset(
                    new NamespaceKey("nonExistentDataset"),
                    DatasetRetrievalOptions.DEFAULT.toBuilder().build()))
        .isInstanceOf(DatasetNotFoundException.class)
        .hasMessageContaining("Dataset [nonExistentDataset] not found.");
  }

  @Test
  public void testRefresh_sourceDoesNotExist() throws Exception {
    ManagedStoragePlugin.MetadataBridge metadataBridge =
        mock(ManagedStoragePlugin.MetadataBridge.class);
    NamespaceService namespaceService = mock(NamespaceService.class);
    ExtendedStoragePlugin storagePlugin = mock(ExtendedStoragePlugin.class);

    when(storagePlugin.getDatasetHandle(any(), any(), any())).thenReturn(Optional.empty());
    when(metadataBridge.getMetadata()).thenReturn(Optional.of(storagePlugin));
    when(metadataBridge.getNamespaceService()).thenReturn(namespaceService);

    NamespaceKey key = new NamespaceKey("testing");
    when(namespaceService.exists(eq(key), eq(NameSpaceContainer.Type.SOURCE))).thenReturn(false);

    SourceMetadataManager manager =
        new SourceMetadataManager(
            key,
            modifiableSchedulerService,
            true,
            mock(LegacyKVStore.class),
            metadataBridge,
            optionManager,
            CatalogServiceMonitor.DEFAULT,
            () -> broadcaster);
    RuntimeException e =
        assertThrows(
            RuntimeException.class,
            () ->
                manager.refresh(
                    SourceUpdateType.FULL,
                    new MetadataPolicy()
                        .setAutoPromoteDatasets(true)
                        .setDeleteUnavailableDatasets(true),
                    true));
    assertThat(e).hasMessage(String.format("Source %s does not exist", key.getName()));
  }

  private void setUpTestForBackgroudRefresh(
      NamespaceKey namespaceKey,
      NamespaceService namespaceService,
      ManagedStoragePlugin.MetadataBridge bridge,
      SourceConfig sourceConfig)
      throws NamespaceException {
    LegacyKVStore kvStore = mock(LegacyKVStore.class);
    MetadataPolicy metadataPolicy = mock(MetadataPolicy.class);
    SourceInternalData sourceInternalData = mock(SourceInternalData.class);

    // Mock returns
    doReturn(sourceInternalData).when(sourceInternalData).setLastFullRefreshDateMs(any());
    doReturn(sourceInternalData).when(sourceInternalData).setLastNameRefreshDateMs(any());
    doReturn(metadataPolicy).when(bridge).getMetadataPolicy();
    doReturn(UpdateMode.PREFETCH).when(metadataPolicy).getDatasetUpdateMode();
    doReturn(Optional.of(mock(SourceMetadata.class))).when(bridge).getMetadata();
    doReturn(mock(DatasetRetrievalOptions.class)).when(bridge).getDefaultRetrievalOptions();
    doReturn(1L).when(metadataPolicy).getDatasetDefinitionRefreshAfterMs();
    doReturn(1L).when(metadataPolicy).getNamesRefreshMs();
    doReturn(SourceState.GOOD).when(bridge).getState();
    doReturn(namespaceService).when(bridge).getNamespaceService();
    doReturn(sourceConfig).when(namespaceService).getSource(any());
    doReturn(1000L).when(optionManager).getOption(SOURCE_METADATA_MANAGER_WAKEUP_FREQUENCY_MS);
    doReturn(sourceInternalData).when(kvStore).get(namespaceKey);
    doReturn(true).when(namespaceService).exists(namespaceKey, NameSpaceContainer.Type.SOURCE);

    SourceMetadataManager sourceMetadataManager =
        new SourceMetadataManager(
            namespaceKey,
            modifiableSchedulerService,
            true,
            kvStore,
            bridge,
            optionManager,
            new CatalogServiceMonitor() {
              @Override
              public boolean isActiveSourceChange() {
                return false;
              }
            },
            () -> broadcaster);
  }

  @Test
  public void saveRefreshData_updatesSourceChangeState() throws NamespaceException {
    NamespaceKey namespaceKey = new NamespaceKey("testing");
    NamespaceService namespaceService = mock(NamespaceService.class);
    ManagedStoragePlugin.MetadataBridge bridge = mock(ManagedStoragePlugin.MetadataBridge.class);
    SourceConfig sourceConfig = spy(new SourceConfig());
    sourceConfig.setSourceChangeState(SourceChangeState.SOURCE_CHANGE_STATE_CREATING);

    setUpTestForBackgroudRefresh(namespaceKey, namespaceService, bridge, sourceConfig);

    // verify that sourceChangeState is updated to SOURCE_CHANGE_STATE_NONE during background
    // refresh when the SourceChangeState is stuck in NONE..
    Awaitility.waitAtMost(Duration.ofSeconds(5))
        .until(
            () ->
                bridge.getNamespaceService().getSource(namespaceKey).getSourceChangeState()
                    == SourceChangeState.SOURCE_CHANGE_STATE_NONE);
  }

  @Test
  public void saveRefreshData_updatesSourceChangeStateDoesNotUpdateSourceConfig()
      throws NamespaceException {
    NamespaceKey namespaceKey = new NamespaceKey("testing");
    NamespaceService namespaceService = mock(NamespaceService.class);
    ManagedStoragePlugin.MetadataBridge bridge = mock(ManagedStoragePlugin.MetadataBridge.class);
    SourceConfig sourceConfig = spy(new SourceConfig());
    sourceConfig.setSourceChangeState(SourceChangeState.SOURCE_CHANGE_STATE_NONE);
    sourceConfig.setTag("0");

    setUpTestForBackgroudRefresh(namespaceKey, namespaceService, bridge, sourceConfig);

    // waiting few seconds for ensure two cycles of background refresh is completed.
    Awaitility.await().atMost(Duration.ofSeconds(5));

    // Assert that there's no sourceConfig update.
    verify(namespaceService, never()).addOrUpdateSource(eq(namespaceKey), any());
    // Assert that there's no changes in sourceChangeState.
    assertEquals(
        bridge.getNamespaceService().getSource(namespaceKey).getSourceChangeState(),
        SourceChangeState.SOURCE_CHANGE_STATE_NONE);
    // Assert the tag has not changed.
    assertEquals(sourceConfig.getTag(), "0");
  }

  @Test
  public void testRefreshDatasetWithIcebergView()
      throws NamespaceException, ConnectorException, SourceMalfunctionException {
    NamespaceService namespaceService = mock(NamespaceService.class);
    ExtendedStoragePlugin storagePlugin = mock(ExtendedStoragePlugin.class);
    DatasetViewMetadata datasetViewMetadata = mock(DatasetViewMetadata.class);

    ManagedStoragePlugin.MetadataBridge bridge = mock(ManagedStoragePlugin.MetadataBridge.class);
    NamespaceKey sourceKey = new NamespaceKey("testSource");
    when(bridge.getMetadata()).thenReturn(Optional.of(storagePlugin));
    when(bridge.getNamespaceService()).thenReturn(namespaceService);
    when(bridge.getMaxMetadataColumns()).thenReturn(MAX_COLUMNS);
    when(bridge.getMaxNestedLevels()).thenReturn(MAX_NESTED_LEVELS);
    when(optionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED)).thenReturn(true);
    when(namespaceService.exists(sourceKey, NameSpaceContainer.Type.SOURCE)).thenReturn(true);
    ViewDatasetHandle handle =
        () -> new EntityPath(Lists.newArrayList("testSource", "viewdataset"));

    DatasetMetadata datasetMetadata = mock(DatasetMetadata.class);
    when(datasetMetadata.unwrap(DatasetViewMetadata.class)).thenReturn(datasetViewMetadata);
    when(storagePlugin.getDatasetMetadata(any(), any(), any(GetMetadataOption[].class)))
        .thenReturn(datasetMetadata);
    SourceMetadataManager sourceMetadataManager =
        new SourceMetadataManager(
            sourceKey,
            modifiableSchedulerService,
            true,
            mock(LegacyKVStore.class),
            bridge,
            optionManager,
            CatalogServiceMonitor.DEFAULT,
            () -> broadcaster);
    NamespaceKey datasetKey = new NamespaceKey("testSource.dataset");
    DatasetConfig datasetConfig = new DatasetConfig();
    datasetConfig.setType(DatasetType.VIRTUAL_DATASET);
    datasetConfig.setFullPathList(datasetKey.getPathComponents());
    datasetConfig.setVirtualDataset(
        new VirtualDataset().setIcebergViewAttributes(new IcebergViewAttributes()));

    when(namespaceService.getDataset(datasetKey)).thenReturn(datasetConfig);
    when(bridge.getNamespaceService()).thenReturn(namespaceService);
    when(bridge.getMetadata()).thenReturn(Optional.of(storagePlugin));
    when(storagePlugin.getDatasetHandle(any(EntityPath.class), any(GetDatasetOption[].class)))
        .thenReturn(Optional.of(handle));
    DatasetMetadataSaver datasetMetadataSaver = mock(DatasetMetadataSaver.class);
    when(namespaceService.newDatasetMetadataSaver(any(), any(), any(), anyLong(), anyBoolean()))
        .thenReturn(datasetMetadataSaver);
    doNothing().when(datasetMetadataSaver).saveDataset(any(), anyBoolean(), any(), any());
    when(namespaceService.newDatasetMetadataSaver(any(), any(), any(), anyLong(), anyBoolean()))
        .thenReturn(mock(DatasetMetadataSaver.class));

    DatasetRetrievalOptions options = DatasetRetrievalOptions.DEFAULT;
    sourceMetadataManager.refreshDataset(datasetKey, options);
    verify(datasetViewMetadata).newDeepConfig(datasetConfig);
  }

  @Test
  public void testRefreshDatasetWithIcebergViewWithoutSupportOptionSet()
      throws NamespaceException, ConnectorException, SourceMalfunctionException {
    NamespaceService namespaceService = mock(NamespaceService.class);
    ExtendedStoragePlugin storagePlugin = mock(ExtendedStoragePlugin.class);

    ManagedStoragePlugin.MetadataBridge bridge = mock(ManagedStoragePlugin.MetadataBridge.class);
    NamespaceKey sourceKey = new NamespaceKey("testSource");
    when(bridge.getMetadata()).thenReturn(Optional.of(storagePlugin));
    when(bridge.getNamespaceService()).thenReturn(namespaceService);
    when(bridge.getMaxMetadataColumns()).thenReturn(MAX_COLUMNS);
    when(bridge.getMaxNestedLevels()).thenReturn(MAX_NESTED_LEVELS);
    when(optionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED)).thenReturn(false);
    when(namespaceService.exists(sourceKey, NameSpaceContainer.Type.SOURCE)).thenReturn(true);
    ViewDatasetHandle handle =
        () -> new EntityPath(Lists.newArrayList("testSource", "viewdataset"));

    SourceMetadataManager sourceMetadataManager =
        new SourceMetadataManager(
            sourceKey,
            modifiableSchedulerService,
            true,
            mock(LegacyKVStore.class),
            bridge,
            optionManager,
            CatalogServiceMonitor.DEFAULT,
            () -> broadcaster);
    NamespaceKey datasetKey = new NamespaceKey("testSource.dataset");
    DatasetConfig datasetConfig = new DatasetConfig();
    datasetConfig.setType(DatasetType.VIRTUAL_DATASET);
    datasetConfig.setFullPathList(datasetKey.getPathComponents());
    datasetConfig.setVirtualDataset(
        new VirtualDataset().setIcebergViewAttributes(new IcebergViewAttributes()));

    when(namespaceService.getDataset(datasetKey)).thenReturn(datasetConfig);
    when(bridge.getNamespaceService()).thenReturn(namespaceService);
    when(bridge.getMetadata()).thenReturn(Optional.of(storagePlugin));
    when(storagePlugin.getDatasetHandle(any(EntityPath.class), any(GetDatasetOption[].class)))
        .thenReturn(Optional.of(handle));
    DatasetRetrievalOptions options = DatasetRetrievalOptions.DEFAULT;
    assertThatThrownBy(() -> sourceMetadataManager.refreshDataset(datasetKey, options))
        .hasMessageContaining(
            "Only tables can be refreshed. Dataset \"testSource.dataset\" is a view");
  }
}
