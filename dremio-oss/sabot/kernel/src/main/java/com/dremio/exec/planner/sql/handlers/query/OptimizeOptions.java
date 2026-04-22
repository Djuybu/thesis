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

package com.dremio.exec.planner.sql.handlers.query;

import static com.dremio.optimization.api.OptimizeConstants.OPTIMIZE_MAX_FILE_SIZE_MB_PROPERTY;
import static com.dremio.optimization.api.OptimizeConstants.OPTIMIZE_MIN_FILE_SIZE_MB_PROPERTY;
import static com.dremio.optimization.api.util.OptimizeOptionUtils.validateOptionsInBytes;
import static org.apache.iceberg.TableProperties.WRITE_TARGET_FILE_SIZE_BYTES;

import com.dremio.exec.ExecConstants;
import com.dremio.exec.planner.sql.parser.SqlOptimize;
import com.dremio.optimization.api.OptimizeConstants;
import com.dremio.options.OptionManager;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Map;
import java.util.Optional;
import org.apache.iceberg.util.PropertyUtil;

/**
 * A rewrite strategy for data files which determines which files to rewrite based on their size. If
 * files are either smaller than the {@link #OptimizeOptions#minFileSizeBytes} threshold or larger
 * than the {@link #OptimizeOptions#maxFileSizeBytes} threshold, they are considered targets for
 * being rewritten. Groups will be considered for rewriting if they contain more files than {@link
 * #OptimizeOptions#minInputFiles} or would produce at least one file of {@link
 * OptimizeOptions#targetFileSizeBytes}.
 */
public final class OptimizeOptions {
  public static OptimizeOptions DEFAULT = new Builder().build();
  private final Long targetFileSizeBytes;
  private final Long maxFileSizeBytes;
  private final Long minFileSizeBytes;
  private final Long minInputFiles;

  /**
   * If a dataset is non-partitioned, it should use a single parquet writer. It will avoid different
   * blocks of the same parquet to be written by different fragments. It should use a single parquet
   * writer to ensure the writer rolls to the next file only after completing the current one. This
   * avoids creation of small files when execution planning over parallelisms.
   */
  private final boolean isSingleDataWriter;

  private final boolean optimizeDataFiles;
  private final boolean optimizeManifestFiles;

  private OptimizeOptions(
      Long targetFileSizeBytes,
      Long maxFileSizeBytes,
      Long minFileSizeBytes,
      Long minInputFiles,
      boolean isSingleDataWriter,
      boolean optimizeDataFiles,
      boolean optimizeManifestFiles) {
    this.targetFileSizeBytes = targetFileSizeBytes;
    this.maxFileSizeBytes = maxFileSizeBytes;
    this.minFileSizeBytes = minFileSizeBytes;
    this.minInputFiles = minInputFiles;
    this.isSingleDataWriter = isSingleDataWriter;
    this.optimizeDataFiles = optimizeDataFiles;
    this.optimizeManifestFiles = optimizeManifestFiles;
  }

