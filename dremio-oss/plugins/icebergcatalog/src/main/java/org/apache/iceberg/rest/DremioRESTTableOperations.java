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
package org.apache.iceberg.rest;

import com.dremio.exec.store.iceberg.DremioFileIO;
import org.apache.iceberg.LocationProviders;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.encryption.EncryptionManager;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.LocationProvider;
import org.apache.iceberg.util.LocationUtil;

/** Currently wrapping for DremioFileIO only. */
public class DremioRESTTableOperations implements TableOperations {
  private static final String METADATA_FOLDER_NAME = "metadata";

  private final DremioFileIO dremioFileIO;
  private final RESTTableOperations delegate;

  public DremioRESTTableOperations(DremioFileIO dremioFileIO, TableOperations delegate) {
    this.dremioFileIO = dremioFileIO;
    this.delegate = (RESTTableOperations) delegate;
  }

  @Override
  public TableMetadata current() {
    return delegate.current();
  }

  @Override
  public TableMetadata refresh() {
    return delegate.refresh();
  }

  @Override
  public void commit(TableMetadata base, TableMetadata metadata) {
    delegate.commit(base, metadata);
  }

  @Override
  public FileIO io() {
    return dremioFileIO;
  }

  private static String metadataFileLocation(TableMetadata metadata, String filename) {
    String metadataLocation = metadata.properties().get(TableProperties.WRITE_METADATA_LOCATION);

    if (metadataLocation != null) {
      return String.format("%s/%s", LocationUtil.stripTrailingSlash(metadataLocation), filename);
    }

    return String.format("%s/%s/%s", metadata.location(), METADATA_FOLDER_NAME, filename);
  }

  @Override
  public String metadataFileLocation(String s) {
    return delegate.metadataFileLocation(s);
  }

  @Override
  public LocationProvider locationProvider() {
    return delegate.locationProvider();
  }

  @Override
  public TableOperations temp(TableMetadata uncommittedMetadata) {

    return new TableOperations() {
      @Override
      public TableMetadata current() {
        return uncommittedMetadata;
      }

      @Override
      public TableMetadata refresh() {
        throw new UnsupportedOperationException(
            "Cannot call refresh on temporary table operations");
      }

      @Override
      public void commit(TableMetadata base, TableMetadata metadata) {
        throw new UnsupportedOperationException("Cannot call commit on temporary table operations");
      }

      @Override
      public String metadataFileLocation(String fileName) {
        return DremioRESTTableOperations.metadataFileLocation(uncommittedMetadata, fileName);
      }

      @Override
      public LocationProvider locationProvider() {
        return LocationProviders.locationsFor(
            uncommittedMetadata.location(), uncommittedMetadata.properties());
      }

      @Override
      public FileIO io() {
        return DremioRESTTableOperations.this.io();
      }

      @Override
      public EncryptionManager encryption() {
        return DremioRESTTableOperations.this.encryption();
      }

      @Override
      public long newSnapshotId() {
        return DremioRESTTableOperations.this.newSnapshotId();
      }
    };
  }
}
