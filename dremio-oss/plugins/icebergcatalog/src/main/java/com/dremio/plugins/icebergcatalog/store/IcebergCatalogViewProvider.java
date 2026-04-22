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

import com.dremio.common.utils.ProtostuffUtil;
import com.dremio.connector.metadata.DatasetStats;
import com.dremio.connector.metadata.EntityPath;
import com.dremio.connector.metadata.ViewDatasetHandle;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.iceberg.IcebergViewMetadata;
import com.dremio.exec.store.iceberg.SchemaConverter;
import com.dremio.exec.util.ViewFieldsHelper;
import com.dremio.service.namespace.dataset.DatasetVersion;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.IcebergViewAttributes;
import com.dremio.service.namespace.dataset.proto.ViewFieldType;
import com.dremio.service.namespace.dataset.proto.VirtualDataset;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.iceberg.Schema;
import org.apache.iceberg.view.BaseView;
import org.apache.iceberg.view.SQLViewRepresentation;
import org.apache.iceberg.view.View;
import org.apache.iceberg.view.ViewMetadata;
import org.apache.iceberg.view.ViewVersion;

public class IcebergCatalogViewProvider implements ViewDatasetHandle, IcebergViewMetadata {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(IcebergCatalogViewProvider.class);
  private final EntityPath entityPath;
  private final Supplier<View> viewSupplier;

  public IcebergCatalogViewProvider(
      final EntityPath entityPath, final Supplier<View> viewSupplier) {
    this.entityPath = entityPath;
    this.viewSupplier = viewSupplier;
  }

  @Override
  public EntityPath getDatasetPath() {
    return entityPath;
  }

  public BaseView getView() {
    View view = viewSupplier.get();
    Preconditions.checkArgument(view instanceof BaseView);
    return (BaseView) view;
  }

  public ViewMetadata getViewMetadata() {
    return getView().operations().current();
  }

  @Override
  public SupportedIcebergViewSpecVersion getFormatVersion() {
    return IcebergViewMetadata.of(getViewMetadata().formatVersion());
  }

  @Override
  public Schema getSchema() {
    return getView().schema();
  }

  @Override
  public String getSql() {
    ViewVersion viewVersion = getView().currentVersion();
    SQLViewRepresentation sqlViewRepresentation =
        viewVersion.representations().stream()
            .filter(representation -> representation instanceof SQLViewRepresentation)
            .map(representation -> (SQLViewRepresentation) representation)
            .findFirst()
            .orElseThrow(() -> new UnsupportedOperationException("No SQL representation found"));
    return sqlViewRepresentation.sql();
  }

  @Override
  public List<String> getSchemaPath() {
    return Arrays.asList(getViewMetadata().currentVersion().defaultNamespace().levels());
  }

  @Override
  public String getLocation() {
    return getViewMetadata().location();
  }

  @Override
  public String getMetadataLocation() {
    return getViewMetadata().metadataFileLocation();
  }

  @Override
  public String getUniqueId() {
    return getViewMetadata().uuid();
  }

  @Override
  public Map<String, String> getProperties() {
    return getViewMetadata().properties() == null ? Map.of() : getViewMetadata().properties();
  }

  @Override
  public long getCreatedAt() {
    return getViewMetadata().history().get(0).timestampMillis();
  }

  @Override
  public long getLastModifiedAt() {
    return getViewMetadata().currentVersion().timestampMillis();
  }

  @Override
  public String getDialect() {
    ViewVersion viewVersion = getView().currentVersion();
    SQLViewRepresentation sqlViewRepresentation =
        viewVersion.representations().stream()
            .filter(representation -> representation instanceof SQLViewRepresentation)
            .map(representation -> (SQLViewRepresentation) representation)
            .findFirst()
            .orElseThrow(() -> new UnsupportedOperationException("No SQL representation found"));
    return sqlViewRepresentation.dialect();
  }

  @Override
  public String getCatalog() {
    return getViewMetadata().currentVersion().defaultCatalog();
  }

  @Override
  public String toJson() {
    throw new UnsupportedOperationException("toJson is not supported");
  }

  @Override
  public IcebergViewMetadata fromJson(String metadataLocation, String json) {
    throw new UnsupportedOperationException("fromJson is not supported");
  }

  @Override
  public DatasetStats getDatasetStats() {
    // TODO(DX-99176): Implement this method. Have to figure what stats to gather from a view  .
    return null;
  }

