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

import static com.dremio.exec.catalog.CatalogOptions.RESTCATALOG_LINEAGE_CALCULATION;
import static com.dremio.exec.catalog.CatalogOptions.RESTCATALOG_VIEWS_SUPPORTED;
import static com.dremio.exec.catalog.CatalogOptions.SINGLE_SPLIT_PARTITION_MAX;
import static com.dremio.exec.catalog.CatalogOptions.SPLIT_COMPRESSION_TYPE;
import static com.dremio.sabot.rpc.user.UserSession.MAX_METADATA_COUNT;
import static com.dremio.service.namespace.NamespaceOptions.DATASET_METADATA_CONSISTENCY_VALIDATE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.dremio.common.AutoCloseables;
import com.dremio.common.map.CaseInsensitiveMap;
import com.dremio.common.utils.PathUtils;
import com.dremio.connector.ConnectorException;
import com.dremio.connector.metadata.DatasetHandle;
import com.dremio.connector.metadata.DatasetHandleListing;
import com.dremio.connector.metadata.EntityPath;
import com.dremio.connector.metadata.GetDatasetOption;
import com.dremio.connector.metadata.GetMetadataOption;
import com.dremio.connector.metadata.SourceMetadata;
import com.dremio.connector.metadata.ViewDatasetHandle;
import com.dremio.connector.metadata.extensions.SupportsListingDatasets;
import com.dremio.datastore.LocalKVStoreProvider;
import com.dremio.datastore.api.LegacyKVStore;
import com.dremio.datastore.api.LegacyKVStoreProvider;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.catalog.lineage.ImmutableTableLineage;
import com.dremio.exec.catalog.lineage.ImmutableTableLineage.Builder;
import com.dremio.exec.catalog.lineage.SqlLineageExtractor;
import com.dremio.exec.catalog.lineage.TableLineage;
import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.ops.QueryContextCreator;
import com.dremio.exec.planner.types.SqlTypeFactoryImpl;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.server.SabotContext;
import com.dremio.exec.server.options.OptionValidatorListingImpl;
import com.dremio.exec.store.DatasetRetrievalOptions;
import com.dremio.options.OptionManager;
import com.dremio.options.TypeValidators;
import com.dremio.sabot.rpc.user.UserSession;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceNotFoundException;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.NamespaceServiceImpl;
import com.dremio.service.namespace.NamespaceTestUtils;
import com.dremio.service.namespace.catalogpubsub.CatalogEventMessagePublisherProvider;
import com.dremio.service.namespace.catalogstatusevents.CatalogStatusEventsImpl;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.dataset.proto.FieldOrigin;
import com.dremio.service.namespace.dataset.proto.ParentDataset;
import com.dremio.service.namespace.dataset.proto.ViewFieldType;
import com.dremio.service.namespace.source.proto.MetadataPolicy;
import com.dremio.service.namespace.source.proto.SourceConfig;
import com.dremio.service.namespace.source.proto.UpdateMode;
import com.dremio.service.scheduler.ModifiableLocalSchedulerService;
import com.dremio.test.DremioTest;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rel.type.RelDataTypeFieldImpl;
import org.apache.calcite.rel.type.RelRecordType;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;

public class TestMetadataSynchronizerForViews {

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

  abstract class TestSourceMetadata implements SupportsListingDatasets, SourceMetadata {}

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

  private void setupDatasetsInNamespace() throws Exception {
    NamespaceTestUtils.addPhysicalDS(namespaceService, TABLE);
    NamespaceTestUtils.addVirtualDS(
        namespaceService, VIEW, "SELECT * FROM test-source.public.test-table");
  }

  private void setupShallowDatasetsInNamespace() throws Exception {
    NamespaceTestUtils.addPhysicalDS(namespaceService, TABLE);
    NamespaceTestUtils.addShallowVirtualDS(namespaceService, VIEW);
  }

