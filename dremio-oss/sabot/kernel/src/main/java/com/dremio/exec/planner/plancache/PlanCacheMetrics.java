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
package com.dremio.exec.planner.plancache;

import static com.dremio.exec.planner.common.PlannerMetrics.PREFIX;

import com.dremio.exec.catalog.DremioTable;
import com.dremio.exec.planner.events.PlannerEvent;
import com.dremio.exec.planner.events.PlannerEventBus;
import com.dremio.exec.planner.observer.AttemptObserver;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.google.common.base.Joiner;
import com.google.common.base.Stopwatch;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Metrics;
import io.opentelemetry.api.trace.Span;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/** A common place for delegate metrics and spans to their underlying stores. */
public final class PlanCacheMetrics {

  public enum PlanCachePhases {
    PLAN_CACHE_CHECK("Plan Cache Check"),
    PLAN_CACHE_PUT("Plan Cache Put");
    private final String displayName;

    PlanCachePhases(String displayName) {
      this.displayName = displayName;
    }

    public String getDisplayName() {
      return displayName;
    }
  }

  public enum QueryOutcome {
    CACHE_DISABLED,
    CACHE_MISS,
    CACHE_HIT,

    INVALID_UPDATE_TIME_MISSING,
    INVALID_SOURCE_TABLE_UPDATED,
    INVALID_MATERIALIZATION_HASH,

    // PUT OUTCOMES
    PUT_SUCCESSFUL,
    PUT_FAILED,
    NOT_CACHEABLE,
    NOT_PUT_EXTERNAL_QUERY,
    NOT_PUT_DYNAMIC_FUNCTION,
    NOT_PUT_MAT_CACHE_NOT_INIT,
    NOT_PUT_BLACKLISTED,
    NOT_PUT_VERSIONED_TABLE
  }

  public static final String PLAN_CACHE_SYNC = "plan_cache_sync";
  public static final String PLAN_CACHE_ENTRIES = "plan_cache_entries";

  // Counter for any planned query and whether it hits or misses plan cache with miss reason
  public static final String PLAN_CACHE_QUERIES = "plan_cache_queries";

  // Counter for when we put or not put a plan cache entry
  public static final String PLAN_CACHE_PUTS = "plan_cache_puts";

  public static final String TAG_OUTCOME = "outcome";

  private static final Meter.MeterProvider<Counter> PLAN_CACHE_QUERIES_COUNTER =
      Counter.builder(Joiner.on(".").join(PREFIX, PLAN_CACHE_QUERIES))
          .description("Counter for plan cache queries with outcome")
          .withRegistry(Metrics.globalRegistry);

  private static final Meter.MeterProvider<Counter> PLAN_CACHE_PUTS_COUNTER =
      Counter.builder(Joiner.on(".").join(PREFIX, PLAN_CACHE_PUTS))
          .description("Counter for plan cache puts with outcome")
          .withRegistry(Metrics.globalRegistry);

  public static void incrementPlanCacheQueries(QueryOutcome outcome) {
    PLAN_CACHE_QUERIES_COUNTER.withTag(TAG_OUTCOME, outcome.name()).increment();
  }

  public static void incrementPlanCachePuts(QueryOutcome outcome) {
    PLAN_CACHE_PUTS_COUNTER.withTag(TAG_OUTCOME, outcome.name()).increment();
  }

  public static String getUTCString(long timestamp) {
    return Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toString();
  }

  public static void reportStoreQueryResultPolicy(
      PlannerSettings.StoreQueryResultsPolicy storeQueryResultsPolicy) {
    Span.current()
        .setAttribute("dremio.planner.store_query_results_policy", storeQueryResultsPolicy.name());
  }

  public static PlanCacheEvent createCacheMissEvent() {
    return new PlanCacheEvent(QueryOutcome.CACHE_MISS, "Cache entry not found.");
  }

