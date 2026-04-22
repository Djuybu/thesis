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
package com.dremio.exec.planner;

import static com.google.common.base.Preconditions.checkState;

import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.exec.catalog.EntityExplorer;
import com.dremio.exec.catalog.TableMetadataVerifyDataModifiedRequest;
import com.dremio.exec.catalog.TableMetadataVerifyDataModifiedResult;
import com.dremio.exec.catalog.TableMetadataVerifyResult;
import com.dremio.exec.planner.common.ScanRelBase;
import com.dremio.service.namespace.DatasetHelper;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.file.proto.FileType;
import java.util.Optional;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.TableScan;

public class IcebergTableUtils {
  public static class IcebergTableChecker extends StatelessRelShuttleImpl {
    private boolean hasNativeIcebergTable = false;
    private boolean hasUnlimitedSplitsParquet = false;
    private boolean hasNonIcebergTable = false;

    public static IcebergTableChecker check(RelNode relNode) {
      IcebergTableChecker checker = new IcebergTableChecker();
      relNode.accept(checker);
      return checker;
    }

    @Override
    public RelNode visit(TableScan scan) {
      if (scan instanceof ScanRelBase) {
        DatasetConfig tableConfig = ((ScanRelBase) scan).getTableMetadata().getDatasetConfig();
        if (DatasetHelper.isIcebergDataset(tableConfig)) {
          hasNativeIcebergTable = true;
        } else if (DatasetHelper.isInternalIcebergTable((tableConfig))
            && (FileType.PARQUET.equals(
                tableConfig.getPhysicalDataset().getIcebergMetadata().getFileType()))) {
          hasUnlimitedSplitsParquet = true;
        } else {
          hasNonIcebergTable = true;
        }
      }
      return super.visit(scan);
    }

    public boolean allTablesAreNativeIceberg() {
      return hasNativeIcebergTable && !hasUnlimitedSplitsParquet && !hasNonIcebergTable;
    }

    public boolean allTablesAreIceberg() {
      return (hasNativeIcebergTable || hasUnlimitedSplitsParquet) && !hasNonIcebergTable;
    }
  }

  public static TableMetadataVerifyDataModifiedResult areNewSnapshotsDataModifying(
      EntityExplorer catalog, CatalogEntityKey key, long oldSnapshotId, long currentSnapshotId) {
    final Optional<TableMetadataVerifyResult> tableMetadataVerifyResult =
        catalog.verifyTableMetadata(
            key,
            new TableMetadataVerifyDataModifiedRequest(
                String.valueOf(oldSnapshotId), String.valueOf(currentSnapshotId)));

    checkState(tableMetadataVerifyResult != null);

    final TableMetadataVerifyDataModifiedResult verifyDataModifiedResult =
        tableMetadataVerifyResult
            .filter(TableMetadataVerifyDataModifiedResult.class::isInstance)
            .map(TableMetadataVerifyDataModifiedResult.class::cast)
            .orElse(null);

    return verifyDataModifiedResult;
  }
}
