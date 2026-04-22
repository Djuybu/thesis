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
package com.dremio.plugins.s3.store;

import com.dremio.telemetry.api.metrics.MeterProviders;
import com.google.common.base.Joiner;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter.MeterProvider;
import io.micrometer.core.instrument.Tags;

public final class S3PluginMetrics {
  private S3PluginMetrics() {
    // private constructor to prevent initialization
  }

  public enum OperationType {
    GET_OBJECT,
    GET_BUCKET,
    LIST_BUCKETS,
    GET_BUCKET_REGION;

    @Override
    public String toString() {
      return name().toLowerCase();
    }
  }

  // Shared prefix used by metrics
  private static final String PREFIX = "s3_plugin";

  // Tracks usage of S3FileSystem's tryFindContainerV1, getAWSBucketRegionV1, listBucketsV1
  private static final String GET_S3_V1_CLIENT_CALLS = "get_s3_v1_client_calls";
  private static final String GET_S3_V1_CLIENT_CALLS_DESCRIPTION =
      "number of calls to S3FileSystem.getS3V1Client";

  // Tracks usage of S3FileSystem's tryFindContainerV2, getAWSBucketRegionV2, listBucketsV2
  private static final String GET_S3_V2_CLIENT_CALLS = "get_s3_v2_client_calls";
  private static final String GET_S3_V2_CLIENT_CALLS_DESCRIPTION =
      "number of calls to S3FileSystem.getFsS3Client";

  // Tracks unexpected exceptions (that are not AccessDenied or NoSuchBucket) in
  // S3FileSystem's tryFindContainerV1, getAWSBucketRegionV1, listBucketsV1
  private static final String S3_V1_CLIENT_THREW = "s3_v1_client_threw";
  private static final String S3_V1_CLIENT_THREW_DESCRIPTION =
      "number of times we throw from a try block that calls S3FileSystem.getS3V1Client";

  // Tracks unexpected exceptions (that are not AccessDenied or NoSuchBucket) in
  // S3FileSystem's tryFindContainerV2, getAWSBucketRegionV2, listBucketsV2
  private static final String S3_V2_CLIENT_THREW = "s3_v2_client_threw";
  private static final String S3_V2_CLIENT_THREW_DESCRIPTION =
      "number of times we throw from a try block that calls S3FileSystem.getFsS3Client";

  private static final String S3_CLIENT_THROTTLING_ERROR_TOTAL = "s3_client_throttling_error_total";
  private static final String S3_CLIENT_THROTTLING_ERROR_TOTAL_DESCRIPTION =
      "number of times we got a throttling error (503) from S3FileSystem for each operation";

  private static String createName(String name) {
    return Joiner.on(".").join(PREFIX, name);
  }

  private static final MeterProvider<Counter> GET_S3_V1_CLIENT_CALLS_COUNTER =
      MeterProviders.newCounterProvider(
          createName(GET_S3_V1_CLIENT_CALLS), GET_S3_V1_CLIENT_CALLS_DESCRIPTION);

  private static final MeterProvider<Counter> GET_S3_V2_CLIENT_CALLS_COUNTER =
      MeterProviders.newCounterProvider(
          createName(GET_S3_V2_CLIENT_CALLS), GET_S3_V2_CLIENT_CALLS_DESCRIPTION);

  private static final MeterProvider<Counter> S3_V1_CLIENT_THREW_COUNTER =
      MeterProviders.newCounterProvider(
          createName(S3_V1_CLIENT_THREW), S3_V1_CLIENT_THREW_DESCRIPTION);

  private static final MeterProvider<Counter> S3_V2_CLIENT_THREW_COUNTER =
      MeterProviders.newCounterProvider(
          createName(S3_V2_CLIENT_THREW), S3_V2_CLIENT_THREW_DESCRIPTION);

  private static final MeterProvider<Counter> S3_CLIENT_THROTTLING_ERROR_COUNTER =
      MeterProviders.newCounterProvider(
          createName(S3_CLIENT_THROTTLING_ERROR_TOTAL),
          S3_CLIENT_THROTTLING_ERROR_TOTAL_DESCRIPTION);

  public static void incrementGetS3V1ClientCallsCounter() {
    GET_S3_V1_CLIENT_CALLS_COUNTER.withTags().increment();
  }

  public static void incrementGetS3V2ClientCallsCounter() {
    GET_S3_V2_CLIENT_CALLS_COUNTER.withTags().increment();
  }

  public static void incrementS3V1ClientThrewCounter() {
    S3_V1_CLIENT_THREW_COUNTER.withTags().increment();
  }

  public static void incrementS3V2ClientThrewCounter() {
    S3_V2_CLIENT_THREW_COUNTER.withTags().increment();
  }

  public static void incrementS3ClientThrottlingErrorCounter(OperationType operation) {
    S3_CLIENT_THROTTLING_ERROR_COUNTER
        .withTags(Tags.of("operation", operation.toString()))
        .increment();
  }
}