  public static PlanCacheEvent createInvalidMaterializationHash(
      String reflectionEntryHash, String currentReflectionHash) {
    return new PlanCacheEvent(
        QueryOutcome.INVALID_MATERIALIZATION_HASH,
        String.format(
            ""
                + "Cache entry found with stale materializations.\n"
                + "Old Materialization Hash: %s\n"
                + "New Materialization Hash: %s",
            reflectionEntryHash, currentReflectionHash));
  }

  public static PlanCacheEvent createUpdateTimeMissingEvent(DremioTable dremioTable) {
    return new PlanCacheEvent(
        QueryOutcome.INVALID_UPDATE_TIME_MISSING,
        String.format("Unable to get update time on %s", dremioTable.getPath()));
  }

  public static PlanCacheEvent createInvalidSourceTableUpdate(
      PlanCacheEntry planCacheEntry, DremioTable dremioTable, Long datasetUpdateTime) {
    return new PlanCacheEvent(
        QueryOutcome.INVALID_SOURCE_TABLE_UPDATED,
        String.format(
            ""
                + "Table or source %s updated after cache creation.\n"
                + "Plan cache created %s.\n"
                + "Table updated %s.",
            dremioTable.getPath(),
            PlanCacheMetrics.getUTCString(planCacheEntry.getCreationTime()),
            PlanCacheMetrics.getUTCString(datasetUpdateTime)));
  }

  public static PlanCacheEvent createUncacheablePlan() {
    return new PlanCacheEvent(QueryOutcome.NOT_CACHEABLE, "Plan is not cacheable.");
  }

  public static PlanCacheEvent cachePlanCacheDisabled() {
    return new PlanCacheEvent(QueryOutcome.CACHE_DISABLED, "Plan cache disabled.");
  }

  public static PlannerEvent createFailedPut() {
    return new PlanCacheEvent(QueryOutcome.PUT_FAILED, "Failed to put plan in cache.");
  }

  public static PlannerEvent createSuccessfulPut() {
    return new PlanCacheEvent(QueryOutcome.PUT_SUCCESSFUL, "Successfully put plan in cache.");
  }

  public static PlanCacheEvent createCacheUsed(PlanCacheEntry planCacheEntry) {
    return new PlanCacheEvent(
        QueryOutcome.CACHE_HIT,
        String.format(
            "Cache entry found.  Entry created %s",
            getUTCString(planCacheEntry.getCreationTime())));
  }

  public static void observeCacheUsed(PlannerEventBus bus, PlanCacheEntry planCacheEntry) {
    bus.dispatch(PlanCacheMetrics.createCacheUsed(planCacheEntry));
    PlanCacheMetrics.incrementPlanCacheQueries(QueryOutcome.CACHE_HIT);
  }

  public static void reportPhase(
      AttemptObserver observer,
      PlanCachePhases phase,
      PlanCacheKey planCacheKey,
      List<PlanCacheEvent> events,
      Stopwatch stopwatch) {
    StringBuilder sb =
        new StringBuilder()
            .append("Cache Key: ")
            .append(planCacheKey.getStringHash())
            .append("\nMaterialization Hash: ")
            .append(planCacheKey.getMaterializationHashString());
    events.stream().map(PlanCacheEvent::getProfileReason).forEach(r -> sb.append("\n").append(r));
    observer.planStepLogging(phase.displayName, sb.toString(), stopwatch.elapsed().toMillis());
  }

  /**
   * CacheNotUsedEvent is dispatched when a query does not use plan cache. Reasons include the cache
   * being disabled, no cache entry found, cache entry invalidated due to metadata changes or
   * materialization hash changed.
   */
  public static class PlanCacheEvent implements PlannerEvent {

    private final QueryOutcome metricTag;
    private final String profileReason;

    public PlanCacheEvent(QueryOutcome metricTag, String profileReason) {
      this.metricTag = metricTag;
      this.profileReason = profileReason;
    }

    public QueryOutcome getMetricTag() {
      return metricTag;
    }

    public String getProfileReason() {
      return profileReason;
    }
  }
}
