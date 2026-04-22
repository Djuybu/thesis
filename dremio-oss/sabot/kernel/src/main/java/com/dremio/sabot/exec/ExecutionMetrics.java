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
package com.dremio.sabot.exec;

import com.dremio.telemetry.api.metrics.MeterProviders;
import com.dremio.telemetry.api.metrics.SimpleCounter;
import com.google.common.base.Joiner;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Meter.MeterProvider;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.MultiGauge;

public class ExecutionMetrics {
  public static String EXECUTION_METRIC_PREFIX = "exec";
  private static volatile SimpleCounter longSlicesCounter;
  private static volatile SimpleCounter executorStartedQueries;
  private static volatile SimpleCounter executorEndedQueries;

  private static volatile MeterProvider<Counter> failedDLRReservationsCounter;

  private static volatile DistributionSummary queryPeakMemoryDistribution;
  private static volatile DistributionSummary queryPeakMemoryNonSpillableOperators;
  private static volatile DistributionSummary querySumMemoryNonSpillableOperators;
  private static volatile MultiGauge fragmentStatsGauge;
  private static volatile DistributionSummary fragmentSleepTimeDistribution;

  public static void registerActiveFragmentsCurrentCount(FragmentExecutors fragmentExecutors) {
    MeterProviders.newGauge(
        Joiner.on(".").join(EXECUTION_METRIC_PREFIX, "fragments_active"),
        "Current number of active fragments",
        fragmentExecutors::size);
  }

  public static MultiGauge getFragmentStateGauge() {
    if (fragmentStatsGauge == null) {
      synchronized (ExecutionMetrics.class) {
        if (fragmentStatsGauge != null) {
          return fragmentStatsGauge;
        }
        fragmentStatsGauge =
            MultiGauge.builder(Joiner.on(".").join(EXECUTION_METRIC_PREFIX, "fragment_states"))
                .description(
                    "Number of FragmentExecutors with tag for the state of the executor. (RUNNABLE, BLOCKED, NOT_STARTED, etc.)")
                .register(Metrics.globalRegistry);
      }
    }
    return fragmentStatsGauge;
  }

  public static SimpleCounter getExecutorStartedQueries() {
    if (executorStartedQueries == null) {
      synchronized (ExecutionMetrics.class) {
        if (executorStartedQueries != null) {
          return executorStartedQueries;
        }
        executorStartedQueries =
            SimpleCounter.of(
                Joiner.on(".").join(EXECUTION_METRIC_PREFIX, "started_queries"),
                "Number queries that have started on the a given executor");
      }
    }
    return executorStartedQueries;
  }

  public static DistributionSummary getQueryPeakMemoryDistribution() {
    if (queryPeakMemoryDistribution == null) {
      synchronized (ExecutionMetrics.class) {
        if (queryPeakMemoryDistribution != null) {
          return queryPeakMemoryDistribution;
        }
        queryPeakMemoryDistribution =
            DistributionSummary.builder(
                    Joiner.on(".").join(EXECUTION_METRIC_PREFIX, "query_memory_dist"))
                .description("Distribution of peak memory usage by queries on an executor.")
                .baseUnit("bytes")
                .publishPercentiles(0.5, 0.75, 0.95)
                .maximumExpectedValue(1.0 * 1024 * 1024 * 1024 * 1024) // 1 TB
                .withRegistry(Metrics.globalRegistry)
                .withTags();
      }
    }
    return queryPeakMemoryDistribution;
  }

