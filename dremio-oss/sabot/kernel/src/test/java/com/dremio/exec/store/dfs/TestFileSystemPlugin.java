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
package com.dremio.exec.store.dfs;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.common.scanner.persistence.ScanResult;
import com.dremio.connector.metadata.EntityPath;
import com.dremio.datastore.api.FindByRange;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.catalog.CreateTableOptions;
import com.dremio.exec.catalog.PluginSabotContext;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.catalog.conf.Property;
import com.dremio.exec.hadoop.HadoopFileSystem;
import com.dremio.exec.physical.base.WriterOptions;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.server.SabotContext;
import com.dremio.exec.store.SchemaConfig;
import com.dremio.exec.store.easy.arrow.ArrowFormatPlugin;
import com.dremio.exec.store.easy.arrow.ArrowFormatPluginConfig;
import com.dremio.exec.store.file.proto.FileProtobuf;
import com.dremio.exec.store.iceberg.SupportsFsCreation;
import com.dremio.io.file.FileSystem;
import com.dremio.io.file.Path;
import com.dremio.options.OptionManager;
import com.dremio.sabot.exec.store.easy.proto.EasyProtobuf;
import com.dremio.sabot.exec.store.parquet.proto.ParquetProtobuf;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.PartitionChunkMetadata;
import com.dremio.service.namespace.PartitionChunkMetadataImpl;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.IcebergMetadata;
import com.dremio.service.namespace.dataset.proto.PartitionProtobuf;
import com.dremio.service.namespace.dataset.proto.PhysicalDataset;
import com.dremio.service.namespace.dataset.proto.ReadDefinition;
import com.dremio.service.namespace.file.proto.ParquetFileConfig;
import com.dremio.service.namespace.file.proto.TextFileConfig;
import com.dremio.service.namespace.proto.EntityId;
import com.dremio.service.users.SystemUser;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.protostuff.ByteString;
import java.io.IOException;
import java.nio.file.AccessMode;
import java.util.List;
import java.util.UUID;
import javax.inject.Provider;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class TestFileSystemPlugin {
  private FileSystemPlugin<?> fileSystemPlugin;

  private SabotContext sabotContext;

  private org.apache.hadoop.fs.FileSystem underlyingFs;

  private HadoopFileSystem fileSystem;

  private OptionManager optionManager;

  private static final String TEST_PARQUET_FILE_PATH = "/user/test/data/file.parquet";

  private static final String TEST_CSV_FILE_PATH = "/user/test/data/file.csv";

  private static final String TEST_USER = "TEST_USER";

  private static final NamespaceKey TEST_KEY =
      new NamespaceKey(ImmutableList.of("SPACE_NAME", "SOURCE_NAME", "DATASET_NAME"));

  @Before
  public void setup() throws Exception {
    sabotContext = mock(SabotContext.class);
    final FileSystemWrapper fileSystemWrapper = mock(FileSystemWrapper.class);
    underlyingFs = mock(org.apache.hadoop.fs.FileSystem.class);
    when(underlyingFs.getScheme()).thenReturn("test");
    fileSystem = Mockito.spy(HadoopFileSystem.get(underlyingFs));
    final MockFileSystemConf fileSystemConf = new MockFileSystemConf();
    final String name = "TEST";
    when(fileSystemWrapper.wrap(
            any(FileSystem.class), eq(name), eq(fileSystemConf), eq(null), eq(false), eq(false)))
        .thenReturn(fileSystem);
    when(sabotContext.getFileSystemWrapper()).thenReturn(fileSystemWrapper);
    Provider<StoragePluginId> idProvider = () -> null;
    optionManager = mock(OptionManager.class);
    when(sabotContext.getOptionManager()).thenReturn(optionManager);
    when(optionManager.getOption(PlannerSettings.VALUES_CAST_ENABLED))
        .thenReturn((PlannerSettings.VALUES_CAST_ENABLED.getDefault().getBoolVal()));
    fileSystemPlugin = new FileSystemPlugin<>(fileSystemConf, sabotContext, name, idProvider);
  }

  private PartitionProtobuf.PartitionChunk getEasySplitsPartition(String path) {
    final EasyProtobuf.EasyDatasetSplitXAttr splitExtended =
        EasyProtobuf.EasyDatasetSplitXAttr.newBuilder()
            .setPath(path)
            .setStart(0)
            .setLength(0)
            .setUpdateKey(
                com.dremio.exec.store.file.proto.FileProtobuf.FileSystemCachedEntity.newBuilder()
                    .setPath(path)
                    .setLastModificationTime(0))
            .build();
    final PartitionProtobuf.DatasetSplit datasetSplit =
        PartitionProtobuf.DatasetSplit.newBuilder()
            .setRecordCount(1)
            .setSplitExtendedProperty(splitExtended.toByteString())
            .build();
    return PartitionProtobuf.PartitionChunk.newBuilder()
        .setDatasetSplit(datasetSplit)
        .setSplitCount(1)
        .build();
  }

  private PartitionProtobuf.PartitionChunk getParquetSplitsPartition(String path) {
    final ParquetProtobuf.ParquetDatasetSplitXAttr splitExtended =
        ParquetProtobuf.ParquetDatasetSplitXAttr.newBuilder()
            .setPath(path)
            .setStart(0)
            .setLength(0)
            .setUpdateKey(
                FileProtobuf.FileSystemCachedEntity.newBuilder()
                    .setPath(path)
                    .setLastModificationTime(0)
                    .setLength(0))
            .build();
    final PartitionProtobuf.DatasetSplit datasetSplit =
        PartitionProtobuf.DatasetSplit.newBuilder()
            .setRecordCount(1)
            .setSplitExtendedProperty(splitExtended.toByteString())
            .build();
    return PartitionProtobuf.PartitionChunk.newBuilder()
        .setDatasetSplit(datasetSplit)
        .setSplitCount(1)
        .build();
  }

  @Test
  public void testHasAccessPermissionParquetFile() throws IOException {
    DatasetConfig datasetConfig =
        new DatasetConfig()
            .setId(new EntityId().setId(UUID.randomUUID().toString()))
            .setName(TEST_KEY.toString())
            .setFullPathList(TEST_KEY.getPathComponents())
            .setPhysicalDataset(
                new PhysicalDataset()
                    .setFormatSettings(new ParquetFileConfig().asFileConfig())
                    .setIcebergMetadataEnabled(false))
            .setLastModified(System.currentTimeMillis())
            .setReadDefinition(
                new ReadDefinition().setReadSignature(ByteString.EMPTY).setSplitVersion(1L))
            .setTotalNumSplits(1);
    Mockito.doNothing()
        .when(fileSystem)
        .access(
            Path.of(TEST_PARQUET_FILE_PATH),
            ImmutableSet.of(AccessMode.READ)); // no throw = access granted
    NamespaceService namespaceService = mock(NamespaceService.class);
    when(sabotContext.getNamespaceService(anyString())).thenReturn(namespaceService);

    final PartitionChunkMetadata partitionChunkMetadata =
        new PartitionChunkMetadataImpl(
            getParquetSplitsPartition(TEST_PARQUET_FILE_PATH), null, () -> {}, () -> null);
    Iterable<PartitionChunkMetadata> partitionsIterable = ImmutableList.of(partitionChunkMetadata);
    when(namespaceService.findSplits(any(FindByRange.class))).thenReturn(partitionsIterable);
    boolean hasAccess = fileSystemPlugin.hasAccessPermission(TEST_USER, TEST_KEY, datasetConfig);
    assertTrue(hasAccess);
  }

  /* With unlimited splits enabled (iceberg metadata), the dataset split extended property data for parquet files is
   * serialized as EasyDatasetSplitXAttr, not as ParquetDatasetSplitXAttr
   * Regression test for: DX-51166, DX-37600
   */
  @Test
  public void testHasAccessPermissionParquetFileUnlimitedSplits() throws IOException {
    DatasetConfig datasetConfig =
        new DatasetConfig()
            .setId(new EntityId().setId(UUID.randomUUID().toString()))
            .setName(TEST_KEY.toString())
            .setFullPathList(TEST_KEY.getPathComponents())
            .setPhysicalDataset(
                new PhysicalDataset()
                    .setFormatSettings(new ParquetFileConfig().asFileConfig())
                    .setIcebergMetadataEnabled(true)
                    .setIcebergMetadata(new IcebergMetadata()))
            .setLastModified(System.currentTimeMillis())
            .setReadDefinition(
                new ReadDefinition().setReadSignature(ByteString.EMPTY).setSplitVersion(1L))
            .setTotalNumSplits(1);
    Mockito.doNothing()
        .when(fileSystem)
        .access(
            Path.of(TEST_PARQUET_FILE_PATH),
            ImmutableSet.of(AccessMode.READ)); // no throw = access granted
    NamespaceService namespaceService = mock(NamespaceService.class);
    when(sabotContext.getNamespaceService(anyString())).thenReturn(namespaceService);

    final PartitionChunkMetadata partitionChunkMetadata =
        new PartitionChunkMetadataImpl(
            getEasySplitsPartition(TEST_PARQUET_FILE_PATH), null, () -> {}, () -> null);
    Iterable<PartitionChunkMetadata> partitionsIterable = ImmutableList.of(partitionChunkMetadata);
    when(namespaceService.findSplits(any(FindByRange.class))).thenReturn(partitionsIterable);
    boolean hasAccess = fileSystemPlugin.hasAccessPermission(TEST_USER, TEST_KEY, datasetConfig);
    assertTrue(hasAccess);
  }

  @Test
  public void testHasAccessPermissionEasyFile() throws IOException {
    DatasetConfig datasetConfig =
        new DatasetConfig()
            .setId(new EntityId().setId(UUID.randomUUID().toString()))
            .setName(TEST_KEY.toString())
            .setFullPathList(TEST_KEY.getPathComponents())
            .setPhysicalDataset(
                new PhysicalDataset().setFormatSettings(new TextFileConfig().asFileConfig()))
            .setLastModified(System.currentTimeMillis())
            .setReadDefinition(
                new ReadDefinition().setReadSignature(ByteString.EMPTY).setSplitVersion(1L))
            .setTotalNumSplits(1);
    Mockito.doNothing()
        .when(fileSystem)
        .access(
            Path.of(TEST_CSV_FILE_PATH),
            ImmutableSet.of(AccessMode.READ)); // no throw = access granted
    NamespaceService namespaceService = mock(NamespaceService.class);
    when(sabotContext.getNamespaceService(anyString())).thenReturn(namespaceService);

    final PartitionChunkMetadata partitionChunkMetadata =
        new PartitionChunkMetadataImpl(
            getEasySplitsPartition(TEST_CSV_FILE_PATH), null, () -> {}, () -> null);
    Iterable<PartitionChunkMetadata> partitionsIterable = ImmutableList.of(partitionChunkMetadata);
    when(namespaceService.findSplits(any(FindByRange.class))).thenReturn(partitionsIterable);
    boolean hasAccess = fileSystemPlugin.hasAccessPermission(TEST_USER, TEST_KEY, datasetConfig);
    assertTrue(hasAccess);
  }

  /**
   * Regression test for DX-40512 During JVM shutdown, if the S3A file system is closed while a
   * metadata synchronization is in progress, it is essential that the IO exception be propagated,
   * to abort metadata synchronization. Otherwise, if getDatasetHandle simply returned null, we
   * would incorrectly infer that the dataset no longer exists and can be deleted.
   */
  @Test
  public void testGetDatasetPropagatesFSClosedException() throws IOException {
    OptionManager optionManager = mock(OptionManager.class);
    when(sabotContext.getOptionManager()).thenReturn(optionManager);
    Mockito.doThrow(new IOException("FileSystem is closed!"))
        .when(underlyingFs)
        .exists(any(org.apache.hadoop.fs.Path.class));
    Assert.assertThrows(
        RuntimeException.class,
        () -> fileSystemPlugin.getDatasetHandle(new EntityPath(ImmutableList.of("a", "b"))));
  }

  @Test
  public void testCacheOnRemovalCallsClose() throws Exception {
    LoadingCache<String, org.apache.hadoop.fs.FileSystem> cache = fileSystemPlugin.getHadoopFS();
    org.apache.hadoop.fs.FileSystem mockFileSystem = mock(org.apache.hadoop.fs.FileSystem.class);
    cache.put("key", mockFileSystem);
    cache.invalidate("key");
    verify(mockFileSystem, times(1)).close();
  }

  @Test
  public void testFileSystemPluginCloseCallsFSClose() throws Exception {
    LoadingCache<String, org.apache.hadoop.fs.FileSystem> cache = fileSystemPlugin.getHadoopFS();
    org.apache.hadoop.fs.FileSystem mockFileSystem = mock(org.apache.hadoop.fs.FileSystem.class);
    cache.put("key", mockFileSystem);

    fileSystemPlugin.close();
    verify(mockFileSystem, times(1)).close();
  }

  @Test
  public void testSkipFileExistenceCheck() throws Exception {
    InternalFileConf config = mock(InternalFileConf.class);
    when(config.getPath()).thenReturn(Path.of("rc"));
    when(config.getConnection()).thenReturn("s3:///");
    when(config.getProperties()).thenReturn(ImmutableList.of());
    when(config.getSchemaMutability()).thenReturn(SchemaMutability.SYSTEM_TABLE);

    ScanResult scanResult = mock(ScanResult.class);
    when(scanResult.getImplementations(any(Class.class)))
        .thenReturn(ImmutableSet.of(ArrowFormatPluginConfig.class))
        .thenReturn(ImmutableSet.of(ArrowFormatPlugin.class));

    SabotContext context = mock(SabotContext.class);
    when(context.getClasspathScan()).thenReturn(scanResult);
    when(context.getOptionManager()).thenReturn(optionManager);

    FileSystem fs = mock(FileSystem.class);

    FileSystemPlugin<InternalFileConf> fileSystemPlugin =
        new FileSystemPlugin<>(config, context, "rc", null) {
          @Override
          public FileSystem createFS(SupportsFsCreation.Builder builder) {
            return fs;
          }
        };
    fileSystemPlugin.start();

    fileSystemPlugin.createNewTable(
        TEST_KEY,
        SchemaConfig.newBuilder(() -> SystemUser.SYSTEM_USERNAME)
            .optionManager(optionManager)
            .build(),
        null,
        WriterOptions.DEFAULT,
        ImmutableMap.of("type", ArrowFormatPlugin.ARROW_DEFAULT_NAME),
        CreateTableOptions.builder().setSkipFileExistenceCheck(true).build());
    verify(underlyingFs, never()).exists(any());
  }

  @Test
  public void testPropagateEnableS3V2Client() throws IOException {
    ScanResult scanResult = mock(ScanResult.class);
    when(sabotContext.getClasspathScan()).thenReturn(scanResult);
    when(optionManager.getOption(ExecConstants.ENABLE_S3_V2_CLIENT)).thenReturn(true);

    fileSystemPlugin.start();
    assertTrue(
        fileSystemPlugin
            .getFsConf()
            .getBoolean(ExecConstants.ENABLE_S3_V2_CLIENT.getOptionName(), false));
  }

  class MockFileSystemConf extends FileSystemConf<MockFileSystemConf, MockFileSystemPlugin> {

    @Override
    public MockFileSystemPlugin newPlugin(
        PluginSabotContext pluginSabotContext,
        String name,
        Provider<StoragePluginId> pluginIdProvider) {
      return null;
    }

    @Override
    public Path getPath() {
      return Path.of("/base");
    }

    @Override
    public boolean isImpersonationEnabled() {
      return true;
    }

    @Override
    public List<Property> getProperties() {
      return null;
    }

    @Override
    public String getConnection() {
      return null;
    }

    @Override
    public boolean isPartitionInferenceEnabled() {
      return false;
    }

    @Override
    public SchemaMutability getSchemaMutability() {
      return null;
    }
  }

  public static class MockFileSystemPlugin extends FileSystemPlugin<MockFileSystemConf> {

    public MockFileSystemPlugin(
        MockFileSystemConf config,
        SabotContext context,
        String name,
        Provider<StoragePluginId> idProvider) {
      super(config, context, name, idProvider);
    }
  }
}