  public static OptimizeOptions createInstance(
      Map<String, String> tableProperties,
      OptionManager optionManager,
      SqlOptimize call,
      boolean isSingleDataWriter) {
    Builder instanceBuilder = new Builder();

    instanceBuilder.setSingleWriter(isSingleDataWriter);
    instanceBuilder.setMaxFileSizeRatio(
        optionManager.getOption(ExecConstants.OPTIMIZE_MAXIMUM_FILE_SIZE_DEFAULT_RATIO));
    instanceBuilder.setMinFileSizeRatio(
        optionManager.getOption(ExecConstants.OPTIMIZE_MINIMUM_FILE_SIZE_DEFAULT_RATIO));

    // All the OPTIMIZE options will have precedence in order: SQL options, table properties,
    // support keys, default options
    instanceBuilder.setTargetFileSizeBytes(
        call.getTargetFileSize()
            .map(OptimizeOptions::mbToBytes)
            .orElse(
                getLongTableProperty(tableProperties, WRITE_TARGET_FILE_SIZE_BYTES)
                    .orElse(
                        mbToBytes(
                            optionManager.getOption(ExecConstants.OPTIMIZE_TARGET_FILE_SIZE_MB)))));

    instanceBuilder.setMaxFileSizeMB(
        call.getMaxFileSize()
            .or(() -> getLongTableProperty(tableProperties, OPTIMIZE_MAX_FILE_SIZE_MB_PROPERTY)));

    instanceBuilder.setMinFileSizeMB(
        call.getMinFileSize()
            .or(() -> getLongTableProperty(tableProperties, OPTIMIZE_MIN_FILE_SIZE_MB_PROPERTY)));

    instanceBuilder.setMinInputFiles(
        call.getMinInputFiles()
            .or(
                () ->
                    getLongTableProperty(
                            tableProperties, OptimizeConstants.OPTIMIZE_MINIMAL_INPUT_FILES)
                        .or(
                            () ->
                                Optional.of(
                                    optionManager.getOption(
                                        ExecConstants.OPTIMIZE_MINIMUM_INPUT_FILES)))));

    instanceBuilder.setOptimizeDataFiles(call.getRewriteDataFiles().booleanValue());
    instanceBuilder.setOptimizeManifestFiles(call.getRewriteManifests().booleanValue());

    return instanceBuilder.build();
  }

  public static OptimizeOptions createDefaultOptimizeOptions(
      OptionManager optionManager, boolean isSingleDataWriter) {
    Builder instanceBuilder = new Builder();

    instanceBuilder.setSingleWriter(isSingleDataWriter);
    instanceBuilder.setTargetFileSizeBytes(
        mbToBytes(optionManager.getOption(ExecConstants.OPTIMIZE_TARGET_FILE_SIZE_MB)));
    instanceBuilder.setMaxFileSizeRatio(
        optionManager.getOption(ExecConstants.OPTIMIZE_MAXIMUM_FILE_SIZE_DEFAULT_RATIO));
    instanceBuilder.setMinFileSizeRatio(
        optionManager.getOption(ExecConstants.OPTIMIZE_MINIMUM_FILE_SIZE_DEFAULT_RATIO));

    Long minInputFiles = optionManager.getOption(ExecConstants.OPTIMIZE_MINIMUM_INPUT_FILES);
    instanceBuilder.setMinInputFiles(Optional.of(minInputFiles));

    return instanceBuilder.build();
  }

  public static OptimizeOptions createInstance(SqlOptimize call) {
    Builder instanceBuilder = new Builder();

    call.getTargetFileSize()
        .ifPresent(size -> instanceBuilder.setTargetFileSizeBytes(mbToBytes(size)));
    instanceBuilder.setMaxFileSizeMB(call.getMaxFileSize());
    instanceBuilder.setMinFileSizeMB(call.getMinFileSize());
    instanceBuilder.setMinInputFiles(call.getMinInputFiles());

    instanceBuilder.setOptimizeDataFiles(call.getRewriteDataFiles().booleanValue());
    instanceBuilder.setOptimizeManifestFiles(call.getRewriteManifests().booleanValue());

    return instanceBuilder.build();
  }

  public Long getTargetFileSizeBytes() {
    return targetFileSizeBytes;
  }

  public Long getMaxFileSizeBytes() {
    return maxFileSizeBytes;
  }

  public Long getMinFileSizeBytes() {
    return minFileSizeBytes;
  }

  public Long getMinInputFiles() {
    return minInputFiles;
  }

  public boolean isSingleDataWriter() {
    return isSingleDataWriter;
  }

  public boolean isOptimizeDataFiles() {
    return optimizeDataFiles;
  }

  public boolean isOptimizeManifestFiles() {
    return optimizeManifestFiles;
  }