  public static DistributionSummary getQueryPeakMemoryNonSpillableOpeartors() {
    if (queryPeakMemoryNonSpillableOperators == null) {
      synchronized (ExecutionMetrics.class) {
        if (queryPeakMemoryNonSpillableOperators != null) {
          return queryPeakMemoryNonSpillableOperators;
        }
        queryPeakMemoryNonSpillableOperators =
            DistributionSummary.builder(
                    Joiner.on(".")
                        .join(EXECUTION_METRIC_PREFIX, "query_peak_memory_non_spillable_operators"))
                .description(
                    "Distribution of peak memory usage of non spillable operators by a query on this executor.")
                .baseUnit("bytes")
                .publishPercentiles(0.5, 0.75, 0.95)
                .maximumExpectedValue(1.0 * 1024 * 1024 * 1024 * 1024) // 1 TB
                .withRegistry(Metrics.globalRegistry)
                .withTags();
      }
    }
    return queryPeakMemoryNonSpillableOperators;
  }

  public static DistributionSummary getQuerySumMemoryNonSpillableOpeartors() {
    if (querySumMemoryNonSpillableOperators == null) {
      synchronized (ExecutionMetrics.class) {
        if (querySumMemoryNonSpillableOperators != null) {
          return querySumMemoryNonSpillableOperators;
        }
        querySumMemoryNonSpillableOperators =
            DistributionSummary.builder(
                    Joiner.on(".")
                        .join(EXECUTION_METRIC_PREFIX, "query_sum_memory_non_spillable_operators"))
                .description(
                    "Distribution of sum memory usage of non spillable operators by a query on this executor.")
                .baseUnit("bytes")
                .publishPercentiles(0.5, 0.75, 0.95)
                .maximumExpectedValue(1.0 * 1024 * 1024 * 1024 * 1024) // 1 TB
                .withRegistry(Metrics.globalRegistry)
                .withTags();
      }
    }
    return querySumMemoryNonSpillableOperators;
  }

  public static SimpleCounter getExecutorEndedQueries() {
    if (executorEndedQueries == null) {
      synchronized (ExecutionMetrics.class) {
        if (executorEndedQueries != null) {
          return executorEndedQueries;
        }
        executorEndedQueries =
            SimpleCounter.of(
                Joiner.on(".").join(EXECUTION_METRIC_PREFIX, "ended_queries"),
                "Number queries that have ended on the a given executor");
      }
    }
    return executorEndedQueries;
  }

  public static SimpleCounter getLongSlicesCounter() {
    if (longSlicesCounter == null) {
      synchronized (ExecutionMetrics.class) {
        if (longSlicesCounter != null) {
          return longSlicesCounter;
        }
        longSlicesCounter =
            SimpleCounter.of(
                Joiner.on(".").join(EXECUTION_METRIC_PREFIX, "long_slices"),
                "Number of slices that ran twice the slice time");
      }
    }
    return longSlicesCounter;
  }

  public static MeterProvider<Counter> getDLRFailedReservations() {
    if (failedDLRReservationsCounter == null) {
      synchronized (ExecutionMetrics.class) {
        if (failedDLRReservationsCounter != null) {
          return failedDLRReservationsCounter;
        }
        failedDLRReservationsCounter =
            MeterProviders.newCounterProvider(
                Joiner.on(".").join(EXECUTION_METRIC_PREFIX, "dlr_failed_reservation"),
                "Records the failed reservation along with which reservation failed as a tag.");
      }
    }
    return failedDLRReservationsCounter;
  }

  public static DistributionSummary getFragmentSleepTimeDistribution() {
    if (fragmentSleepTimeDistribution == null) {
      synchronized (ExecutionMetrics.class) {
        if (fragmentSleepTimeDistribution != null) {
          return fragmentSleepTimeDistribution;
        }
        fragmentSleepTimeDistribution =
            DistributionSummary.builder(
                    Joiner.on(".").join(EXECUTION_METRIC_PREFIX, "frag_sleep_time"))
                .description("Amount of time a runnable fragment waited for slicing thread")
                .maximumExpectedValue(3600 * 1000.0) // 1 hour in millis
                .minimumExpectedValue(1000.0) // 1 second in millis
                .withRegistry(Metrics.globalRegistry)
                .withTags();
      }
    }
    return fragmentSleepTimeDistribution;
  }
}
