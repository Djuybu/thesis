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

import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchViewException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.view.View;

/**
 * Provides create, update, & delete functionality between IcebergCatalogPlugin and Iceberg Catalog
 * implementation.
 */
public interface SupportsIcebergDatasetCUD {
  Table createTable(
      List<String> tablePathComponents,
      Schema schema,
      PartitionSpec partitionSpec,
      SortOrder sortOrder,
      @Nullable String location,
      Map<String, String> tableProperties);

  View createView(
      List<String> viewPathComponents,
      @Nullable String location,
      List<String> workspaceSchemaPath,
      Schema schema,
      String sql)
      throws AlreadyExistsException, NoSuchNamespaceException;

  // TODO (DX-102224): Remove location since it's calculated

  View updateView(
      List<String> viewPathComponents,
      @Nullable String location,
      List<String> workspaceSchemaPath,
      Schema schema,
      String sql)
      throws NoSuchViewException;

  // TODO (DX-102224): Remove location since it's calculated

  void dropTable(List<String> dataset);

  TableOperations createIcebergTableOperations(
      FileIO fileIO, List<String> dataset, @Nullable String userName, @Nullable String userId);

  TableOperations createIcebergTableOperationsForCtas(
      FileIO fileIO,
      List<String> dataset,
      Schema schema,
      @Nullable String userName,
      @Nullable String userId);

  void dropView(List<String> viewPathComponents) throws NoSuchViewException;

  String getDatasetLocationFromExistingNamespaceLocationUri(List<String> dataset);
}
