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
package com.dremio.exec.store.iceberg.logging;

import com.dremio.common.logging.StructuredLogger;
import com.dremio.exec.catalog.VacuumOptions;
import com.dremio.exec.store.iceberg.SnapshotsScanOptions;
import com.dremio.exec.store.iceberg.logging.VacuumLogProto.ActionTarget;
import com.dremio.exec.store.iceberg.logging.VacuumLogProto.DeleteOrphanFileInfo;
import com.dremio.exec.store.iceberg.logging.VacuumLogProto.ErrorType;
import com.dremio.exec.store.iceberg.logging.VacuumLogProto.ExpireSnapshotInfo;
import com.dremio.exec.store.iceberg.logging.VacuumLogProto.NessieCommitScanInfo;
import com.dremio.exec.store.iceberg.logging.VacuumLogProto.SnapshotInfo;
import com.dremio.exec.store.iceberg.logging.VacuumLogProto.TableSkipInfo;
import com.dremio.exec.store.iceberg.logging.VacuumLogProto.VacuumCatalogInfo;
import com.dremio.exec.store.iceberg.logging.VacuumLogProto.VacuumLog;
import java.util.List;

public class VacuumLoggingUtil {
  private static final String DELETE_ORPHAN_FILE_ACTION = "DeleteOrphanFile";
  private static final String TABLE_SKIP_ACTION = "TableSkip";
  private static final String NESSIE_COMMIT_SCAN_ACTION = "NessieCommitScan";
  private static final String EXPIRE_SNAPSHOT_ACTION = "ExpireSnapshot";
  private static final String SNAPSHOT_INFO = "SnapshotInfo";
  private static final String VACUUM_CATALOG_INFO_ACTION = "VacuumCatalogInfo";

  public static StructuredLogger getVacuumLogger() {
    return StructuredLogger.get(VacuumLog.class, "VacuumLogger");
  }

  public static VacuumLog createExpireSnapshotLog(
      String queryId,
      String tableId,
      String metadataLocation,
      SnapshotsScanOptions snapshotsScanOptions,
      List<String> snapshots) {
    final ExpireSnapshotInfo.Builder expireSnapshotInfoBuilder =
        ExpireSnapshotInfo.newBuilder()
            .setTable(tableId)
            .setMetadataLocation(metadataLocation)
            .setOlderThanMillis(snapshotsScanOptions.getOlderThanInMillis())
            .setRetainLast(snapshotsScanOptions.getRetainLast());
    for (String s : snapshots) {
      expireSnapshotInfoBuilder.addSnapshotId(s);
    }

    return commonVacuumLogBuilder(queryId, true, expireSnapshotInfoBuilder.build())
        .setErrorType(ErrorType.NO_ERROR)
        .build();
  }

  public static VacuumLog createExpireSnapshotLog(
      String queryId,
      String tableId,
      String metadataLocation,
      SnapshotsScanOptions snapshotsScanOptions,
      ErrorType errorType,
      String error) {
    final ExpireSnapshotInfo.Builder expireSnapshotInfoBuilder =
        ExpireSnapshotInfo.newBuilder()
            .setTable(tableId)
            .setMetadataLocation(metadataLocation)
            .setOlderThanMillis(snapshotsScanOptions.getOlderThanInMillis())
            .setRetainLast(snapshotsScanOptions.getRetainLast());

    return commonVacuumLogBuilder(queryId, true, expireSnapshotInfoBuilder.build())
        .setErrorType(errorType)
        .setError(error)
        .build();
  }

  public static VacuumLog createTableSkipLog(
      String queryId,
      String tableId,
      ErrorType errorType,
      String message,
      VacuumOptions vacuumOptions) {
    return commonVacuumLogBuilder(
            queryId,
            TableSkipInfo.newBuilder()
                .setTable(tableId)
                .setOlderThanMillis(vacuumOptions.getOlderThanInMillis())
                .setRetainLast(vacuumOptions.getRetainLast())
                .build())
        .setErrorType(errorType)
        .setError(message)
        .build();
  }

  public static VacuumLog createTableSkipLog(
      String queryId, String contentId, ErrorType errorType, String message) {
    return commonVacuumLogBuilder(queryId, TableSkipInfo.newBuilder().setTable(contentId).build())
        .setErrorType(errorType)
        .setError(message)
        .build();
  }

  public static VacuumLog createCommitScanLog(
      String queryId, String tableId, String metadataLocation, String snapshotId) {
    return commonVacuumLogBuilder(
            queryId,
            true,
            NessieCommitScanInfo.newBuilder()
                .setTable(tableId)
                .setMetadataLocation(metadataLocation)
                .setSnapshotId(snapshotId)
                .build())
        .setErrorType(ErrorType.NO_ERROR)
        .build();
  }

  public static VacuumLog createCommitScanLog(
      String queryId, String tableId, String metadataLocation, ErrorType errorType, String error) {
    return commonVacuumLogBuilder(
            queryId,
            true,
            NessieCommitScanInfo.newBuilder()
                .setTable(tableId)
                .setMetadataLocation(metadataLocation)
                .build())
        .setErrorType(errorType)
        .setError(error)
        .build();
  }

