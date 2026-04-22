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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class TestSourceAccessChecker {

  private SourceAccessChecker sourceAccessChecker;
  private Catalog delegate;

  @BeforeEach
  public void setup() {
    delegate = mock(Catalog.class);
    sourceAccessChecker = new SourceAccessChecker(delegate);
  }

  @ParameterizedTest
  @ValueSource(strings = {"__source", "$source", "__home", "$scratch"})
  public void testGetTableReturnsSuccessfully(String sourceName) throws NamespaceException {
    NamespaceKey namespaceKey = new NamespaceKey(sourceName);
    DatasetConfig datasetConfig = new DatasetConfig();
    when(delegate.getDataset(namespaceKey)).thenReturn(datasetConfig);

    assertDoesNotThrow(() -> sourceAccessChecker.getDataset(namespaceKey));
  }

  @Test
  public void testGetTableThrowsForLogs() throws NamespaceException {
    NamespaceKey namespaceKey = new NamespaceKey("__logs");
    when(delegate.getDataset(namespaceKey)).thenThrow(new RuntimeException("Access denied"));

    assertThrows(RuntimeException.class, () -> sourceAccessChecker.getDataset(namespaceKey));
  }
}
