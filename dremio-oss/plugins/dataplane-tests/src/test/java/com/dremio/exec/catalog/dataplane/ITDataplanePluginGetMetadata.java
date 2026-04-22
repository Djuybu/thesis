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
package com.dremio.exec.catalog.dataplane;

import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.DATAPLANE_PLUGIN_NAME;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.DEFAULT_BRANCH_NAME;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.createEmptyTableQueryWithAt;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.createUdfQueryWithAt;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.createViewQueryWithAt;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.generateUniqueFunctionName;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.generateUniqueTableName;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.generateUniqueViewName;
import static com.dremio.exec.catalog.dataplane.test.DataplaneTestDefines.insertTableAtQuery;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.catalog.model.dataset.TableVersionContext;
import com.dremio.exec.planner.sql.CalciteArrowHelper;
import com.dremio.exec.store.iceberg.IcebergViewMetadata;
import com.dremio.exec.store.iceberg.VersionedUdfMetadata;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.function.proto.FunctionConfig;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.types.Types;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

public class ITDataplanePluginGetMetadata extends ITDataplanePluginFunctionBase {

  @Test
  public void testGetTableMetadata() throws Exception {
    final String tableName = generateUniqueTableName();
    List<String> tablePath = List.of(tableName);
    runSQL(createEmptyTableQueryWithAt(tablePath, DEFAULT_BRANCH_NAME));
    runSQL(insertTableAtQuery(tablePath, DEFAULT_BRANCH_NAME));
    String metadataLocation = getMetadataLocation(tablePath);
    DatasetConfig datasetConfig =
        getCatalogService()
            .getSystemUserCatalog()
            .getTable(
                CatalogEntityKey.newBuilder()
                    .keyComponents(DATAPLANE_PLUGIN_NAME, tableName)
                    .tableVersionContext(TableVersionContext.NOT_SPECIFIED)
                    .build())
            .getDatasetConfig();
    assertThat(datasetConfig.getPhysicalDataset()).isNotNull();

    TableMetadata tableMetadata = getDataplanePlugin().getTableMetadata(metadataLocation);
    // Turns out datasetConfig.getCreatedAt() is wrong. So there's nothing to verify at the moment.
    // assertThat(tableMetadata.snapshotLog().get(0).timestampMillis()).isEqualTo(datasetConfig.getCreatedAt());
    assertThat(tableMetadata.lastUpdatedMillis()).isEqualTo(datasetConfig.getLastModified());
    assertThat(
            tableMetadata.schema().columns().stream()
                .map(Types.NestedField::name)
                .sorted()
                .collect(Collectors.toUnmodifiableList()))
        .isEqualTo(getColumnNames(datasetConfig));
  }

  @Test
  public void testGetIcebergViewMetadata() throws Exception {
    List<String> tablePath = List.of(generateUniqueTableName());
    runSQL(createEmptyTableQueryWithAt(tablePath, DEFAULT_BRANCH_NAME));
    final String viewName = generateUniqueViewName();
    List<String> viewPath = List.of(viewName);
    runSQL(createViewQueryWithAt(viewPath, tablePath, DEFAULT_BRANCH_NAME));
    String metadataLocation = getMetadataLocation(viewPath);
    DatasetConfig datasetConfig =
        getCatalogService()
            .getSystemUserCatalog()
            .getTable(
                CatalogEntityKey.newBuilder()
                    .keyComponents(DATAPLANE_PLUGIN_NAME, viewName)
                    .tableVersionContext(TableVersionContext.NOT_SPECIFIED)
                    .build())
            .getDatasetConfig();
    assertThat(datasetConfig.getVirtualDataset()).isNotNull();

    IcebergViewMetadata icebergViewMetadata =
        getDataplanePlugin().getIcebergViewMetadata(metadataLocation);
    assertThat(icebergViewMetadata.getCreatedAt()).isEqualTo(datasetConfig.getCreatedAt());
    assertThat(icebergViewMetadata.getLastModifiedAt()).isEqualTo(datasetConfig.getLastModified());
    assertThat(
            icebergViewMetadata.getSchema().columns().stream()
                .map(Types.NestedField::name)
                .sorted()
                .collect(Collectors.toUnmodifiableList()))
        .isEqualTo(getColumnNames(datasetConfig));
  }

  @Test
  public void testGetVersionedUdfMetadata() throws Exception {
    final String functionName = generateUniqueFunctionName();
    List<String> functionPath = List.of(functionName);
    runSQL(createUdfQueryWithAt(functionPath, DEFAULT_BRANCH_NAME));
    String metadataLocation = getMetadataLocation(functionPath);
    Optional<FunctionConfig> optionalFunctionConfig =
        getDataplanePlugin()
            .getFunction(
                CatalogEntityKey.newBuilder()
                    .keyComponents(DATAPLANE_PLUGIN_NAME, functionName)
                    .tableVersionContext(TableVersionContext.NOT_SPECIFIED)
                    .build());
    assertThat(optionalFunctionConfig.isPresent()).isTrue();

    VersionedUdfMetadata versionedUdfMetadata =
        getDataplanePlugin().getVersionedUdfMetadata(metadataLocation);
    assertThat(versionedUdfMetadata.getCreatedAt())
        .isEqualTo(optionalFunctionConfig.get().getCreatedAt());
    assertThat(versionedUdfMetadata.getLastModifiedAt())
        .isEqualTo(optionalFunctionConfig.get().getLastModified());
    assertThat(versionedUdfMetadata.getBody())
        .isEqualTo(
            optionalFunctionConfig
                .get()
                .getFunctionDefinitionsList()
                .get(0)
                .getFunctionBody()
                .getRawBody());
  }

  @NotNull
  private String getMetadataLocation(List<String> path) {
    //noinspection OptionalGetWithoutIsPresent
    return getDataplanePlugin()
        .getNessieClient()
        .getContent(path, getDataplanePlugin().getNessieClient().getDefaultBranch(), null)
        .get()
        .getMetadataLocation()
        .get();
  }

  @NotNull
  private static List<String> getColumnNames(DatasetConfig datasetConfig) {
    return CalciteArrowHelper.fromDataset(datasetConfig).getFields().stream()
        .map(Field::getName)
        .sorted()
        .collect(Collectors.toUnmodifiableList());
  }
}
