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

import com.google.common.base.Preconditions;
import java.io.Closeable;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.rest.RESTCatalog;

// Based on com.google.common.base.Suppliers.ExpiringMemoizingSupplier
public class ExpiringCatalogCache implements Supplier<Catalog>, Closeable {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(ExpiringCatalogCache.class);
  private final Object lock = new Object();
  private final Supplier<Catalog> catalogSupplier;
  private final long durationNanos;
  private volatile Catalog catalog;
  private volatile long expirationNanos;

  ExpiringCatalogCache(Supplier<Catalog> catalogSupplier, long duration, TimeUnit unit) {
    this(catalogSupplier, unit.toNanos(duration));
  }

  ExpiringCatalogCache(Supplier<Catalog> catalogSupplier, long durationNanos) {
    this.catalogSupplier = catalogSupplier;
    this.durationNanos = durationNanos;
    this.catalog = null;
    this.expirationNanos = 0;
  }

  public void invalidate() {
    expirationNanos = 0;
    if (catalog != null) {
      try {
        ((Closeable) catalog).close();
      } catch (Exception e) {
        logger.warn("Encountered exception during closing the catalog.");
      }
      catalog = null;
    }
  }

  private Supplier<Catalog> getCatalogSupplier() {
    return catalogSupplier;
  }

  @Override
  public Catalog get() {
    // double-checked locking
    long nanos = expirationNanos;
    long now = System.nanoTime();
    if (expirationNanos == 0 || now - expirationNanos >= 0) {
      synchronized (lock) {
        if (nanos == expirationNanos) {
          invalidate();
          catalog = getCatalogSupplier().get();
          Preconditions.checkArgument(
              catalog instanceof RESTCatalog, "RESTCatalog instance expected");
          expirationNanos = now + durationNanos + 1;
        }
      }
    }
    return catalog;
  }

  @Override
  public void close() {
    invalidate();
  }
}