  @Override
  public org.apache.arrow.vector.types.pojo.Schema getRecordSchema() {
    final SchemaConverter schemaConverter = SchemaConverter.getBuilder().build();
    return schemaConverter.fromIceberg(getSchema());
  }

  @Override
  public DatasetConfig newDeepConfig(final DatasetConfig currentConfig) {
    final DatasetConfig fullDatasetConfig = initializeFullDatasetConfig(currentConfig);

    fullDatasetConfig.setVirtualDataset(createVirtualDatasetConfig(currentConfig));
    initializeFields(fullDatasetConfig);

    fullDatasetConfig.setCreatedAt(getCreatedAt());
    fullDatasetConfig.setLastModified(getLastModifiedAt());

    return fullDatasetConfig;
  }

  private void initializeFields(final DatasetConfig datasetConfig) {
    // Only write column definitions if we're saving an Iceberg Catalog View definition for the
    // first time (a new Namespace object). For existing Iceberg Catalog Views, the lineage
    // calculation is responsible for updating the column definitions.
    final VirtualDataset virtualDataset = datasetConfig.getVirtualDataset();
    final boolean isRecordSchemaNull = Objects.isNull(datasetConfig.getRecordSchema());
    final boolean isSqlFieldsEmpty = CollectionUtils.isEmpty(virtualDataset.getSqlFieldsList());
    if (isRecordSchemaNull || isSqlFieldsEmpty) {
      if (isRecordSchemaNull != isSqlFieldsEmpty) {
        logger.warn(
            "The record schema and sql fields are not in sync."
                + " Is Record Schema empty: {}. Is SqlFieldsEmpty: {}",
            isRecordSchemaNull,
            isSqlFieldsEmpty);
      }
      final BatchSchema batchSchema = convertToBatchSchema();
      final List<ViewFieldType> viewFieldTypesList = getViewFieldTypes(batchSchema);

      datasetConfig.setRecordSchema(batchSchema.toByteString());
      virtualDataset.setSqlFieldsList(viewFieldTypesList);
    }
  }

  private DatasetConfig initializeFullDatasetConfig(DatasetConfig currentConfig) {
    return currentConfig;
  }

  // Note: this returns the schema specified in the Iceberg object.
  // If the iceberg object is 1. a view and 2. uses "*" to select,
  // then there is a chance the schema information from the iceberg view
  // could be out of date since the Iceberg specification does not guarantee
  // the schema in an Iceberg view gets updated when a dependent table
  // or view's schema gets changed.
  private BatchSchema convertToBatchSchema() {
    return new BatchSchema(getRecordSchema().getFields());
  }

  private List<ViewFieldType> getViewFieldTypes(BatchSchema batchSchema) {
    return ViewFieldsHelper.getBatchSchemaFields(batchSchema);
  }

  private List<String> createViewContext(DatasetConfig fullDatasetConfig) {
    // Table identifiers in the view SQL first get resolved independently without the
    // default-namespace and default-catalog.  If not resolved, then the engine needs to try with
    // the default-namespace and default-catalog using an identifier like:
    // Example: catalog.namespace.identifier
    // However, if the catalog is not specified, then it implies that the identifier is coming
    // from the same catalog as this view. So we add the parent catalog to the context.
    List<String> viewContext = new ArrayList<>();
    if (getCatalog() == null) {
      viewContext.add(fullDatasetConfig.getFullPathList().get(0));
    } else {
      viewContext.add(getCatalog());
    }
    viewContext.addAll(getSchemaPath());
    return viewContext;
  }

  private VirtualDataset createVirtualDatasetConfig(final DatasetConfig currentConfig) {
    final VirtualDataset currentVirtualDataset = currentConfig.getVirtualDataset();
    final VirtualDataset newVirtualDataset =
        currentVirtualDataset == null
            ? new VirtualDataset()
            : ProtostuffUtil.copy(currentVirtualDataset);

    final List<String> viewContext = createViewContext(currentConfig);

    newVirtualDataset.setContextList(viewContext);
    newVirtualDataset.setSql(getSql());
    newVirtualDataset.setVersion(DatasetVersion.newVersion());
    newVirtualDataset.setIcebergViewAttributes(
        new IcebergViewAttributes()
            .setViewSpecVersion(getFormatVersion().name())
            .setViewDialect(getDialect())
            .setMetadataLocation(getMetadataLocation()));
    newVirtualDataset.setSchemaOutdated(false);
    return newVirtualDataset;
  }
}
