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
package com.dremio.exec.ops;

import com.dremio.exec.planner.common.PlannerMetrics;
import com.dremio.telemetry.api.metrics.MeterProviders;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;

public class IcebergMetrics {

  // Shared prefix used by metrics
  private static final String PREFIX = "iceberg";

  // Operation type for the captured metric
  private static final String OPERATION_TYPE = "operation_type";

  public enum OperationType {
    READ,
    CREATE;

    @Override
    public String toString() {
      return name().toLowerCase();
    }
  }

  // Tracks format version of the table queried
  private static final String FORMAT_VERSION = "format_version";
  private static final String FORMAT_VERSION_DESCRIPTION = "format version of table";

  // Counter for tracking format version
  private static final Meter.MeterProvider<Counter> FORMAT_VERSION_COUNTER =
      MeterProviders.newCounterProvider(createName(FORMAT_VERSION), FORMAT_VERSION_DESCRIPTION);

  private static String createName(String name) {
    return PlannerMetrics.createName(PREFIX, name);
  }

  /**
   * Increments the counter for a specific Iceberg format version.
   *
   * @param formatVersion The format version of the table
   * @param operationType The operation type for which it is being captured
   */
  public static void countFormatVersion(int formatVersion, OperationType operationType) {
    FORMAT_VERSION_COUNTER
        .withTags(
            Tags.of(
                FORMAT_VERSION,
                String.valueOf(formatVersion),
                OPERATION_TYPE,
                operationType.toString()))
        .increment();
  }

  private IcebergMetrics() {
    // private constructor to prevent instantiation
  }
}
