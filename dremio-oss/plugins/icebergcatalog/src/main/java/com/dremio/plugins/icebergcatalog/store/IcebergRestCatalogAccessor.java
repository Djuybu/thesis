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

import static com.dremio.exec.store.IcebergCatalogPluginOptions.RESTCATALOG_PLUGIN_CATALOG_EXPIRE_SECONDS;

import com.dremio.options.OptionManager;
import com.google.common.base.Preconditions;
import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;

@Deprecated
public class IcebergRestCatalogAccessor extends AbstractRestCatalogAccessor {

  private final Supplier<Catalog> catalogSupplier;

  public IcebergRestCatalogAccessor(
      Supplier<Catalog> catalogSupplier,
      OptionManager optionsManager,
      List<String> allowedNamespaces,
      boolean isRecursiveAllowedNamespaces) {
    super(
        new ExpiringCatalogCache(
            catalogSupplier,
            optionsManager.getOption(RESTCATALOG_PLUGIN_CATALOG_EXPIRE_SECONDS),
            TimeUnit.SECONDS),
        optionsManager,
        allowedNamespaces,
        isRecursiveAllowedNamespaces);
    this.catalogSupplier = catalogSupplier;
  }

  @Override
  protected void checkStateInternal() throws Exception {
    Closeable closeable = (Closeable) catalogSupplier.get();
    closeable.close();
  }

  @Override
  public String getDefaultBaseLocation() {
    Catalog catalog = catalogSupplier.get();
    Preconditions.checkState(
        catalog instanceof RESTCatalog, "Catalog is not an instance of RESTCatalog");
    Map<String, String> props = ((RESTCatalog) catalog).properties();
    return props.get(DEFAULT_BASE_LOCATION);
  }

  @Override
  public Transaction createTableTransactionForNewTable(
      TableIdentifier tableIdentifier, Schema schema) {
    return getCatalog().newCreateTableTransaction(tableIdentifier, schema);
  }
}
