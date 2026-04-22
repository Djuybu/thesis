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
package com.dremio.exec.store.iceberg;

import static com.dremio.exec.store.iceberg.logging.VacuumLoggingUtil.createExpireSnapshotLog;
import static com.dremio.exec.store.iceberg.logging.VacuumLoggingUtil.getVacuumLogger;

import com.dremio.common.logging.StructuredLogger;
import com.dremio.common.utils.protos.QueryIdHelper;
import com.dremio.exec.catalog.VacuumOptions;
import com.dremio.exec.physical.base.OpProps;
import com.dremio.io.file.FileSystem;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.store.iceberg.proto.IcebergProtobuf;
import com.dremio.sabot.exec.store.iceberg.proto.IcebergProtobuf.IcebergDatasetSplitXAttr;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;

public class SingleTableIcebergExpirySnapshotsReader extends IcebergExpirySnapshotsReader {

  private static final StructuredLogger vacuumLogger = getVacuumLogger();

  private final String queryId;
  private final IcebergProtobuf.IcebergDatasetSplitXAttr splitXAttr;
  private final String schemeVariate;
  private final String userId;
  private final List<String> dataset;

  public SingleTableIcebergExpirySnapshotsReader(
      OperatorContext context,
      IcebergDatasetSplitXAttr splitXAttr,
      SupportsIcebergMutablePlugin icebergMutablePlugin,
      OpProps props,
      SnapshotsScanOptions snapshotsScanOptions,
      String schemeVariate,
      String userId,
      List<String> dataset) {
    super(context, icebergMutablePlugin, props, snapshotsScanOptions);
    this.queryId = QueryIdHelper.getQueryId(context.getFragmentHandle().getQueryId());
    this.splitXAttr = splitXAttr;
    this.schemeVariate = schemeVariate;
    this.userId = userId;
    this.dataset = dataset;
  }

  @Override
  protected void setupNextExpiryAction() {
    String metadataPath = splitXAttr.getPath();
    super.setupFsIfNecessary(metadataPath, dataset);

    TableMetadata tableMetadata = TableMetadataParser.read(io, metadataPath);
    boolean commitExpiry = true;
    boolean isExpireSnapshots = true;
    boolean isRemoveOrphanFiles = false;
    if (SnapshotsScanOptions.Mode.ALL_SNAPSHOTS.equals(snapshotsScanOptions.getMode())) {
      isExpireSnapshots = false;
      isRemoveOrphanFiles = true;
      commitExpiry = false;
    } else if (SnapshotsScanOptions.Mode.EXPIRED_SNAPSHOTS.equals(snapshotsScanOptions.getMode())) {
      commitExpiry = false;
    }
    VacuumOptions options =
        VacuumOptions.createInstance(
            isExpireSnapshots,
            isRemoveOrphanFiles,
            snapshotsScanOptions.getOlderThanInMillis(),
            snapshotsScanOptions.getRetainLast(),
            null,
            null);

    currentExpiryAction =
        new IcebergExpiryAction(
            icebergMutablePlugin,
            props,
            context,
            options,
            tableMetadata,
            splitXAttr.getTableName() != null
                ? splitXAttr.getTableName()
                : String.join(".", dataset),
            splitXAttr.getDbName(),
            null,
            io,
            commitExpiry,
            schemeVariate,
            fs.getScheme(),
            userId);
    noMoreActions = true;

    if (commitExpiry) {
      createVacuumExpiryLog(currentExpiryAction, snapshotsScanOptions, metadataPath);
    }
  }

  private void createVacuumExpiryLog(
      IcebergExpiryAction currentExpiryAction,
      SnapshotsScanOptions snapshotsScanOptions,
      String metadataPath) {
    vacuumLogger.info(
        createExpireSnapshotLog(
            queryId,
            currentExpiryAction.getTableName(),
            metadataPath,
            snapshotsScanOptions,
            currentExpiryAction.getExpiredSnapshots().stream()
                .map(snapshotEntry -> String.valueOf(snapshotEntry.getSnapshotId()))
                .collect(Collectors.toList())),
        "");
  }

  @Override
  protected FileSystem getFsFromPlugin(String filePath, List<String> dataset) throws IOException {
    return icebergMutablePlugin.createFS(
        SupportsFsCreation.builder()
            .filePath(filePath)
            .userName(props.getUserName())
            .userId(userId)
            .operatorContext(context)
            .dataset(dataset));
  }
}
