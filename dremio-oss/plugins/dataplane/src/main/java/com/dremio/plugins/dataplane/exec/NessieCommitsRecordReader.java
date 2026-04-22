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
package com.dremio.plugins.dataplane.exec;

import static com.dremio.common.FSConstants.MAXIMUM_CONNECTIONS;
import static com.dremio.exec.store.IcebergExpiryMetric.NUM_ACCESS_DENIED;
import static com.dremio.exec.store.IcebergExpiryMetric.NUM_NOT_FOUND;
import static com.dremio.exec.store.IcebergExpiryMetric.NUM_PARTIAL_FAILURES;
import static com.dremio.exec.store.iceberg.logging.VacuumLogProto.ErrorType.CONTAINER_NOT_FOUND_EXCEPTION;
import static com.dremio.exec.store.iceberg.logging.VacuumLogProto.ErrorType.NOT_FOUND_EXCEPTION;
import static com.dremio.exec.store.iceberg.logging.VacuumLogProto.ErrorType.PERMISSION_EXCEPTION;
import static com.dremio.exec.store.iceberg.logging.VacuumLoggingUtil.createCommitScanLog;
import static com.dremio.exec.store.iceberg.logging.VacuumLoggingUtil.getVacuumLogger;

import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.logging.StructuredLogger;
import com.dremio.common.util.S3ConnectionConstants;
import com.dremio.common.utils.protos.QueryIdHelper;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.store.IcebergExpiryMetric;
import com.dremio.exec.store.SystemSchemas;
import com.dremio.exec.store.iceberg.NessieCommitsSubScan;
import com.dremio.exec.store.iceberg.SnapshotEntry;
import com.dremio.exec.store.iceberg.SupportsFsCreation;
import com.dremio.exec.store.iceberg.SupportsIcebergMutablePlugin;
import com.dremio.io.file.FileSystem;
import com.dremio.plugins.dataplane.store.DataplanePlugin;
import com.dremio.plugins.util.ContainerNotFoundException;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.fragment.FragmentExecutionContext;
import com.dremio.sabot.op.scan.OutputMutator;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.exceptions.NotFoundException;
import org.apache.iceberg.io.FileIO;
import org.projectnessie.gc.contents.ContentReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Scans all live Nessie contents, and outputs the metadata locations for each one of them. */
public class NessieCommitsRecordReader extends AbstractNessieCommitRecordsReader {
  private static final Logger LOGGER = LoggerFactory.getLogger(NessieCommitsRecordReader.class);
  private static final StructuredLogger vacuumLogger = getVacuumLogger();

  private volatile VarCharVector datasetOutVector;
  private volatile VarCharVector metadataFilePathOutVector;
  private volatile BigIntVector snapshotIdOutVector;
  private volatile VarCharVector manifestListPathOutVector;
  private FileIO io = null;
  private final ExecutorService opExecService;
  private final String queryId;
  private final Semaphore slots;

  public NessieCommitsRecordReader(
      FragmentExecutionContext fec,
      OperatorContext context,
      NessieCommitsSubScan config,
      SupportsIcebergMutablePlugin icebergMutablePlugin) {
    super(fec, context, config);
    opExecService = context.getExecutor();
    this.queryId = QueryIdHelper.getQueryId(context.getFragmentHandle().getQueryId());

    DataplanePlugin plugin = (DataplanePlugin) icebergMutablePlugin;
    // Limit the parallel expiry threads based on filesystem's limits. Take minimum of all supported
    // filesystem implementations.
    // Since S3 is the only supported FS, using that value.
    int maxParallelism =
        plugin
            .getProperty(MAXIMUM_CONNECTIONS)
            .map(Integer::parseInt)
            .orElse(S3ConnectionConstants.DEFAULT_MAX_THREADS / 2);
    this.slots = new Semaphore(maxParallelism);
  }