  @JsonIgnore
  public boolean isOptimizeManifestsOnly() {
    return isOptimizeManifestFiles() && !isOptimizeDataFiles();
  }

  private static Optional<Long> getLongTableProperty(
      Map<String, String> tableProperties, String propertyName) {
    if (propertyName == null) {
      return Optional.empty();
    }

    try {
      return Optional.ofNullable(
          PropertyUtil.propertyAsNullableLong(tableProperties, propertyName));
    } catch (NumberFormatException exception) {
      return Optional.empty();
    }
  }

  private static long mbToBytes(long sizeMB) {
    return sizeMB * 1024 * 1024;
  }

  private static class Builder {
    // To consistent with upstream WRITE_TARGET_FILE_SIZE_BYTES and avoid multiple conversions. It's
    // better to use targetFileSizeBytes
    private Long targetFileSizeBytes =
        mbToBytes(ExecConstants.OPTIMIZE_TARGET_FILE_SIZE_MB.getDefault().getNumVal());
    private Double maxFileSizeRatio =
        ExecConstants.OPTIMIZE_MAXIMUM_FILE_SIZE_DEFAULT_RATIO.getDefault().getFloatVal();
    private Double minFileSizeRatio =
        ExecConstants.OPTIMIZE_MINIMUM_FILE_SIZE_DEFAULT_RATIO.getDefault().getFloatVal();
    private Optional<Long> maxFileSizeMB = Optional.empty();
    private Optional<Long> minFileSizeMB = Optional.empty();
    private long minInputFiles =
        ExecConstants.OPTIMIZE_MINIMUM_INPUT_FILES.getDefault().getNumVal();
    private boolean optimizeDataFiles = true;
    private boolean optimizeManifestFiles = true;
    private boolean isSingleWriter = false;

    private Builder() {}

    private void setMaxFileSizeRatio(Double maxFileSizeRatio) {
      this.maxFileSizeRatio = maxFileSizeRatio;
    }

    private void setMinFileSizeRatio(Double minFileSizeRatio) {
      this.minFileSizeRatio = minFileSizeRatio;
    }

    private void setMinInputFiles(Optional<Long> minInputFiles) {
      minInputFiles.ifPresent(val -> this.minInputFiles = val);
    }

    private void setMaxFileSizeMB(Optional<Long> maxFileSizeMB) {
      this.maxFileSizeMB = maxFileSizeMB;
    }

    private void setMinFileSizeMB(Optional<Long> minFileSizeMB) {
      this.minFileSizeMB = minFileSizeMB;
    }

    private void setTargetFileSizeBytes(Long targetFileSizeBytes) {
      this.targetFileSizeBytes = targetFileSizeBytes;
    }

    private void setOptimizeDataFiles(boolean optimizeDataFiles) {
      this.optimizeDataFiles = optimizeDataFiles;
    }

    private void setOptimizeManifestFiles(boolean optimizeManifestFiles) {
      this.optimizeManifestFiles = optimizeManifestFiles;
    }

    public void setSingleWriter(boolean singleWriter) {
      isSingleWriter = singleWriter;
    }

    private OptimizeOptions build() {
      long maxFileSizeBytes =
          this.maxFileSizeMB
              .map(OptimizeOptions::mbToBytes)
              .orElse((long) (this.targetFileSizeBytes * maxFileSizeRatio));
      long minFileSizeBytes =
          this.minFileSizeMB
              .map(OptimizeOptions::mbToBytes)
              .orElse((long) (this.targetFileSizeBytes * minFileSizeRatio));

      validateOptionsInBytes(
          targetFileSizeBytes, minFileSizeBytes, maxFileSizeBytes, minInputFiles);

      return new OptimizeOptions(
          targetFileSizeBytes,
          maxFileSizeBytes,
          minFileSizeBytes,
          minInputFiles,
          isSingleWriter,
          optimizeDataFiles,
          optimizeManifestFiles);
    }
  }
}