  @BeforeEach
  public void setupTest() throws Exception {
    // Using the default values of these support keys. They do not really affect the tests.
    when(optionManager.getOption(SPLIT_COMPRESSION_TYPE)).thenReturn("SNAPPY");
    when(optionManager.getOption(SINGLE_SPLIT_PARTITION_MAX)).thenReturn(500L);
    when(optionManager.getOption(DATASET_METADATA_CONSISTENCY_VALIDATE)).thenReturn(false);

    // Support keys that will be really used in some tests
    when(optionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED)).thenReturn(false);
    when(optionManager.getOption(RESTCATALOG_LINEAGE_CALCULATION)).thenReturn(true);
  }

  @AfterEach
  public void cleanupTest() throws Exception {
    namespaceService.deleteSourceChildren(
        sourceKey, sourceConfig.getTag(), (DatasetConfig datasetConfig) -> {});
  }

  @Test
  public void validateSourceDatasetsNotDeletedWhenListDatasetHandlesThrows() throws Exception {
    setupDatasetsInNamespace();
    ManagedStoragePlugin.MetadataBridge bridge = mock(ManagedStoragePlugin.MetadataBridge.class);
    SupportsListingDatasets sourceMetadata = mock(TestSourceMetadata.class);
    when(sourceMetadata.listDatasetHandles(any()))
        .thenThrow(new ConnectorException("Source error"));
    when(bridge.getMetadata()).thenReturn(Optional.of((SourceMetadata) sourceMetadata));
    final MetadataSynchronizer synchronizeRun =
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
    Assertions.assertNotNull(
        namespaceService.getDataset(new NamespaceKey(PathUtils.parseFullPath(TABLE))));
    Assertions.assertEquals(2, namespaceService.getAllDatasetsCount(sourceKey));
    DatasetConfig datasetConfig =
        namespaceService.getDataset(new NamespaceKey(PathUtils.parseFullPath(TABLE)));
    Assertions.assertNotNull(datasetConfig);
    DatasetConfig viewDatasetConfig =
        namespaceService.getDataset(new NamespaceKey(PathUtils.parseFullPath(VIEW)));
    Assertions.assertNotNull(viewDatasetConfig);
  }

  @Test
  public void validateMissingDatasetsAreDeleted() throws Exception {
    setupDatasetsInNamespace();
    ManagedStoragePlugin.MetadataBridge bridge = mock(ManagedStoragePlugin.MetadataBridge.class);
    SupportsListingDatasets sourceMetadata = mock(TestSourceMetadata.class);
    when(sourceMetadata.listDatasetHandles(any(GetDatasetOption[].class)))
        .thenReturn(Collections::emptyIterator);
    when(bridge.getMetadata()).thenReturn(Optional.of((SourceMetadata) sourceMetadata));
    final MetadataSynchronizer synchronizeRun =
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
    Assertions.assertThrows(
        NamespaceNotFoundException.class,
        () -> namespaceService.getDataset(new NamespaceKey(PathUtils.parseFullPath(TABLE))));
    Assertions.assertEquals(0, namespaceService.getAllDatasetsCount(sourceKey));
  }

  @Test
  public void testRefreshNewWithDatasetAndViewHandles() throws Exception {
    // When new views won't be fully refreshed
    refreshNewWithDatasetAndViewHandles(false, true);
    refreshNewWithDatasetAndViewHandles(false, false);

    // When new views will be fully refreshed
    refreshNewWithDatasetAndViewHandles(true, false);
    refreshNewWithDatasetAndViewHandles(true, true);
  }

  private void refreshNewWithDatasetAndViewHandles(
      boolean shouldFullyRefreshViews, boolean shouldCalculateLineage) throws Exception {
    try (MockedStatic<SqlLineageExtractor> mocked = mockStatic(SqlLineageExtractor.class)) {
      when(optionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED))
          .thenReturn(shouldFullyRefreshViews);
      // When shouldFullyRefreshViews is false, this flag does not matter.
      when(optionManager.getOption(RESTCATALOG_LINEAGE_CALCULATION))
          .thenReturn(shouldCalculateLineage);

      // Mock the DatasetHandle and ViewDatasetHandle
      DatasetHandle datasetHandle = mock(DatasetHandle.class);
      ViewDatasetHandle viewDatasetHandle = mock(ViewDatasetHandle.class);

      // Mock the necessary methods for DatasetHandle
      when(datasetHandle.getDatasetPath())
          .thenReturn(new EntityPath(List.of("test-source", "public", "test-table")));

      // Mock the necessary methods for ViewDatasetHandle
      when(viewDatasetHandle.getDatasetPath())
          .thenReturn(new EntityPath(List.of("test-source", "test-view")));

      // Mock the source metadata to return both handles
      ManagedStoragePlugin.MetadataBridge bridge = mock(ManagedStoragePlugin.MetadataBridge.class);
      SupportsListingDatasets sourceMetadata = mock(TestSourceMetadata.class);
      // Return DatasetHandleListing
      when(sourceMetadata.listDatasetHandles(any(GetDatasetOption[].class)))
          .thenReturn(
              (DatasetHandleListing)
                  () -> Arrays.asList(datasetHandle, viewDatasetHandle).iterator());
      when(bridge.getMetadata()).thenReturn(Optional.of((SourceMetadata) sourceMetadata));

      SabotContext sabotContext = mock(SabotContext.class);
      QueryContextCreator queryContextCreator = mock(QueryContextCreator.class);
      QueryContext queryContext = mock(QueryContext.class);
      if (shouldFullyRefreshViews) {
        final DatasetViewMetadata datasetViewMetadata = mock(DatasetViewMetadata.class);
        final DatasetConfig datasetConfig =
            MetadataObjectsUtils.newShallowConfig(viewDatasetHandle);
        final ViewFieldType type = new ViewFieldType();
        type.setType(SqlTypeName.INTEGER.toString());
        type.setName("foo");
        datasetConfig
            .getVirtualDataset()
            .setSqlFieldsList(ImmutableList.of(type))
            .setCalciteFieldsList(ImmutableList.of(type))
            .setSql("SELECG 1");
        datasetConfig.setRecordSchema(new BatchSchema(Collections.emptyList()).toByteString());
        when(((SourceMetadata) sourceMetadata)
                .getDatasetMetadata(
                    eq(viewDatasetHandle), eq(null), any(GetMetadataOption[].class)))
            .thenReturn(datasetViewMetadata);
        when(datasetViewMetadata.unwrap(DatasetViewMetadata.class)).thenReturn(datasetViewMetadata);
        when(datasetViewMetadata.newDeepConfig(any(DatasetConfig.class))).thenReturn(datasetConfig);
        if (shouldCalculateLineage) {
          when(optionManager.getOption(MAX_METADATA_COUNT.getOptionName()))
              .thenReturn(MAX_METADATA_COUNT.getDefault());
          when(sabotContext.getOptionValidatorListing())
              .thenReturn(
                  new OptionValidatorListingImpl(
                      CaseInsensitiveMap.newImmutableMap(new HashMap<>())));
          when(sabotContext.getOptionManager()).thenReturn(optionManager);
          when(sabotContext.getQueryContextCreator()).thenReturn(queryContextCreator);
          when(queryContextCreator.createNewQueryContext(
                  any(UserSession.class),
                  any(UserBitShared.QueryId.class),
                  eq(null),
                  eq(Long.MAX_VALUE),
                  eq(Predicates.alwaysTrue()),
                  eq(null),
                  eq(null)))
              .thenReturn(queryContext);

          RelDataTypeField field =
              new RelDataTypeFieldImpl(
                  "bar", 0, SqlTypeFactoryImpl.INSTANCE.createSqlType(SqlTypeName.VARCHAR));
          RelDataType fields = new RelRecordType(List.of(field));
          TableLineage tableLineage =
              new Builder()
                  .setFields(fields)
                  .addAllUpstreams(
                      ImmutableList.of(
                          new ParentDataset()
                              .setDatasetPathList(PARENT)
                              .setType(DatasetType.PHYSICAL_DATASET)
                              .setLevel(1)))
                  .addAllFieldUpstreams(ImmutableList.of(new FieldOrigin("baz")))
                  .build();
          mocked
              .when(() -> SqlLineageExtractor.extractLineage(eq(queryContext), any(String.class)))
              .thenReturn(tableLineage);
        }
      }
      // Create and run the MetadataSynchronizer
      MetadataSynchronizer synchronizeRun =
          new MetadataSynchronizer(
              namespaceService,
              sourceKey,
              bridge,
              metadataPolicy,
              datasetSaver,
              retrievalOptions,
              optionManager,
              sabotContext);
      // Run refresh which should hit handleNewDataset as both the table and view don't exist in the
      // Namespace yet.
      synchronizeRun.go();

      // Verify that both datasets are added to the namespace
      DatasetConfig tableDatasetConfig =
          namespaceService.getDataset(
              new NamespaceKey(List.of("test-source", "public", "test-table")));
      DatasetConfig viewDatasetConfig =
          namespaceService.getDataset(new NamespaceKey(List.of("test-source", "test-view")));
      assertTableConfigIsShallow(tableDatasetConfig);
      if (shouldFullyRefreshViews) {
        assertViewConfigIsFull(viewDatasetConfig);
        if (shouldCalculateLineage) {
          Assertions.assertEquals(
              1, viewDatasetConfig.getVirtualDataset().getSqlFieldsList().size());
          Assertions.assertEquals(
              "bar", viewDatasetConfig.getVirtualDataset().getSqlFieldsList().get(0).getName());
          Assertions.assertEquals(
              SqlTypeName.VARCHAR.toString(),
              viewDatasetConfig.getVirtualDataset().getSqlFieldsList().get(0).getType());

          Assertions.assertEquals(
              1, viewDatasetConfig.getVirtualDataset().getFieldUpstreamsList().size());
          Assertions.assertEquals(
              "baz",
              viewDatasetConfig.getVirtualDataset().getFieldUpstreamsList().get(0).getName());
          Assertions.assertEquals(
              null, viewDatasetConfig.getVirtualDataset().getFieldOriginsList());

          Assertions.assertEquals(1, viewDatasetConfig.getVirtualDataset().getParentsList().size());
          Assertions.assertEquals(
              PARENT,
              viewDatasetConfig.getVirtualDataset().getParentsList().get(0).getDatasetPathList());
        }
      } else {
        assertViewConfigIsShallow(viewDatasetConfig);
      }
    }
    cleanupTest();
  }

  @Test
  public void testRefreshExistingWithDatasetAndViewHandlesWithoutQuerying() throws Exception {
    // When existing views won't be fully refreshed if they're shallow, i.e. not queried yet.
    refreshExistingWithDatasetAndViewHandlesWithoutQuerying(false, true);
    refreshExistingWithDatasetAndViewHandlesWithoutQuerying(false, false);

    // When existing views will be fully refreshed no matter what.
    refreshExistingWithDatasetAndViewHandlesWithoutQuerying(true, false);
    refreshExistingWithDatasetAndViewHandlesWithoutQuerying(true, true);
  }

  private void refreshExistingWithDatasetAndViewHandlesWithoutQuerying(
      boolean shouldFullyRefreshViews, boolean shouldCalculateLineage) throws Exception {
    try (MockedStatic<SqlLineageExtractor> mocked = mockStatic(SqlLineageExtractor.class)) {

      when(optionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED))
          .thenReturn(shouldFullyRefreshViews);
      // When shouldFullyRefreshViews is false, this flag does not matter.
      when(optionManager.getOption(RESTCATALOG_LINEAGE_CALCULATION))
          .thenReturn(shouldCalculateLineage);

      setupShallowDatasetsInNamespace();
      // Mock the DatasetHandle and ViewDatasetHandle
      DatasetHandle datasetHandle = mock(DatasetHandle.class);
      ViewDatasetHandle viewDatasetHandle = mock(ViewDatasetHandle.class);

      // Mock the necessary methods for DatasetHandle
      when(datasetHandle.getDatasetPath())
          .thenReturn(new EntityPath(List.of("test-source", "public", "test-table")));

      // Mock the necessary methods for ViewDatasetHandle
      when(viewDatasetHandle.getDatasetPath())
          .thenReturn(new EntityPath(List.of("test-source", "test-view")));

      // Mock the source metadata to return both handles
      ManagedStoragePlugin.MetadataBridge bridge = mock(ManagedStoragePlugin.MetadataBridge.class);
      SupportsListingDatasets sourceMetadata = mock(TestSourceMetadata.class);
      // Return DatasetHandleListing
      when(sourceMetadata.listDatasetHandles(any(GetDatasetOption[].class)))
          .thenReturn(
              (DatasetHandleListing)
                  () -> Arrays.asList(datasetHandle, viewDatasetHandle).iterator());
      when(bridge.getMetadata()).thenReturn(Optional.of((SourceMetadata) sourceMetadata));

      SabotContext sabotContext = mock(SabotContext.class);
      QueryContextCreator queryContextCreator = mock(QueryContextCreator.class);
      QueryContext queryContext = mock(QueryContext.class);
      if (shouldFullyRefreshViews) {
        final DatasetViewMetadata datasetViewMetadata = mock(DatasetViewMetadata.class);
        final DatasetConfig datasetConfig =
            namespaceService.getDataset(new NamespaceKey(List.of("test-source", "test-view")));
        final ViewFieldType type = new ViewFieldType();
        type.setType(SqlTypeName.INTEGER.toString());
        type.setName("foo");
        datasetConfig
            .getVirtualDataset()
            .setSqlFieldsList(ImmutableList.of(type))
            .setCalciteFieldsList(ImmutableList.of(type))
            .setSql("SELECT 1");
        datasetConfig.setRecordSchema(new BatchSchema(Collections.emptyList()).toByteString());
        when(((SourceMetadata) sourceMetadata)
                .getDatasetMetadata(
                    eq(viewDatasetHandle), eq(null), any(GetMetadataOption[].class)))
            .thenReturn(datasetViewMetadata);
        when(datasetViewMetadata.unwrap(DatasetViewMetadata.class)).thenReturn(datasetViewMetadata);
        when(datasetViewMetadata.newDeepConfig(any(DatasetConfig.class))).thenReturn(datasetConfig);
        if (shouldCalculateLineage) {
          when(optionManager.getOption(MAX_METADATA_COUNT.getOptionName()))
              .thenReturn(MAX_METADATA_COUNT.getDefault());
          when(sabotContext.getOptionValidatorListing())
              .thenReturn(
                  new OptionValidatorListingImpl(
                      CaseInsensitiveMap.newImmutableMap(new HashMap<>())));
          when(sabotContext.getOptionManager()).thenReturn(optionManager);
          when(sabotContext.getQueryContextCreator()).thenReturn(queryContextCreator);
          when(queryContextCreator.createNewQueryContext(
                  any(UserSession.class),
                  any(UserBitShared.QueryId.class),
                  eq(null),
                  eq(Long.MAX_VALUE),
                  eq(Predicates.alwaysTrue()),
                  eq(null),
                  eq(null)))
              .thenReturn(queryContext);

          RelDataTypeField field =
              new RelDataTypeFieldImpl(
                  "bar", 0, SqlTypeFactoryImpl.INSTANCE.createSqlType(SqlTypeName.VARCHAR));
          RelDataType fields = new RelRecordType(List.of(field));
          TableLineage tableLineage =
              new ImmutableTableLineage.Builder()
                  .setFields(fields)
                  .addAllUpstreams(
                      ImmutableList.of(
                          new ParentDataset()
                              .setDatasetPathList(PARENT)
                              .setType(DatasetType.PHYSICAL_DATASET)
                              .setLevel(1)))
                  .addAllFieldUpstreams(ImmutableList.of(new FieldOrigin("baz")))
                  .build();
          mocked
              .when(() -> SqlLineageExtractor.extractLineage(eq(queryContext), any(String.class)))
              .thenReturn(tableLineage);
        }
      }
      // Create and run the MetadataSynchronizer
      MetadataSynchronizer synchronizeRun =
          new MetadataSynchronizer(
              namespaceService,
              sourceKey,
              bridge,
              metadataPolicy,
              datasetSaver,
              retrievalOptions,
              optionManager,
              sabotContext);
      // Run refresh which should hit handleExistingDataset as both the table and view already exist
      // in the Namespace.
      synchronizeRun.go();

      // Verify that both datasets are added to the namespace
      DatasetConfig tableDatasetConfig =
          namespaceService.getDataset(
              new NamespaceKey(List.of("test-source", "public", "test-table")));
      DatasetConfig viewDatasetConfig =
          namespaceService.getDataset(new NamespaceKey(List.of("test-source", "test-view")));
      Assertions.assertEquals(2, namespaceService.getAllDatasetsCount(sourceKey));
      assertTableConfigIsShallow(tableDatasetConfig);
      if (shouldFullyRefreshViews) {
        assertViewConfigIsFull(viewDatasetConfig);
        if (shouldCalculateLineage) {
          Assertions.assertEquals(
              1, viewDatasetConfig.getVirtualDataset().getSqlFieldsList().size());
          Assertions.assertEquals(
              "bar", viewDatasetConfig.getVirtualDataset().getSqlFieldsList().get(0).getName());
          Assertions.assertEquals(
              SqlTypeName.VARCHAR.toString(),
              viewDatasetConfig.getVirtualDataset().getSqlFieldsList().get(0).getType());

          Assertions.assertEquals(
              1, viewDatasetConfig.getVirtualDataset().getFieldUpstreamsList().size());
          Assertions.assertEquals(
              "baz",
              viewDatasetConfig.getVirtualDataset().getFieldUpstreamsList().get(0).getName());
          Assertions.assertEquals(
              null, viewDatasetConfig.getVirtualDataset().getFieldOriginsList());

          Assertions.assertEquals(1, viewDatasetConfig.getVirtualDataset().getParentsList().size());
          Assertions.assertEquals(
              PARENT,
              viewDatasetConfig.getVirtualDataset().getParentsList().get(0).getDatasetPathList());
        }
      } else {
        assertViewConfigIsShallow(viewDatasetConfig);
      }
    }
    cleanupTest();
  }

  @Test
  public void testRefreshNewWithOnlyDatasetHandles() throws Exception {
    // Mock the DatasetHandle
    DatasetHandle datasetHandle = mock(DatasetHandle.class);
    final String datasetPath = "test-source.public.test-table";
    // Mock the necessary methods for DatasetHandle
    when(datasetHandle.getDatasetPath())
        .thenReturn(new EntityPath(List.of("test-source", "public", "test-table")));

    // Mock the source metadata to return only the dataset handle
    ManagedStoragePlugin.MetadataBridge bridge = mock(ManagedStoragePlugin.MetadataBridge.class);
    SupportsListingDatasets sourceMetadata = mock(TestSourceMetadata.class);
    when(sourceMetadata.listDatasetHandles(any(GetDatasetOption[].class)))
        .thenReturn(
            (DatasetHandleListing) () -> Collections.singletonList(datasetHandle).iterator());
    when(bridge.getMetadata()).thenReturn(Optional.of((SourceMetadata) sourceMetadata));

    // Create and run the MetadataSynchronizer
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

    // Verify that the dataset is added to the namespace
    DatasetConfig tableDatasetConfig =
        namespaceService.getDataset(
            new NamespaceKey(List.of("test-source", "public", "test-table")));
    assertTableConfigIsShallow(tableDatasetConfig);
    Assertions.assertEquals(1, namespaceService.getAllDatasetsCount(sourceKey));
  }

  @Test
  public void testRefreshNewWithOnlyViewHandles() throws Exception {
    // Mock the ViewDatasetHandle
    ViewDatasetHandle viewDatasetHandle = mock(ViewDatasetHandle.class);

    // Mock the necessary methods for ViewDatasetHandle
    when(viewDatasetHandle.getDatasetPath())
        .thenReturn(new EntityPath(List.of("test-source", "view1")));

    // Mock the source metadata to return only the view handle
    ManagedStoragePlugin.MetadataBridge bridge = mock(ManagedStoragePlugin.MetadataBridge.class);
    SupportsListingDatasets sourceMetadata = mock(TestSourceMetadata.class);
    when(sourceMetadata.listDatasetHandles(any(GetDatasetOption[].class)))
        .thenReturn(
            (DatasetHandleListing) () -> Collections.singletonList(viewDatasetHandle).iterator());
    when(bridge.getMetadata()).thenReturn(Optional.of((SourceMetadata) sourceMetadata));

    // Create and run the MetadataSynchronizer
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

    // Verify that the view dataset is added to the namespace
    Assertions.assertNotNull(
        namespaceService.getDataset(new NamespaceKey(List.of("test-source", "view1"))));
    Assertions.assertEquals(1, namespaceService.getAllDatasetsCount(sourceKey));
  }

  @Test
  public void testRefreshWithNoHandles() throws Exception {
    // Mock the source metadata to return no handles
    setupDatasetsInNamespace();
    ManagedStoragePlugin.MetadataBridge bridge = mock(ManagedStoragePlugin.MetadataBridge.class);
    SupportsListingDatasets sourceMetadata = mock(TestSourceMetadata.class);
    when(sourceMetadata.listDatasetHandles(any(GetDatasetOption[].class)))
        .thenReturn((DatasetHandleListing) Collections::emptyIterator);
    when(bridge.getMetadata()).thenReturn(Optional.of((SourceMetadata) sourceMetadata));

    // Create and run the MetadataSynchronizer
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
    // Verify that no datasets are added to the namespace
    Assertions.assertEquals(0, namespaceService.getAllDatasetsCount(sourceKey));
  }

  @Test
  public void testAdhocNamesRefreshOnSourceWithView() throws Exception {

    ViewDatasetHandle viewDatasetHandle = mock(ViewDatasetHandle.class);
    when(viewDatasetHandle.getDatasetPath())
        .thenReturn(new EntityPath(List.of("test-source", "view1")));

    ManagedStoragePlugin.MetadataBridge bridge = mock(ManagedStoragePlugin.MetadataBridge.class);
    SupportsListingDatasets sourceMetadata = mock(TestSourceMetadata.class);
    when(sourceMetadata.listDatasetHandles(any(GetDatasetOption[].class)))
        .thenReturn(() -> Collections.singletonList(viewDatasetHandle).iterator());
    when(bridge.getMetadata()).thenReturn(Optional.of((SourceMetadata) sourceMetadata));
    when(bridge.getNamespaceService()).thenReturn(namespaceService);
    when(bridge.getDefaultRetrievalOptions()).thenReturn(retrievalOptions);

    // Test refreshDatasetNames for source with view
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

    // Verify that the source metadata manager refreshes the view
    DatasetConfig viewDatasetConfig =
        namespaceService.getDataset(new NamespaceKey(List.of("test-source", "view1")));
    assertViewConfigIsShallow(viewDatasetConfig);
  }

  @Test
  public void testAdhocFullRefreshOnSourceWithView() throws Exception {

    ViewDatasetHandle viewDatasetHandle = mock(ViewDatasetHandle.class);
    when(viewDatasetHandle.getDatasetPath())
        .thenReturn(new EntityPath(List.of("test-source", "view1")));

    ManagedStoragePlugin.MetadataBridge bridge = mock(ManagedStoragePlugin.MetadataBridge.class);
    SupportsListingDatasets sourceMetadata = mock(TestSourceMetadata.class);
    when(sourceMetadata.listDatasetHandles(any(GetDatasetOption[].class)))
        .thenReturn(() -> Collections.singletonList(viewDatasetHandle).iterator());
    when(bridge.getMetadata()).thenReturn(Optional.of((SourceMetadata) sourceMetadata));
    when(bridge.getNamespaceService()).thenReturn(namespaceService);
    when(bridge.getDefaultRetrievalOptions()).thenReturn(retrievalOptions);

    // Test refreshDatasetNames for source with view
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
    Assertions.assertThrows(
        IllegalStateException.class,
        () ->
            sourceMetadataManager.refresh(
                SourceUpdateType.FULL,
                new MetadataPolicy()
                    .setAutoPromoteDatasets(true)
                    .setDeleteUnavailableDatasets(true),
                true));
  }

  private void assertTableConfigIsShallow(DatasetConfig datasetConfig) {
    Assertions.assertNotNull(datasetConfig);
    Assertions.assertNull(datasetConfig.getRecordSchema());
    Assertions.assertNull(datasetConfig.getReadDefinition());
  }

  private void assertTableConfigIsFull(DatasetConfig datasetConfig) {
    Assertions.assertNotNull(datasetConfig);
    Assertions.assertNotNull(datasetConfig.getRecordSchema());
    Assertions.assertNotNull(datasetConfig.getReadDefinition());
  }

  private void assertViewConfigIsShallow(DatasetConfig datasetConfig) {
    Assertions.assertNotNull(datasetConfig);
    Assertions.assertNotNull(datasetConfig.getVirtualDataset());
    Assertions.assertNull(datasetConfig.getRecordSchema());
    Assertions.assertNull(datasetConfig.getVirtualDataset().getSqlFieldsList());
    Assertions.assertNull(datasetConfig.getVirtualDataset().getCalciteFieldsList());
  }

  private void assertViewConfigIsFull(DatasetConfig datasetConfig) {
    Assertions.assertNotNull(datasetConfig);
    Assertions.assertNotNull(datasetConfig.getVirtualDataset().getVersion());
    Assertions.assertNotNull(datasetConfig.getRecordSchema());
    Assertions.assertNotNull(datasetConfig.getVirtualDataset().getSqlFieldsList());
    Assertions.assertNotNull(datasetConfig.getVirtualDataset().getCalciteFieldsList());
  }
}
