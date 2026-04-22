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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.common.utils.PathUtils;
import com.dremio.connector.ConnectorException;
import com.dremio.connector.metadata.GetDatasetOption;
import com.dremio.connector.metadata.SourceMetadata;
import com.dremio.connector.metadata.extensions.SupportsListingDatasets;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceNotFoundException;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestMetadataSynchronizer extends TestMetadataSynchronizerBase {

  @Test
  public void validateSourceDatasetsNotDeletedWhenListDatasetHandlesThrows()
      throws NamespaceException, ConnectorException {
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
    Assertions.assertEquals(1, namespaceService.getAllDatasetsCount(sourceKey));
  }

  @Test
  public void validateMissingDatasetsAndParentFoldersAreDeleted() throws Exception {
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
    Assertions.assertEquals(0, namespaceService.getFolders(sourceKey).size());
  }
}
