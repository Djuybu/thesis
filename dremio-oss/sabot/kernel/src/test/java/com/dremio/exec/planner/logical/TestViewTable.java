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
package com.dremio.exec.planner.logical;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.exec.catalog.CatalogIdentity;
import com.dremio.exec.catalog.CatalogUser;
import com.dremio.exec.catalog.DatasetMetadataState;
import com.dremio.exec.dotfile.View;
import com.dremio.exec.record.BatchSchema;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.google.common.collect.ImmutableList;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestViewTable {

  private NamespaceKey path;
  private View view;
  private CatalogIdentity viewOwner;
  private BatchSchema schema;
  private DatasetConfig config;
  private DatasetMetadataState datasetMetadataState;

  @BeforeEach
  public void setUp() {
    path = new NamespaceKey("testPath");
    RelDataType rowType = mock(RelDataType.class);
    when(rowType.getSqlTypeName()).thenReturn(SqlTypeName.INTEGER);
    when(rowType.getFieldCount()).thenReturn(1);
    when(rowType.getFieldList()).thenReturn(ImmutableList.of());
    view = new View("testPath", "SELECT 1", rowType, ImmutableList.of(), ImmutableList.of());
    viewOwner = new CatalogUser("testUser");
    schema = BatchSchema.EMPTY;
    config = new DatasetConfig();
    datasetMetadataState =
        DatasetMetadataState.builder().setIsComplete(true).setIsExpired(false).build();
  }

  @Test
  public void testViewTableInitialization() {
    ViewTable viewTable =
        new ViewTable(path, view, viewOwner, config, schema, datasetMetadataState);
    assertNotNull(viewTable);
    assertEquals(path, viewTable.getPath());
    assertEquals(view, viewTable.getView());
    assertEquals(viewOwner, viewTable.getViewOwner());
    assertEquals(config, viewTable.getDatasetConfig());
    assertEquals(schema, viewTable.getSchema());
    assertEquals(datasetMetadataState, viewTable.getDatasetMetadataState());
  }

  @Test
  public void testViewTableWithNullDatasetMetadataState() {
    ViewTable viewTable = new ViewTable(path, view, viewOwner, config, schema, null);
    assertNotNull(viewTable);
    assertNull(viewTable.getDatasetMetadataState());
  }
}
