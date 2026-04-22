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

import static com.dremio.context.UserContext.SYSTEM_USER_CONTEXT_ID;
import static com.dremio.exec.catalog.CatalogOptions.RESTCATALOG_VIEWS_SUPPORTED;
import static com.dremio.exec.store.IcebergCatalogPluginOptions.RESTCATALOG_PLUGIN_ENABLED;
import static com.dremio.exec.store.IcebergCatalogPluginOptions.RESTCATALOG_PLUGIN_MUTABLE_ENABLED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.BaseTestQuery;
import com.dremio.catalog.exception.CatalogEntityAlreadyExistsException;
import com.dremio.catalog.exception.CatalogEntityNotFoundException;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.logical.FormatPluginConfig;
import com.dremio.config.DremioConfig;
import com.dremio.connector.metadata.EntityPath;
import com.dremio.context.RequestContext;
import com.dremio.context.UserContext;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.catalog.PluginSabotContext;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.physical.base.ViewOptions;
import com.dremio.exec.physical.base.WriterOptions;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.SchemaConfig;
import com.dremio.exec.store.dfs.IcebergTableProps;
import com.dremio.exec.store.easy.json.JSONFormatPlugin;
import com.dremio.exec.store.iceberg.DremioFileIO;
import com.dremio.exec.store.iceberg.IcebergFormatConfig;
import com.dremio.exec.store.iceberg.IcebergFormatPlugin;
import com.dremio.exec.store.iceberg.IcebergUtils;
import com.dremio.exec.store.iceberg.model.IcebergModel;
import com.dremio.exec.store.iceberg.model.IcebergTableIdentifier;
import com.dremio.io.file.FileSystem;
import com.dremio.options.OptionManager;
import com.dremio.plugins.icebergcatalog.dfs.DatasetFileSystemCache;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.dataset.proto.IcebergMetadata;
import com.dremio.service.namespace.dataset.proto.IcebergViewAttributes;
import com.dremio.service.namespace.dataset.proto.PhysicalDataset;
import com.dremio.service.namespace.dataset.proto.VirtualDataset;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.inject.Provider;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.exceptions.NoSuchViewException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.view.BaseView;
import org.apache.iceberg.view.View;
import org.apache.iceberg.view.ViewMetadata;
import org.apache.iceberg.view.ViewOperations;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class TestRestIcebergCatalogPlugin extends BaseTestQuery {
  private RestIcebergCatalogPlugin plugin;

  @Mock private RestIcebergCatalogPluginConfig mockPluginConfig;
  @Mock private CatalogAccessor mockCatalogAccessor;
  @Mock private PluginSabotContext pluginSabotContext;
  @Mock private OptionManager optionManager;
  @Mock private DatasetFileSystemCache mockFileSystemCache;
  @Mock private StoragePluginId storagePluginId;
  @Mock private FileSystem fileSystem;
  @Mock private DremioFileIO dremioFileIO;

  private class RestIcebergCatalogPluginMock extends RestIcebergCatalogPlugin {
    protected RestIcebergCatalogPluginMock(
        RestIcebergCatalogPluginConfig pluginConfig,
        PluginSabotContext sabotContext,
        String name,
        Provider<StoragePluginId> pluginIdProvider) {
      super(pluginConfig, sabotContext, name, pluginIdProvider);
    }

    @Override
    public CatalogAccessor createCatalog(Configuration config) {
      return mockCatalogAccessor;
    }

    @Override
    public DatasetFileSystemCache getHadoopFileSystemCache() {
      return mockFileSystemCache;
    }

    @Override
    public FileSystem createFS(Builder b) throws IOException {
      return fileSystem;
    }

    @Override
    public FileIO createIcebergFileIO(
        FileSystem fs,
        OperatorContext context,
        List<String> dataset,
        String datasourcePluginUID,
        Long fileLength) {
      return dremioFileIO;
    }
  }

  @Before
  public void setUp() throws Exception {
    when(pluginSabotContext.getOptionManager()).thenReturn(optionManager);
    when(optionManager.getOption(RESTCATALOG_PLUGIN_ENABLED)).thenReturn(true);
    when(mockPluginConfig.getRestEndpointURI(any(DremioConfig.class)))
        .thenReturn("http://localhost:8181/catalog/api");
    when(pluginSabotContext.getDremioConfig()).thenReturn(mock(DremioConfig.class));
    this.plugin =
        new RestIcebergCatalogPluginMock(
            mockPluginConfig, pluginSabotContext, "resticebergcatalog", () -> storagePluginId);
    plugin.start();
  }

  @Test
  public void testGetCatalogThrowsExceptionWhenNotStarted() {
    this.plugin =
        new RestIcebergCatalogPluginMock(
            mockPluginConfig, pluginSabotContext, "resticebergcatalog", () -> storagePluginId);
    assertThatThrownBy(() -> plugin.getCatalogAccessor())
        .isInstanceOf(UserException.class)
        .hasMessageContaining(
            "Iceberg Catalog Source resticebergcatalog is either not started or already closed");
  }

  @Test
  public void testGetDatasetHandleWithSmallComponentSize() {
    EntityPath mockEntityPath = mock(EntityPath.class);
    when(mockEntityPath.getComponents()).thenReturn(List.of("", ""));
    assertEquals(Optional.empty(), plugin.getDatasetHandle(mockEntityPath));
  }

  @Test
  public void testCloseShouldCatchExceptions() throws Exception {
    doThrow(new Exception()).when(mockCatalogAccessor).close();
    doThrow(new Exception()).when(mockFileSystemCache).close();
    plugin.close();
    verify(mockCatalogAccessor, times(1)).close();
    verify(mockFileSystemCache, times(1)).close();
  }

  @Test
  public void testIsMetadataValidityCheckRecentEnough() throws Exception {
    OptionManager mockOptionManager = mock(OptionManager.class);
    when(mockOptionManager.getOption(PlannerSettings.METADATA_EXPIRY_CHECK_INTERVAL_SECS))
        .thenReturn(1L);
    assertFalse(plugin.isMetadataValidityCheckRecentEnough(null, null, mockOptionManager));
    assertFalse(plugin.isMetadataValidityCheckRecentEnough(1L, 1002L, mockOptionManager));
    assertTrue(plugin.isMetadataValidityCheckRecentEnough(1L, 1001L, mockOptionManager));
    assertTrue(plugin.isMetadataValidityCheckRecentEnough(1L, 1000L, mockOptionManager));
  }

  @Test
  public void testGetFormatPlugin() {
    FormatPluginConfig mockIcebergFormatPlugin = mock(IcebergFormatConfig.class);
    assertTrue(plugin.getFormatPlugin(mockIcebergFormatPlugin) instanceof IcebergFormatPlugin);
    FormatPluginConfig mockJsonFormatPlugin = mock(JSONFormatPlugin.JSONFormatConfig.class);
    assertThatThrownBy(() -> plugin.getFormatPlugin(mockJsonFormatPlugin))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("Format plugins for non iceberg use cases are not supported.");
  }

  @Test
  public void testIsIcebergMetadataValidNullChecks() {
    DatasetConfig mockDatasetConfig = mock(DatasetConfig.class);
    PhysicalDataset mockPhysicalDataset = mock(PhysicalDataset.class);
    IcebergMetadata mockIcebergMetadata = mock(IcebergMetadata.class);
    when(mockDatasetConfig.getPhysicalDataset()).thenReturn(mockPhysicalDataset);
    when(mockPhysicalDataset.getIcebergMetadata()).thenReturn(null);
    assertFalse(plugin.isIcebergMetadataValid(mockDatasetConfig, null));
    when(mockPhysicalDataset.getIcebergMetadata()).thenReturn(mockIcebergMetadata);
    when(mockIcebergMetadata.getMetadataFileLocation()).thenReturn(null);
    assertFalse(plugin.isIcebergMetadataValid(mockDatasetConfig, null));
    when(mockIcebergMetadata.getMetadataFileLocation()).thenReturn("");
    assertFalse(plugin.isIcebergMetadataValid(mockDatasetConfig, null));
  }

  @Test
  public void testIsIcebergMetadataValid() {
    DatasetConfig mockDatasetConfig = mock(DatasetConfig.class);
    PhysicalDataset mockPhysicalDataset = mock(PhysicalDataset.class);
    IcebergMetadata mockIcebergMetadata = mock(IcebergMetadata.class);
    when(mockDatasetConfig.getPhysicalDataset()).thenReturn(mockPhysicalDataset);
    when(mockPhysicalDataset.getIcebergMetadata()).thenReturn(mockIcebergMetadata);
    when(mockIcebergMetadata.getMetadataFileLocation()).thenReturn("");
    assertFalse(plugin.isIcebergMetadataValid(mockDatasetConfig, null));
  }

  @Test
  public void testIcIcebergViewMetadataValid() {
    NamespaceKey namespaceKey = new NamespaceKey("viewtest");
    ViewMetadata viewMetadata = mock(ViewMetadata.class);
    when(viewMetadata.formatVersion()).thenReturn(1);
    when(viewMetadata.schema()).thenReturn(new Schema());
    when(viewMetadata.currentVersionId()).thenReturn(1);
    when(viewMetadata.location()).thenReturn("location");
    when(viewMetadata.metadataFileLocation()).thenReturn("metadata_location");
    when(mockCatalogAccessor.getViewMetadata(namespaceKey.getPathComponents()))
        .thenReturn(viewMetadata);

    IcebergViewAttributes icebergViewAttributes =
        new IcebergViewAttributes()
            .setViewDialect("SQL")
            .setViewDialect("spark")
            .setMetadataLocation("metadata_location");
    DatasetConfig mockDatasetConfig = mock(DatasetConfig.class);
    when(mockDatasetConfig.getType()).thenReturn(DatasetType.VIRTUAL_DATASET);
    VirtualDataset mockVirtualDataset = mock(VirtualDataset.class);
    when(mockDatasetConfig.getVirtualDataset()).thenReturn(mockVirtualDataset);
    when(mockVirtualDataset.getIcebergViewAttributes()).thenReturn(icebergViewAttributes);
    when(mockCatalogAccessor.getViewMetadata(namespaceKey.getPathComponents()))
        .thenReturn(viewMetadata);
    assertTrue(plugin.isIcebergMetadataValid(mockDatasetConfig, namespaceKey));
  }

  @Test
  public void testCreateIcebergView() throws Exception {
    NamespaceKey namespaceKey = new NamespaceKey("viewtest.folder1.v1");
    BatchSchema schema = BatchSchema.EMPTY;
    ViewOptions viewOptions = mock(ViewOptions.class);
    com.dremio.exec.dotfile.View view = mock(com.dremio.exec.dotfile.View.class);
    when(optionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED)).thenReturn(true);
    when(view.getSql()).thenReturn("select * from t1");
    when(viewOptions.isViewCreate()).thenReturn(true);
    when(viewOptions.getBatchSchema()).thenReturn(schema);
    when(mockCatalogAccessor.createView(
            eq(namespaceKey.getPathComponents()), any(), any(), any(), eq("select * from t1")))
        .thenReturn(mock(View.class));
    assertTrue(plugin.createOrUpdateView(namespaceKey, null, view, viewOptions));
    verify(mockCatalogAccessor, times(1))
        .createView(
            eq(namespaceKey.getPathComponents()), any(), any(), any(), eq("select * from t1"));
    verify(mockCatalogAccessor, times(0))
        .updateView(
            eq(namespaceKey.getPathComponents()), any(), any(), any(), eq("select * from t1"));
  }

  @Test
  public void testCreateIcebergViewThrowsError() throws Exception {
    NamespaceKey namespaceKey = new NamespaceKey("viewtest.folder1.v1");
    BatchSchema schema = BatchSchema.EMPTY;
    ViewOptions viewOptions = mock(ViewOptions.class);
    com.dremio.exec.dotfile.View view = mock(com.dremio.exec.dotfile.View.class);
    when(optionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED)).thenReturn(true);
    when(view.getSql()).thenReturn("select * from t1");
    when(viewOptions.isViewCreate()).thenReturn(true);
    when(viewOptions.getBatchSchema()).thenReturn(schema);
    when(mockCatalogAccessor.createView(
            eq(namespaceKey.getPathComponents()), any(), any(), any(), eq("select * from t1")))
        .thenThrow(AlreadyExistsException.class);
    assertThatThrownBy(() -> plugin.createOrUpdateView(namespaceKey, null, view, viewOptions))
        .isInstanceOf(CatalogEntityAlreadyExistsException.class)
        .hasMessageContaining("View already exists");
  }

  @Test
  public void testUpdateIcebergViewThrowsError() throws Exception {
    NamespaceKey namespaceKey = new NamespaceKey("viewtest.folder1.v1");
    BatchSchema schema = BatchSchema.EMPTY;
    ViewOptions viewOptions = mock(ViewOptions.class);
    com.dremio.exec.dotfile.View view = mock(com.dremio.exec.dotfile.View.class);
    when(optionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED)).thenReturn(true);
    when(view.getSql()).thenReturn("select * from t1");
    when(viewOptions.isViewCreate()).thenReturn(false);
    when(viewOptions.getBatchSchema()).thenReturn(schema);
    when(mockCatalogAccessor.updateView(
            eq(namespaceKey.getPathComponents()), any(), any(), any(), eq("select * from t1")))
        .thenThrow(NoSuchViewException.class);
    assertThatThrownBy(() -> plugin.createOrUpdateView(namespaceKey, null, view, viewOptions))
        .isInstanceOf(CatalogEntityNotFoundException.class)
        .hasMessageContaining("cannot be found");
  }

  @Test
  public void testUpdateIcebergView() throws Exception {
    NamespaceKey namespaceKey = new NamespaceKey("viewtest.folder1.v1");
    BatchSchema schema = BatchSchema.EMPTY;
    ViewOptions viewOptions = mock(ViewOptions.class);
    com.dremio.exec.dotfile.View view = mock(com.dremio.exec.dotfile.View.class);
    when(optionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED)).thenReturn(true);
    when(view.getSql()).thenReturn("select * from t1");
    when(viewOptions.isViewCreate()).thenReturn(false);
    when(viewOptions.getBatchSchema()).thenReturn(schema);
    when(mockCatalogAccessor.updateView(
            eq(namespaceKey.getPathComponents()), any(), any(), any(), eq("select * from t1")))
        .thenReturn(mock(View.class));
    assertTrue(plugin.createOrUpdateView(namespaceKey, null, view, viewOptions));
    verify(mockCatalogAccessor, times(0))
        .createView(
            eq(namespaceKey.getPathComponents()), any(), any(), any(), eq("select * from t1"));
    verify(mockCatalogAccessor, times(1))
        .updateView(
            eq(namespaceKey.getPathComponents()), any(), any(), any(), eq("select * from t1"));
  }

  @Test
  public void testDropIcebergViewThrowsError() throws Exception {
    when(optionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED)).thenReturn(true);
    NamespaceKey namespaceKey = new NamespaceKey("viewtest.folder1.v1");
    doThrow(new NoSuchViewException(""))
        .when(mockCatalogAccessor)
        .dropView(eq(namespaceKey.getPathComponents()));
    assertThatThrownBy(() -> plugin.dropView(namespaceKey, null, null))
        .isInstanceOf(CatalogEntityNotFoundException.class)
        .hasMessageContaining("cannot be found");
  }

  @Test
  public void testIcIcebergViewMetadataNullMetadataLocation() {
    NamespaceKey namespaceKey = new NamespaceKey("viewtest");
    ViewMetadata viewMetadata = mock(ViewMetadata.class);
    when(viewMetadata.formatVersion()).thenReturn(1);
    when(viewMetadata.schema()).thenReturn(new Schema());
    when(viewMetadata.currentVersionId()).thenReturn(1);
    when(viewMetadata.location()).thenReturn("location");
    when(viewMetadata.metadataFileLocation()).thenReturn("metadata_location");
    when(mockCatalogAccessor.getViewMetadata(namespaceKey.getPathComponents()))
        .thenReturn(viewMetadata);

    IcebergViewAttributes icebergViewAttributes =
        new IcebergViewAttributes().setViewDialect("SQL").setViewDialect("spark");
    DatasetConfig mockDatasetConfig = mock(DatasetConfig.class);
    when(mockDatasetConfig.getType()).thenReturn(DatasetType.VIRTUAL_DATASET);
    VirtualDataset mockVirtualDataset = mock(VirtualDataset.class);
    when(mockDatasetConfig.getVirtualDataset()).thenReturn(mockVirtualDataset);
    when(mockVirtualDataset.getIcebergViewAttributes()).thenReturn(icebergViewAttributes);
    when(mockCatalogAccessor.getViewMetadata(namespaceKey.getPathComponents()))
        .thenReturn(viewMetadata);
    assertFalse(plugin.isIcebergMetadataValid(mockDatasetConfig, namespaceKey));
  }

  @Test
  public void testIcIcebergViewMetadataDifferentMetadataLocation() {
    NamespaceKey namespaceKey = new NamespaceKey("viewtest");
    ViewMetadata viewMetadata = mock(ViewMetadata.class);
    when(viewMetadata.formatVersion()).thenReturn(1);
    when(viewMetadata.schema()).thenReturn(new Schema());
    when(viewMetadata.currentVersionId()).thenReturn(1);
    when(viewMetadata.location()).thenReturn("location");
    when(viewMetadata.metadataFileLocation()).thenReturn("metadata_location");
    when(mockCatalogAccessor.getViewMetadata(namespaceKey.getPathComponents()))
        .thenReturn(viewMetadata);

    IcebergViewAttributes icebergViewAttributes =
        new IcebergViewAttributes()
            .setViewDialect("SQL")
            .setViewDialect("spark")
            .setMetadataLocation("metadata_location1");
    DatasetConfig mockDatasetConfig = mock(DatasetConfig.class);
    when(mockDatasetConfig.getType()).thenReturn(DatasetType.VIRTUAL_DATASET);
    VirtualDataset mockVirtualDataset = mock(VirtualDataset.class);
    when(mockDatasetConfig.getVirtualDataset()).thenReturn(mockVirtualDataset);
    when(mockVirtualDataset.getIcebergViewAttributes()).thenReturn(icebergViewAttributes);
    when(mockCatalogAccessor.getViewMetadata(namespaceKey.getPathComponents()))
        .thenReturn(viewMetadata);
    assertFalse(plugin.isIcebergMetadataValid(mockDatasetConfig, namespaceKey));
  }

  @Test
  public void testCreateIcebergTableOperationsShouldThrowWhenSupportKeyIsDisabled() {
    final FileIO fileIO = mock(FileIO.class);
    final String queryUserName = "someName";
    final IcebergTableIdentifier tableIdentifier = mock(IcebergTableIdentifier.class);

    when(pluginSabotContext.getOptionManager()).thenReturn(optionManager);
    when(optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)).thenReturn(false);
    assertThatThrownBy(
            () -> plugin.createIcebergTableOperations(fileIO, tableIdentifier, queryUserName, null))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void testCreateIcebergTableOperationsWhenSupportKeyIsEnabled() {
    final FileIO fileIO = mock(FileIO.class);
    final String queryUserName = "someName";
    final String queryUserId = "someId";
    final IcebergCatalogTableIdentifier tableIdentifier = mock(IcebergCatalogTableIdentifier.class);
    final TableOperations tableOperations = mock(TableOperations.class);
    final List<String> dataset = List.of("some", "dataset");

    when(pluginSabotContext.getOptionManager()).thenReturn(optionManager);
    when(optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)).thenReturn(true);
    when(mockCatalogAccessor.createIcebergTableOperations(any(), any(), any(), any()))
        .thenReturn(tableOperations);
    when(tableIdentifier.getDataset()).thenReturn(dataset);

    assertEquals(
        tableOperations,
        plugin.createIcebergTableOperations(fileIO, tableIdentifier, queryUserName, queryUserId));
    verify(mockCatalogAccessor)
        .createIcebergTableOperations(fileIO, dataset, queryUserName, queryUserId);
  }

  @Test
  public void testCreateNewTableShouldThrowWithoutIcebergProps() {
    when(optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)).thenReturn(true);
    assertThatThrownBy(
            () ->
                plugin.createNewTable(
                    new NamespaceKey(List.of("source", "table", "path")),
                    mock(SchemaConfig.class),
                    null,
                    WriterOptions.DEFAULT,
                    null,
                    null))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("Iceberg Catalog sources can only be used to manage Iceberg tables.");
  }

  @Test
  public void testCreateNewTableShouldThrowWithInvalidStoreAsType() {
    when(optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)).thenReturn(true);
    assertThatThrownBy(
            () ->
                plugin.createNewTable(
                    new NamespaceKey(List.of("source", "table", "path")),
                    mock(SchemaConfig.class),
                    mock(IcebergTableProps.class),
                    WriterOptions.DEFAULT,
                    Map.of("type", "parquet"),
                    null))
        .isInstanceOf(UnsupportedOperationException.class)
        .hasMessageContaining("Iceberg Catalog sources can only be used to manage Iceberg tables.");
  }

  @Test
  public void testTruncateTableShouldThrowWhenSupportKeyIsDisabled() {
    when(pluginSabotContext.getOptionManager()).thenReturn(optionManager);
    when(optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)).thenReturn(false);
    assertThatThrownBy(() -> plugin.truncateTable(null, null, null))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void testGetIdShouldThrowWhenSupportKeyIsDisabled() {
    when(pluginSabotContext.getOptionManager()).thenReturn(optionManager);
    when(optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)).thenReturn(false);
    assertThatThrownBy(() -> plugin.getId()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void testGetIdShouldReturnStoragePluginId() {
    when(pluginSabotContext.getOptionManager()).thenReturn(optionManager);
    when(optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)).thenReturn(true);
    assertEquals(storagePluginId, plugin.getId());
  }

  @Test
  public void testAlterTableShouldThrowWhenSupportKeyIsDisabled() {
    when(pluginSabotContext.getOptionManager()).thenReturn(optionManager);
    when(optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)).thenReturn(false);
    assertThatThrownBy(() -> plugin.alterTable(null, null, null, null, null))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void testRollbackTableShouldThrowWhenSupportKeyIsDisabled() {
    when(pluginSabotContext.getOptionManager()).thenReturn(optionManager);
    when(optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)).thenReturn(false);
    assertThatThrownBy(() -> plugin.rollbackTable(null, null, null, null, null))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  public void testValidateOnStartWhenSupportKeyIsEnabled() throws Exception {
    when(pluginSabotContext.getOptionManager()).thenReturn(optionManager);
    when(optionManager.getOption(RESTCATALOG_PLUGIN_ENABLED)).thenReturn(true);
    assertDoesNotThrow(() -> plugin.validateOnStart());
  }

  @Test
  public void testValidateOnStartWhenSupportKeyIsDisabled() {
    when(pluginSabotContext.getOptionManager()).thenReturn(optionManager);
    when(optionManager.getOption(RESTCATALOG_PLUGIN_ENABLED)).thenReturn(false);
    assertThatThrownBy(() -> plugin.validateOnStart())
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Iceberg Catalog Source is not supported.");
  }

  @Test
  public void testBuildCatalogProperties() {
    Configuration configuration = new Configuration();
    Map<String, String> actualProps = plugin.buildCatalogProperties(configuration);
    assertThat(actualProps)
        .containsEntry(CatalogProperties.URI, "http://localhost:8181/catalog/api");
    assertThat(actualProps).containsEntry(IcebergUtils.ENABLE_AZURE_ABFSS_SCHEME, "true");
    assertThat(actualProps)
        .containsEntry(CatalogProperties.CATALOG_IMPL, "org.apache.iceberg.rest.RESTCatalog");
  }

  @Test
  public void testBuildingIcebergModelForAlterTable() {
    this.plugin =
        new RestIcebergCatalogPluginMock(
            mockPluginConfig, pluginSabotContext, "resticebergcatalog", () -> storagePluginId);
    IcebergModel icebergModel =
        RequestContext.current()
            .with(UserContext.CTX_KEY, UserContext.SYSTEM_USER_CONTEXT)
            .callUnchecked(
                () ->
                    plugin.getIcebergModel(
                        "s3://bucket/location", List.of("source1", "f1", "t1"), "userName"));
    assertThat(icebergModel).isInstanceOf(IcebergCatalogModel.class);
    IcebergCatalogModel icebergCatalogModel = (IcebergCatalogModel) icebergModel;
    assertThat(icebergCatalogModel.getQueryUserId()).isEqualTo(SYSTEM_USER_CONTEXT_ID);
  }

  @Test
  public void testDropTableThrowsCatalogNotFoundError() {
    when(optionManager.getOption(RESTCATALOG_PLUGIN_MUTABLE_ENABLED)).thenReturn(true);
    NamespaceKey namespaceKey =
        new NamespaceKey(Arrays.asList("catalogTest", "folderTest", "tableTest"));
    doThrow(new NoSuchTableException(""))
        .when(mockCatalogAccessor)
        .dropTable(namespaceKey.getPathComponents());
    assertThatThrownBy(() -> plugin.dropTable(namespaceKey, null, null))
        .isInstanceOf(CatalogEntityNotFoundException.class)
        .hasMessageContaining("not found");
  }

  @Test
  public void testLoadViewMetadata() {
    // Setup
    BaseView mockBaseView = mock(BaseView.class);
    ViewOperations viewOperations = mock(ViewOperations.class);
    ViewMetadata mockViewMetadata = mock(ViewMetadata.class);
    TableIdentifier viewIdentifier = TableIdentifier.of("namespace", "viewName");

    // Configure mock behavior
    when(mockCatalogAccessor.loadView(eq(viewIdentifier))).thenReturn(mockBaseView);
    when(mockBaseView.operations()).thenReturn(viewOperations);
    when(viewOperations.current()).thenReturn(mockViewMetadata);

    // Execute the method
    plugin.loadViewMetadata(viewIdentifier);

    // Verify
    verify(mockCatalogAccessor).loadView(eq(viewIdentifier));
  }

  @Test
  public void testEnableS3V2ClientIsPropagated() {
    when(optionManager.getOption(ExecConstants.ENABLE_S3_V2_CLIENT)).thenReturn(true);
    this.plugin =
        new RestIcebergCatalogPluginMock(
            mockPluginConfig, pluginSabotContext, "resticebergcatalog", () -> storagePluginId);
    assertTrue(
        this.plugin
            .getFsConfCopy()
            .getBoolean(ExecConstants.ENABLE_S3_V2_CLIENT.getOptionName(), false));
  }
}
