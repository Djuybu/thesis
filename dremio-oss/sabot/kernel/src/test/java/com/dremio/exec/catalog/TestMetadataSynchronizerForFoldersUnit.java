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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.dremio.connector.metadata.SourceMetadata;
import com.dremio.options.OptionManager;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceInvalidStateException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.folder.FolderNamespaceService;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestMetadataSynchronizerForFoldersUnit {
  private static OptionManager optionManager;
  private static SourceMetadata sourceMetadata;
  private static NamespaceKey sourceKey;
  private static Set<NamespaceKey> ancestorsToKeep;
  private static FolderNamespaceService systemNamespaceService;

  @BeforeEach
  public void setup() {
    optionManager = mock(OptionManager.class);
    when(optionManager.getOption(RESTCATALOG_FOLDERS_SUPPORTED)).thenReturn(true);
    sourceMetadata =
        mock(SourceMetadata.class, withSettings().extraInterfaces(SupportsFolderIngestion.class));
    when(((SupportsFolderIngestion) sourceMetadata).getFolderListing())
        .thenReturn(new MockFolderListing(null));
    sourceKey = new NamespaceKey("source");
    ancestorsToKeep = new HashSet<>();
    systemNamespaceService = mock(FolderNamespaceService.class);
  }

  @Test
  public void validateHappyPath() throws NamespaceException {
    MetadataSynchronizer.updateNamespaceServiceWithFolderMetadataFromSource(
        optionManager, sourceMetadata, sourceKey, ancestorsToKeep, systemNamespaceService);
    Assertions.assertEquals(3, ancestorsToKeep.size());
    verify(systemNamespaceService, times(3)).addOrUpdateFolder(any(), any());
  }

  @Test
  public void validateNamespaceErrors() throws NamespaceException {
    NamespaceKey key = new NamespaceKey(Arrays.asList("source", "folder0"));
    doThrow(new NamespaceInvalidStateException("message"))
        .when(systemNamespaceService)
        .addOrUpdateFolder(eq(key), any(), any());

    MetadataSynchronizer.updateNamespaceServiceWithFolderMetadataFromSource(
        optionManager, sourceMetadata, sourceKey, ancestorsToKeep, systemNamespaceService);
    Assertions.assertEquals(3, ancestorsToKeep.size());
    verify(systemNamespaceService, times(3)).addOrUpdateFolder(any(), any());
  }
}