  @Override
  public void setup(OutputMutator output) throws ExecutionSetupException {
    datasetOutVector = (VarCharVector) output.getVector(SystemSchemas.DATASET_FIELD);
    metadataFilePathOutVector = (VarCharVector) output.getVector(SystemSchemas.METADATA_FILE_PATH);
    snapshotIdOutVector = (BigIntVector) output.getVector(SystemSchemas.SNAPSHOT_ID);
    manifestListPathOutVector = (VarCharVector) output.getVector(SystemSchemas.MANIFEST_LIST_PATH);

    super.setup(output);
  }

  @Override
  protected CompletableFuture<Optional<SnapshotEntry>> getEntries(
      AtomicInteger idx, ContentReference contentReference) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            slots.acquire();
            return tryLoadSnapshot(contentReference)
                .map(s -> new SnapshotEntry(contentReference.metadataLocation(), s));
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        },
        opExecService);
  }

  @Override
  protected void populateOutputVectors(AtomicInteger idx, SnapshotEntry snapshot, String dataset) {
    final int idxVal = idx.getAndIncrement();
    byte[] metadataPath = toSchemeAwarePath(snapshot.getMetadataJsonPath());
    byte[] manifestListPath = toSchemeAwarePath(snapshot.getManifestListPath());
    byte[] datasetByte = dataset.getBytes(StandardCharsets.UTF_8);

    datasetOutVector.setSafe(idxVal, datasetByte);
    metadataFilePathOutVector.setSafe(idxVal, metadataPath);
    snapshotIdOutVector.setSafe(idxVal, snapshot.getSnapshotId());
    manifestListPathOutVector.setSafe(idxVal, manifestListPath);
  }

  @Override
  protected void setValueCount(int valueCount) {
    datasetOutVector.setValueCount(valueCount);
    metadataFilePathOutVector.setValueCount(valueCount);
    snapshotIdOutVector.setValueCount(valueCount);
    manifestListPathOutVector.setValueCount(valueCount);
  }

  private Optional<Snapshot> tryLoadSnapshot(ContentReference contentReference) {
    if (contentReference.snapshotId() == null || contentReference.snapshotId() == -1) {
      return Optional.empty();
    }
    String tableId =
        String.format(
            "%s@%d AT %s [%s]",
            contentReference.contentKey(),
            contentReference.snapshotId(),
            contentReference.commitId(),
            contentReference.contentId());
    Stopwatch loadTime = Stopwatch.createStarted();
    try {

      final Optional<Snapshot> snapshot =
          loadSnapshot(
              contentReference.metadataLocation(),
              contentReference.snapshotId(),
              getDatasetElements(contentReference),
              tableId);
      vacuumLogger.info(
          createCommitScanLog(
              queryId,
              tableId,
              contentReference.metadataLocation(),
              contentReference.snapshotId().toString()),
          "");
      return snapshot;
    } catch (NotFoundException nfe) {
      String message = "Skipping table [%s] since table metadata is not found [metadata=%s]\n";
      LOGGER.warn(String.format(message, tableId, contentReference.metadataLocation()), nfe);
      vacuumLogger.warn(
          createCommitScanLog(
              queryId,
              tableId,
              contentReference.metadataLocation(),
              contentReference.snapshotId().toString(),
              NOT_FOUND_EXCEPTION,
              String.format(message, tableId, contentReference.metadataLocation())
                  + nfe.toString()),
          "");
      getContext().getStats().addLongStat(NUM_PARTIAL_FAILURES, 1L);
      getContext().getStats().addLongStat(NUM_NOT_FOUND, 1L);
      return Optional.empty();
    } catch (UserException e) {
      if (UserBitShared.DremioPBError.ErrorType.PERMISSION.equals(e.getErrorType())) {
        String message =
            "Skipping table [%s] since access to table metadata is denied [metadata=%s].\n";
        LOGGER.warn(String.format(message, tableId, contentReference.metadataLocation()), e);
        vacuumLogger.warn(
            createCommitScanLog(
                queryId,
                tableId,
                contentReference.metadataLocation(),
                contentReference.snapshotId().toString(),
                PERMISSION_EXCEPTION,
                String.format(message, tableId, contentReference.metadataLocation())
                    + e.toString()),
            "");
        getContext().getStats().addLongStat(NUM_PARTIAL_FAILURES, 1L);
        getContext().getStats().addLongStat(NUM_ACCESS_DENIED, 1L);
        return Optional.empty();
      }

      throw e;
    } catch (IOException ioe) {
      throw UserException.ioExceptionError(ioe)
          .message(
              "Error while loading the snapshot %d from table %s on commit %s",
              contentReference.snapshotId(),
              contentReference.contentKey(),
              contentReference.commitId())
          .build();
    } finally {
      LOGGER.debug("{} load time {}ms", tableId, loadTime.elapsed(TimeUnit.MILLISECONDS));
      getContext()
          .getStats()
          .addLongStat(
              IcebergExpiryMetric.SNAPSHOT_LOAD_TIME, loadTime.elapsed(TimeUnit.MILLISECONDS));
      slots.release();
    }
  }

  @VisibleForTesting
  Optional<Snapshot> loadSnapshot(
      String metadataJsonPath, long snapshotId, List<String> datasetPath, String tableId)
      throws IOException {
    return readTableMetadata(
            io(metadataJsonPath, datasetPath), metadataJsonPath, tableId, getContext())
        .map(metadata -> metadata.snapshot(snapshotId));
  }

  private FileIO io(String metadataLocation, List<String> datasetPath) throws IOException {
    if (io == null) {
      FileSystem fs =
          getPlugin()
              .createFS(
                  SupportsFsCreation.builder()
                      .withAsyncOptions(true)
                      .filePath(metadataLocation)
                      .userName(getConfig().getProps().getUserName())
                      .operatorContext(getContext()));
      io =
          getPlugin()
              .createIcebergFileIO(
                  fs, getContext(), datasetPath, getConfig().getPluginId().getName(), null);
    }
    return io;
  }

  static Optional<TableMetadata> readTableMetadata(
      final FileIO io,
      final String metadataLocation,
      final String tableId,
      final OperatorContext context) {
    try {
      return Optional.of(TableMetadataParser.read(io, metadataLocation));
    } catch (UserException ue) {
      /* TableMetadataParser.read calls FileIO.newInputFile.
       * One implementation of the latter, DremioFileIO.newInputFile, catches ContainerNotFoundException
       * and wraps it in a UserException.  Per DX-93461, the current design intent for VACUUM CATALOG is to
       * make ContainerNotFoundException get logged and ignored, though this behavior is up for discussion
       * longer-term.
       *
       * The newInputFile interface method has many callsites.  Rather than change the exception semantics of
       * newInputFile (potentially involving all implementations and callsites), we catch a UserException that
       * specifically wraps ContainerNotFoundException (but nothing else).  This catch is scoped around a single
       * statement,rather than with the other catches I found at the bottom, because we currently believe this
       * is the only statement that would throw a CNFE that should be handled this way.
       */
      if (null == ue.getCause()
          || !(ue.getCause() instanceof ContainerNotFoundException)
          || !UserBitShared.DremioPBError.ErrorType.IO_EXCEPTION.equals(ue.getErrorType())) {
        throw ue; // Not the exception we're looking for, or not allowed to catch, rethrow it as-is
      }
      // Found CNFE inside UE; log and return empty
      String message = "Skipping table %s since its storage container was not found.\n";
      LOGGER.warn(String.format(message, tableId), ue);
      vacuumLogger.warn(
          createCommitScanLog(
              QueryIdHelper.getQueryId(context.getFragmentHandle().getQueryId()),
              tableId,
              metadataLocation,
              CONTAINER_NOT_FOUND_EXCEPTION,
              String.format(message, tableId) + ue.toString()),
          "");
      context.getStats().addLongStat(NUM_PARTIAL_FAILURES, 1L);
      context.getStats().addLongStat(NUM_NOT_FOUND, 1L);
      return Optional.empty();
    }
  }
}