  public static VacuumLog createCommitScanLog(
      String queryId,
      String tableId,
      String metadataLocation,
      String snapshotId,
      ErrorType errorType,
      String error) {
    return commonVacuumLogBuilder(
            queryId,
            false,
            NessieCommitScanInfo.newBuilder()
                .setTable(tableId)
                .setMetadataLocation(metadataLocation)
                .setSnapshotId(snapshotId)
                .build())
        .setErrorType(errorType)
        .setError(error)
        .build();
  }

  public static VacuumLog createDeleteOrphanFileLog(String queryId, String filePath) {
    return commonVacuumLogBuilder(
            queryId, true, DeleteOrphanFileInfo.newBuilder().setFilePath(filePath).build())
        .setErrorType(ErrorType.NO_ERROR)
        .build();
  }

  public static VacuumLog createDeleteOrphanFileLog(
      String queryId, String filePath, ErrorType errorType, String error) {
    return commonVacuumLogBuilder(
            queryId, false, DeleteOrphanFileInfo.newBuilder().setFilePath(filePath).build())
        .setErrorType(errorType)
        .setError(error)
        .build();
  }

  public static VacuumLog createSnapshotInfoLog(
      String queryId,
      String tableId,
      SnapshotsScanOptions snapshotsScanOptions,
      List<String> snapshots) {
    final SnapshotInfo.Builder snapshotInfoBuilder =
        SnapshotInfo.newBuilder()
            .setTable(tableId)
            .setScanMode(snapshotsScanOptions.getMode().name())
            .setOlderThanMillis(snapshotsScanOptions.getOlderThanInMillis())
            .setRetainLast(snapshotsScanOptions.getRetainLast());
    for (String s : snapshots) {
      snapshotInfoBuilder.addSnapshotId(s);
    }
    return commonVacuumLogBuilder(queryId, snapshotInfoBuilder.build())
        .setErrorType(ErrorType.NO_ERROR)
        .build();
  }

  public static VacuumLog createVacuumCatalogInfoLog(String queryId, String info) {
    final VacuumCatalogInfo.Builder vacuumCatalogInfo =
        VacuumCatalogInfo.newBuilder().setInfo(info);

    return commonVacuumLogBuilder(queryId, true, vacuumCatalogInfo.build())
        .setErrorType(ErrorType.NO_ERROR)
        .build();
  }

  private static VacuumLog.Builder commonVacuumLogBuilder(
      String queryId, boolean status, ExpireSnapshotInfo expireSnapshotInfo) {
    return VacuumLog.newBuilder()
        .setJobId(queryId)
        .setAction(EXPIRE_SNAPSHOT_ACTION)
        .setStatus(status)
        .setActionTarget(
            ActionTarget.newBuilder().setExpireSnapshotInfo(expireSnapshotInfo).build());
  }

  private static VacuumLog.Builder commonVacuumLogBuilder(
      String queryId, boolean status, NessieCommitScanInfo commitScanInfo) {
    return VacuumLog.newBuilder()
        .setJobId(queryId)
        .setAction(NESSIE_COMMIT_SCAN_ACTION)
        .setStatus(status)
        .setActionTarget(ActionTarget.newBuilder().setCommitScanInfo(commitScanInfo).build());
  }

  private static VacuumLog.Builder commonVacuumLogBuilder(
      String queryId, boolean status, DeleteOrphanFileInfo deleteFileInfo) {
    return VacuumLog.newBuilder()
        .setJobId(queryId)
        .setAction(DELETE_ORPHAN_FILE_ACTION)
        .setStatus(status)
        .setActionTarget(ActionTarget.newBuilder().setDeleteFileInfo(deleteFileInfo).build());
  }

  private static VacuumLog.Builder commonVacuumLogBuilder(
      String queryId, TableSkipInfo tableSkipInfo) {
    return VacuumLog.newBuilder()
        .setJobId(queryId)
        .setAction(TABLE_SKIP_ACTION)
        .setStatus(false)
        .setActionTarget(ActionTarget.newBuilder().setTableSkipInfo(tableSkipInfo).build());
  }

  public static VacuumLog.Builder commonVacuumLogBuilder(
      String queryId, SnapshotInfo snapshotInfo) {
    return VacuumLog.newBuilder()
        .setJobId(queryId)
        .setAction(SNAPSHOT_INFO)
        .setStatus(true)
        .setActionTarget(ActionTarget.newBuilder().setSnapshotInfo(snapshotInfo).build());
  }

  private static VacuumLog.Builder commonVacuumLogBuilder(
      String queryId, boolean status, VacuumCatalogInfo vacuumCatalogInfo) {
    return VacuumLog.newBuilder()
        .setJobId(queryId)
        .setAction(VACUUM_CATALOG_INFO_ACTION)
        .setStatus(status)
        .setActionTarget(ActionTarget.newBuilder().setVacuumCatalogInfo(vacuumCatalogInfo).build());
  }
}
