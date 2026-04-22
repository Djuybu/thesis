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
import static com.dremio.service.namespace.dataset.proto.DatasetType.VIRTUAL_DATASET;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.connector.ConnectorException;
import com.dremio.connector.metadata.DatasetMetadata;
import com.dremio.connector.metadata.EntityPath;
import com.dremio.connector.metadata.GetMetadataOption;
import com.dremio.connector.metadata.SourceMetadata;
import com.dremio.connector.metadata.ViewDatasetHandle;
import com.dremio.exec.store.DatasetRetrievalOptions;
import com.dremio.exec.store.iceberg.IcebergViewMetadata;
import com.dremio.options.OptionManager;
import com.dremio.service.namespace.DatasetMetadataSaver;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.proto.EntityId;
import com.dremio.service.namespace.split.SplitNamespaceService;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class TestDatasetSaverImplWithViews {
  @Mock private OptionManager optionManager;
  @Mock private SourceMetadata sourceMetadata;
  @Mock private ViewDatasetHandle viewDatasetHandle;
  @Mock private Function<DatasetConfig, DatasetConfig> datasetMutator;
  @Mock private MetadataObjectsUtils metadataObjectsUtils;
  @Mock private NamespaceService systemNamespaceService;
  @Mock private DatasetMetadataSaver datasetMetadataSaver;
  @Mock private MetadataUpdateListener updateListener;

  @Mock private DatasetSaverImpl datasetSaver;
  private DatasetConfig datasetConfig;
  private DatasetRetrievalOptions options;

  @BeforeEach
  public void setUp() {
    MockitoAnnotations.openMocks(this);
    datasetSaver = new DatasetSaverImpl(systemNamespaceService, updateListener, optionManager);
    datasetConfig = new DatasetConfig();

    datasetConfig.setId(new EntityId().setId(UUID.randomUUID().toString()));
    datasetConfig.setCreatedAt(System.currentTimeMillis());
    datasetConfig.setName("viewdataset");
    datasetConfig.setFullPathList(Arrays.asList("viewPath"));
    datasetConfig.setType(VIRTUAL_DATASET);
    options = DatasetRetrievalOptions.DEFAULT;

    // Mock the ViewDatasetHandle
    EntityPath entityPath = new EntityPath(Collections.singletonList("testPath"));
    when(viewDatasetHandle.getDatasetPath()).thenReturn(entityPath);
    when(optionManager.getOption(CatalogOptions.SPLIT_COMPRESSION_TYPE))
        .thenReturn(String.valueOf(NamespaceService.SplitCompression.SNAPPY));
  }

  @Test
  public void testSaveCallsCompleteVirtualDatasetConfig()
      throws ConnectorException, NamespaceException {
    // Mock the dataset metadata
    DatasetMetadata datasetMetadata = mock(DatasetMetadata.class);
    DatasetViewMetadata datasetViewMetadata = mock(DatasetViewMetadata.class);

    IcebergViewMetadata icebergViewMetadata = mock(IcebergViewMetadata.class);
    List<String> viewPath = Arrays.asList("testPath");
    NamespaceKey namespaceKey = new NamespaceKey(viewPath);
    Schema arrowSchema = new Schema(Collections.emptyList());
    when(optionManager.getOption(RESTCATALOG_VIEWS_SUPPORTED)).thenReturn(true);
    when(icebergViewMetadata.getCatalog()).thenReturn(null);
    when(icebergViewMetadata.getSchemaPath()).thenReturn(viewPath);
    when(icebergViewMetadata.getSql()).thenReturn("SELECT * FROM test_table");
    when(icebergViewMetadata.getFormatVersion())
        .thenReturn(IcebergViewMetadata.SupportedIcebergViewSpecVersion.V1);
    when(icebergViewMetadata.getDialect())
        .thenReturn(IcebergViewMetadata.SupportedViewDialectsForWrite.DREMIOSQL.toString());
    when(datasetMetadata.getRecordSchema()).thenReturn(arrowSchema);
    when(datasetMetadata.unwrap(IcebergViewMetadata.class)).thenReturn(icebergViewMetadata);
    doNothing().when(updateListener).metadataUpdated(namespaceKey);
    when(sourceMetadata.getDatasetMetadata(
            ArgumentMatchers.any(),
            ArgumentMatchers.any(),
            ArgumentMatchers.any(GetMetadataOption[].class)))
        .thenReturn(datasetMetadata);
    when(datasetMetadata.unwrap(DatasetViewMetadata.class)).thenReturn(datasetViewMetadata);
    when(systemNamespaceService.newDatasetMetadataSaver(
            namespaceKey,
            datasetConfig.getId(),
            SplitNamespaceService.SplitCompression.SNAPPY,
            0,
            false))
        .thenReturn(datasetMetadataSaver);
    doNothing().when(datasetMetadataSaver).saveDataset(any(), anyBoolean(), any(), any());

    // Call the save method
    datasetSaver.save(
        datasetConfig, viewDatasetHandle, sourceMetadata, false, options, datasetMutator);

    // Verify that completeVirtualDatasetConfig is called
    verify(datasetViewMetadata, times(1)).newDeepConfig(datasetConfig);
  }
}
