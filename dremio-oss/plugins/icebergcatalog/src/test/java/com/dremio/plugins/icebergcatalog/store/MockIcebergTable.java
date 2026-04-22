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
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.DeleteFiles;
import org.apache.iceberg.ExpireSnapshots;
import org.apache.iceberg.HistoryEntry;
import org.apache.iceberg.ManageSnapshots;
import org.apache.iceberg.OverwriteFiles;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.ReplacePartitions;
import org.apache.iceberg.ReplaceSortOrder;
import org.apache.iceberg.RewriteFiles;
import org.apache.iceberg.RewriteManifests;
import org.apache.iceberg.RowDelta;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.SnapshotRef;
import org.apache.iceberg.SortOrder;
import org.apache.iceberg.StatisticsFile;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.UpdateLocation;
import org.apache.iceberg.UpdatePartitionSpec;
import org.apache.iceberg.UpdateProperties;
import org.apache.iceberg.UpdateSchema;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.encryption.EncryptionManager;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.LocationProvider;

public class MockIcebergTable implements Table {
  private final TableIdentifier tableIdentifier;
  private final String rootLocationUri;

  public MockIcebergTable(TableIdentifier tableIdentifier, String rootLocationUri) {
    this.tableIdentifier = tableIdentifier;
    this.rootLocationUri = rootLocationUri;
  }

  @Override
  public void refresh() {}

  @Override
  public TableScan newScan() {
    return null;
  }

  @Override
  public Schema schema() {
    return null;
  }

  @Override
  public Map<Integer, Schema> schemas() {
    return Map.of();
  }

  @Override
  public PartitionSpec spec() {
    return null;
  }

  @Override
  public Map<Integer, PartitionSpec> specs() {
    return Map.of();
  }

  @Override
  public SortOrder sortOrder() {
    return null;
  }

  @Override
  public Map<Integer, SortOrder> sortOrders() {
    return Map.of();
  }

  @Override
  public Map<String, String> properties() {
    return Map.of();
  }

  @Override
  public String location() {
    return rootLocationUri + "/" + tableIdentifier.namespace() + "/" + tableIdentifier.name();
  }

  @Override
  public Snapshot currentSnapshot() {
    return null;
  }

  @Override
  public Snapshot snapshot(long snapshotId) {
    return null;
  }

  @Override
  public Iterable<Snapshot> snapshots() {
    return null;
  }

  @Override
  public List<HistoryEntry> history() {
    return List.of();
  }

  @Override
  public UpdateSchema updateSchema() {
    return null;
  }

  @Override
  public UpdatePartitionSpec updateSpec() {
    return null;
  }

  @Override
  public UpdateProperties updateProperties() {
    return null;
  }

  @Override
  public ReplaceSortOrder replaceSortOrder() {
    return null;
  }

  @Override
  public UpdateLocation updateLocation() {
    return null;
  }

  @Override
  public AppendFiles newAppend() {
    return null;
  }

  @Override
  public RewriteFiles newRewrite() {
    return null;
  }

  @Override
  public RewriteManifests rewriteManifests() {
    return null;
  }

  @Override
  public OverwriteFiles newOverwrite() {
    return null;
  }

  @Override
  public RowDelta newRowDelta() {
    return null;
  }

  @Override
  public ReplacePartitions newReplacePartitions() {
    return null;
  }

  @Override
  public DeleteFiles newDelete() {
    return null;
  }

  @Override
  public ExpireSnapshots expireSnapshots() {
    return null;
  }

  @Override
  public ManageSnapshots manageSnapshots() {
    return null;
  }

  @Override
  public Transaction newTransaction() {
    return null;
  }

  @Override
  public FileIO io() {
    return null;
  }

  @Override
  public EncryptionManager encryption() {
    return null;
  }

  @Override
  public LocationProvider locationProvider() {
    return null;
  }

  @Override
  public List<StatisticsFile> statisticsFiles() {
    return List.of();
  }

  @Override
  public Map<String, SnapshotRef> refs() {
    return Map.of();
  }
}
