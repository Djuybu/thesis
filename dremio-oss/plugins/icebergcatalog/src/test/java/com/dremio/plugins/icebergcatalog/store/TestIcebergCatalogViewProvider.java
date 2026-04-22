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

import static com.dremio.service.namespace.dataset.proto.DatasetType.VIRTUAL_DATASET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.connector.metadata.DatasetStats;
import com.dremio.connector.metadata.EntityPath;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.iceberg.IcebergViewMetadata;
import com.dremio.exec.store.iceberg.SchemaConverter;
import com.dremio.exec.util.ViewFieldsHelper;
import com.dremio.service.namespace.dataset.DatasetVersion;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.FieldOrigin;
import com.dremio.service.namespace.dataset.proto.ParentDataset;
import com.dremio.service.namespace.dataset.proto.ViewFieldType;
import com.dremio.service.namespace.dataset.proto.VirtualDataset;
import com.dremio.service.namespace.proto.EntityId;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.view.BaseView;
import org.apache.iceberg.view.SQLViewRepresentation;
import org.apache.iceberg.view.ViewHistoryEntry;
import org.apache.iceberg.view.ViewMetadata;
import org.apache.iceberg.view.ViewOperations;
import org.apache.iceberg.view.ViewVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class TestIcebergCatalogViewProvider {
  private static EntityPath entityPath;
  private static BaseView view;
  private static ViewMetadata viewMetadata;
  private static ViewVersion viewVersion;
  private static SQLViewRepresentation sqlViewRepresentation;
  private static ViewOperations viewOperations;
  private static IcebergCatalogViewProvider provider;
  private static Schema schema;
  private static Namespace defaultNamespace;

  @BeforeAll
  public static void setUp() {
    entityPath = mock(EntityPath.class);
    view = mock(BaseView.class);
    viewMetadata = mock(ViewMetadata.class);
    viewVersion = mock(ViewVersion.class);
    sqlViewRepresentation = mock(SQLViewRepresentation.class);
    viewOperations = mock(ViewOperations.class);
    defaultNamespace = mock(Namespace.class);
    schema = mock(Schema.class);

    when(view.name()).thenReturn("viewName");
    when(view.currentVersion()).thenReturn(viewVersion);
    when(viewVersion.defaultNamespace()).thenReturn(defaultNamespace);
    when(defaultNamespace.levels()).thenReturn(new String[] {"level1", "level2"});
    when(view.operations()).thenReturn(viewOperations);
    when(viewOperations.current()).thenReturn(viewMetadata);
    when(viewMetadata.currentVersion()).thenReturn(viewVersion);
    when(viewMetadata.schema()).thenReturn(schema);
    when(schema.columns()).thenReturn(List.of());
    when(viewMetadata.location()).thenReturn("location");
    when(viewMetadata.metadataFileLocation()).thenReturn("metadataLocation");
    when(viewMetadata.uuid()).thenReturn("uniqueId");
    when(viewMetadata.history()).thenReturn(List.of(mock(ViewHistoryEntry.class)));
    when(viewMetadata.history().get(0).timestampMillis()).thenReturn(123456789L);
    when(viewMetadata.currentVersion().timestampMillis()).thenReturn(987654321L);
    when(viewMetadata.currentVersion().defaultCatalog()).thenReturn("catalog");
    when(viewVersion.representations()).thenReturn(List.of(sqlViewRepresentation));
    when(sqlViewRepresentation.sql()).thenReturn("SELECT * FROM table");
    when(sqlViewRepresentation.dialect()).thenReturn("SQL");

    provider = new IcebergCatalogViewProvider(entityPath, () -> view);
  }

  @Test
  public void testGetDatasetPath() {
    assertEquals(entityPath, provider.getDatasetPath());
  }

  @Test
  public void testGetView() {
    assertEquals(view, provider.getView());
  }

  @Test
  public void testGetViewMetadata() {
    assertEquals(viewMetadata, provider.getViewMetadata());
  }

  @Test
  public void testGetFormatVersion() {
    when(viewMetadata.formatVersion()).thenReturn(1);
    assertEquals(IcebergViewMetadata.of(1), provider.getFormatVersion());
  }

  @Test
  public void testGetSchema() {
    Schema schema = mock(Schema.class);
    when(view.schema()).thenReturn(schema);
    assertEquals(schema, provider.getSchema());
  }

  @Test
  public void testGetSql() {
    assertEquals("SELECT * FROM table", provider.getSql());
  }

  @Test
  public void testGetSchemaPath() {
    assertEquals(List.of("level1", "level2"), provider.getSchemaPath());
  }

  @Test
  public void testGetLocation() {
    when(viewMetadata.location()).thenReturn("location");
    assertEquals("location", provider.getLocation());
  }

  @Test
  public void testGetMetadataLocation() {
    when(viewMetadata.metadataFileLocation()).thenReturn("metadataLocation");
    assertEquals("metadataLocation", provider.getMetadataLocation());
  }

  @Test
  public void testGetUniqueId() {
    when(viewMetadata.uuid()).thenReturn("uniqueId");
    assertEquals("uniqueId", provider.getUniqueId());
  }

  @Test
  public void testGetProperties() {
    when(viewMetadata.properties()).thenReturn(Map.of("key", "value"));
    assertEquals(Map.of("key", "value"), provider.getProperties());
  }

  @Test
  public void testGetCreatedAt() {
    when(viewMetadata.history()).thenReturn(List.of(mock(ViewHistoryEntry.class)));
    when(viewMetadata.history().get(0).timestampMillis()).thenReturn(123456789L);
    assertEquals(123456789L, provider.getCreatedAt());
  }

  @Test
  public void testGetLastModifiedAt() {
    when(viewMetadata.currentVersion().timestampMillis()).thenReturn(987654321L);
    assertEquals(987654321L, provider.getLastModifiedAt());
  }

  @Test
  public void testGetDialect() {
    assertEquals("SQL", provider.getDialect());
  }

  @Test
  public void testGetCatalog() {
    when(viewMetadata.currentVersion().defaultCatalog()).thenReturn("catalog");
    assertEquals("catalog", provider.getCatalog());
  }

  @Test
  public void testGetDatasetStats() {
    // Call the method
    DatasetStats result = provider.getDatasetStats();
    // Verify the result
    assertNull(result, "Expected getDatasetStats to return null");
  }

  @Test
  public void testGetRecordSchema() {
    // Create a real Iceberg schema
    Schema icebergSchema =
        new Schema(
            Types.NestedField.required(1, "id", Types.IntegerType.get()),
            Types.NestedField.optional(2, "name", Types.StringType.get()));

    // Mock the behavior of the view to return the real schema
    when(provider.getSchema()).thenReturn(icebergSchema);

    // Use the real SchemaConverter
    SchemaConverter schemaConverter = SchemaConverter.getBuilder().build();

    org.apache.arrow.vector.types.pojo.Schema result = provider.getRecordSchema();

    // Verify the result
    assertNotNull(result, "Expected getRecordSchema to return a non-null Schema");
    // Compare schemas
    BatchSchema expectedSchema = schemaConverter.fromIceberg(icebergSchema);
    assertEquals(
        expectedSchema,
        new BatchSchema(result.getFields()),
        "Expected the returned Schema to match the Arrow Schema");
    assertEquals(
        icebergSchema.asStruct().fields().size(),
        result.getFields().size(),
        "Expected the returned Schema to match the Iceberg Schema field count");
  }

  @Test
  public void testNewDeepConfigWithShallowConfig() {
    // Create a shallow DatasetConfig
    DatasetConfig shallowConfig = new DatasetConfig();
    shallowConfig.setId(new EntityId().setId(UUID.randomUUID().toString()));
    shallowConfig.setName("testView");
    shallowConfig.setFullPathList(List.of("test", "view"));
    shallowConfig.setType(VIRTUAL_DATASET);

    org.apache.iceberg.Schema icebergSchema =
        new Schema(
            Types.NestedField.required(1, "id", Types.IntegerType.get()),
            Types.NestedField.optional(2, "name", Types.StringType.get()));

    when(provider.getSchema()).thenReturn(icebergSchema);

    DatasetConfig deepConfig = provider.newDeepConfig(shallowConfig);

    // Verify the result
    assertNotNull(deepConfig, "Expected newDeepConfig to return a non-null DatasetConfig");
    assertEquals(shallowConfig.getId(), deepConfig.getId(), "Expected IDs to match");
    assertEquals(shallowConfig.getName(), deepConfig.getName(), "Expected names to match");
    assertEquals(
        shallowConfig.getFullPathList(),
        deepConfig.getFullPathList(),
        "Expected full paths to match");
    assertEquals(shallowConfig.getType(), deepConfig.getType(), "Expected types to match");
    assertEquals(123456789L, deepConfig.getCreatedAt(), "Expected created at timestamps to match");
    assertEquals(
        987654321L, deepConfig.getLastModified(), "Expected last modified at timestamps to match");
    assertEquals(
        "SELECT * FROM table", deepConfig.getVirtualDataset().getSql(), "Expected SQL to match");
    assertEquals(
        "catalog",
        deepConfig.getVirtualDataset().getContextList().get(0),
        "Expected catalog to match");
    assertThat(deepConfig.getVirtualDataset().getSqlFieldsList())
        .as("Expected fields to be updated to Iceberg schema")
        .hasSize(2)
        .extracting("name")
        .containsExactly("id", "name");
  }

  @Test
  public void testNewDeepConfigWithFullConfig() {
    final BatchSchema batchSchema = createTestBatchSchema();

    // Create a fullish DatasetConfig
    DatasetConfig fullConfig = new DatasetConfig();
    fullConfig.setId(new EntityId().setId(UUID.randomUUID().toString()));
    fullConfig.setName("testView");
    fullConfig.setFullPathList(List.of("test", "view"));
    fullConfig.setType(VIRTUAL_DATASET);

    VirtualDataset virtualDataset = new VirtualDataset();
    List<ViewFieldType> fieldTypes = ViewFieldsHelper.getBatchSchemaFields(batchSchema);
    ParentDataset parentDataset = new ParentDataset().setDatasetPathList(List.of("test", "parent"));
    ParentDataset grandParentDataset =
        new ParentDataset().setDatasetPathList(List.of("test", "grandparent"));
    FieldOrigin fieldOrigin = new FieldOrigin().setName("fieldOrigin");
    List<String> requiredFields = ImmutableList.of("requiredField");
    boolean defaultReflectionEnabled = true;
    boolean schemaOutdated = true;
    virtualDataset
        .setVersion(DatasetVersion.newVersion())
        .setSqlFieldsList(fieldTypes)
        .setParentsList(ImmutableList.of(parentDataset))
        .setContextList(ImmutableList.of("catalog", "namespace"))
        .setFieldOriginsList(ImmutableList.of(fieldOrigin))
        .setGrandParentsList(ImmutableList.of(grandParentDataset))
        .setRequiredFieldsList(requiredFields)
        .setCalciteFieldsList(fieldTypes)
        .setDefaultReflectionEnabled(defaultReflectionEnabled)
        .setSchemaOutdated(schemaOutdated)
        .setFieldUpstreamsList(ImmutableList.of(fieldOrigin));
    fullConfig.setVirtualDataset(virtualDataset);
    fullConfig.setRecordSchema(batchSchema.toByteString());

    org.apache.iceberg.Schema icebergSchema =
        new Schema(
            Types.NestedField.required(1, "id", Types.IntegerType.get()),
            Types.NestedField.optional(2, "name", Types.StringType.get()));

    when(provider.getSchema()).thenReturn(icebergSchema);

    DatasetConfig deepConfig = provider.newDeepConfig(fullConfig);
    final VirtualDataset deepVirtualDataset = deepConfig.getVirtualDataset();
    // Verify the result
    assertNotNull(deepConfig, "Expected newDeepConfig to return a non-null DatasetConfig");
    assertEquals(fullConfig.getId(), deepConfig.getId(), "Expected IDs to match");
    assertEquals(fullConfig.getName(), deepConfig.getName(), "Expected names to match");
    assertEquals(
        fullConfig.getFullPathList(), deepConfig.getFullPathList(), "Expected full paths to match");
    assertEquals(fullConfig.getType(), deepConfig.getType(), "Expected types to match");
    assertEquals(123456789L, deepConfig.getCreatedAt(), "Expected created at timestamps to match");
    assertEquals(
        987654321L, deepConfig.getLastModified(), "Expected last modified at timestamps to match");
    assertEquals("SELECT * FROM table", deepVirtualDataset.getSql(), "Expected SQL to match");
    assertEquals(
        "catalog", deepVirtualDataset.getContextList().get(0), "Expected catalog to match");
    assertThat(deepVirtualDataset.getSqlFieldsList())
        .as("Expected fields to NOT be updated")
        .hasSize(2)
        .extracting("name")
        .containsExactly("field1", "field2");
    assertEquals(ImmutableList.of(parentDataset), deepVirtualDataset.getParentsList());
    assertEquals(ImmutableList.of(fieldOrigin), deepVirtualDataset.getFieldOriginsList());
    assertEquals(ImmutableList.of(grandParentDataset), deepVirtualDataset.getGrandParentsList());
    assertEquals(requiredFields, deepVirtualDataset.getRequiredFieldsList());
    assertEquals(fieldTypes, deepVirtualDataset.getCalciteFieldsList());
    assertEquals(defaultReflectionEnabled, deepVirtualDataset.getDefaultReflectionEnabled());
    assertEquals(false, deepVirtualDataset.getSchemaOutdated());
    assertEquals(ImmutableList.of(fieldOrigin), deepVirtualDataset.getFieldUpstreamsList());
  }

  @Test
  public void testNewDeepConfigOnFieldsAlreadySetIgnoresIcebergSchema() {
    final DatasetConfig config = createBaseConfig();
    final BatchSchema batchSchema = createTestBatchSchema();
    final List<ViewFieldType> fieldTypes = ViewFieldsHelper.getBatchSchemaFields(batchSchema);

    config.getVirtualDataset().setSqlFieldsList(fieldTypes);
    config.setRecordSchema(batchSchema.toByteString());

    when(provider.getSchema()).thenReturn(createTestIcebergSchema());

    final DatasetConfig deepConfig = provider.newDeepConfig(config);

    assertThat(deepConfig.getVirtualDataset().getSqlFieldsList()).isEqualTo(fieldTypes);
    assertThat(BatchSchema.deserialize(config.getRecordSchema())).isEqualTo(batchSchema);
  }

  @Test
  public void testNewDeepConfigOnFieldsNotSetUsesIcebergSchema() {
    final DatasetConfig config = createBaseConfig();
    final Schema icebergSchema = createTestIcebergSchema();
    when(provider.getSchema()).thenReturn(icebergSchema);

    final BatchSchema expectedSchema =
        new BatchSchema(
            SchemaConverter.getBuilder().build().fromIceberg(icebergSchema).getFields());
    final List<ViewFieldType> expectedFields =
        ViewFieldsHelper.getBatchSchemaFields(expectedSchema);

    final DatasetConfig deepConfig = provider.newDeepConfig(config);

    assertThat(deepConfig.getVirtualDataset().getSqlFieldsList()).isEqualTo(expectedFields);
    assertThat(BatchSchema.deserialize(config.getRecordSchema())).isEqualTo(expectedSchema);
  }

  private DatasetConfig createBaseConfig() {
    final DatasetConfig config = new DatasetConfig();
    config.setVirtualDataset(new VirtualDataset());
    return config;
  }

  private Schema createTestIcebergSchema() {
    return new Schema(
        Types.NestedField.required(1, "differentfield1", Types.IntegerType.get()),
        Types.NestedField.optional(2, "differentfield2", Types.StringType.get()));
  }

  private BatchSchema createTestBatchSchema() {
    return BatchSchema.newBuilder()
        .addField(new Field("field1", FieldType.nullable(new ArrowType.Utf8()), null))
        .addField(new Field("field2", FieldType.nullable(new ArrowType.Int(32, true)), null))
        .build();
  }
}
