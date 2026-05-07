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
package com.dremio.service.reflection.analysis;

import com.dremio.service.job.JobSummary;
import com.dremio.service.job.SearchJobsRequest;
import com.dremio.service.jobs.JobsService;
import com.dremio.service.scheduler.Schedule;
import com.dremio.service.scheduler.SchedulerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically reads completed jobs from {@link JobsService}, aggregates column-level usage per
 * dataset, and POSTs a compact summary to the autonomous-reflection AI service so it can update its
 * knowledge base.
 *
 * <p>Scheduling uses {@link SchedulerService} with {@code asClusteredSingleton} so only one
 * coordinator runs this in a multi-coordinator cluster.
 */
public final class AutonomousReflectionIngestTask implements Runnable {

  private static final Logger logger =
      LoggerFactory.getLogger(AutonomousReflectionIngestTask.class);

  private static final String LOCAL_TASK_LEADER_NAME = "autonomous-reflection-ingest";

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Path DEBUG_LOG_PATH =
      Path.of("/home/djuybu/thesis/.cursor/debug-947d4b.log");

  /**
   * Regex that extracts column names from the SELECT list of a SQL statement. Handles both bare
   * identifiers ({@code col}) and quoted identifiers ({@code "col"}). Stops at {@code FROM}.
   */
  private static final Pattern SELECT_COLS =
      Pattern.compile("(?i)SELECT\\s+(.*?)\\s+FROM\\s+", Pattern.DOTALL);

  private static final Pattern COL_IDENT =
      Pattern.compile("(?:\"([^\"]+)\"|([A-Za-z_][A-Za-z0-9_]*))");

  private static final Pattern AGG_FUNC =
      Pattern.compile(
          "(?i)(?:SUM|AVG|COUNT|MIN|MAX)\\s*\\(\\s*(?:\"([^\"]+)\"|([A-Za-z_][A-Za-z0-9_]*))\\s*\\)");

  private static final Pattern GROUP_BY =
      Pattern.compile("(?i)GROUP\\s+BY\\s+(.*?)(?:ORDER|LIMIT|HAVING|$)", Pattern.DOTALL);

  private static final Pattern WHERE_COLS =
      Pattern.compile("(?i)WHERE\\s+(.*?)(?:GROUP|ORDER|LIMIT|HAVING|$)", Pattern.DOTALL);

  private final Provider<JobsService> jobsServiceProvider;
  private final Provider<SchedulerService> schedulerServiceProvider;
  private final String ingestUrl;
  private final String ingestToken;
  private final long intervalMinutes;

  private volatile long lastWindowEndMs;

  public AutonomousReflectionIngestTask(
      Provider<JobsService> jobsServiceProvider,
      Provider<SchedulerService> schedulerServiceProvider,
      String ingestUrl,
      String ingestToken,
      long intervalMinutes) {
    this.jobsServiceProvider = jobsServiceProvider;
    this.schedulerServiceProvider = schedulerServiceProvider;
    this.ingestUrl = ingestUrl;
    this.ingestToken = ingestToken;
    this.intervalMinutes = intervalMinutes;
    this.lastWindowEndMs = System.currentTimeMillis();
  }

  /** Register the first scheduled execution. */
  public void start() {
    scheduleNext();
    logger.info(
        "Autonomous reflection ingest task scheduled (interval={}min, url={})",
        intervalMinutes,
        ingestUrl);
  }

  @Override
  public void run() {
    long windowStart = lastWindowEndMs;
    long windowEnd = System.currentTimeMillis();
    try {
      runIngestWindow(windowStart, windowEnd);
      lastWindowEndMs = windowEnd;
    } catch (Exception e) {
      logger.warn(
          "Autonomous reflection ingest failed for window [{}, {}]: {}",
          windowStart,
          windowEnd,
          e.getMessage());
    } finally {
      scheduleNext();
    }
  }

  // ---- core logic ----

  void runIngestWindow(long startMs, long endMs) throws Exception {
    SearchJobsRequest request = SearchJobsRequest.newBuilder().setLimit(1000).build();

    Iterable<JobSummary> jobsIterable = jobsServiceProvider.get().searchJobs(request);
    // #region agent log
    debugLog(
        "pre-fix",
        "H1",
        "AutonomousReflectionIngestTask.java:138",
        "searchJobs return type check",
        Map.of(
            "isIterable",
            jobsIterable instanceof Iterable,
            "isIterator",
            jobsIterable instanceof Iterator,
            "returnedClass",
            jobsIterable.getClass().getName()));
    // #endregion
    Iterator<JobSummary> jobs = jobsIterable.iterator();
    // #region agent log
    debugLog(
        "pre-fix",
        "H2",
        "AutonomousReflectionIngestTask.java:151",
        "iterator creation from iterable",
        Map.of("iteratorClass", jobs.getClass().getName()));
    // #endregion

    // datasetKey -> columnName -> counts
    Map<String, Map<String, int[]>> usageMap = new HashMap<>();
    int scannedJobs = 0;

    while (jobs.hasNext()) {
      JobSummary job = jobs.next();
      scannedJobs++;
      if (job.getStartTime() < startMs || job.getStartTime() >= endMs) {
        continue;
      }
      if (job.getDatasetPathList().isEmpty()) {
        continue;
      }
      String sql = job.getSql();
      if (sql == null || sql.isEmpty()) {
        sql = job.getQueryText();
      }
      if (sql == null || sql.isEmpty()) {
        continue;
      }

      String datasetKey = String.join(".", job.getDatasetPathList());
      Map<String, int[]> colMap = usageMap.computeIfAbsent(datasetKey, k -> new HashMap<>());
      aggregateColumnsFromSql(sql, colMap);
    }
    // #region agent log
    debugLog(
        "pre-fix",
        "H3",
        "AutonomousReflectionIngestTask.java:185",
        "ingest loop summary",
        Map.of("scannedJobs", scannedJobs, "datasetCount", usageMap.size()));
    // #endregion

    if (usageMap.isEmpty()) {
      logger.debug("No new jobs in window [{}, {}], skipping ingest POST", startMs, endMs);
      return;
    }

    Map<String, Object> payload = buildPayload(usageMap, startMs, endMs);
    int code = postIngestPayload(payload);
    logger.info("Ingest POST returned {} for window [{}, {}]", code, startMs, endMs);
  }

  /**
   * Simple SQL column extractor: adds projection/filter/groupBy/aggregate counts per column. Not a
   * full parser — covers the common SELECT ... FROM ... WHERE ... GROUP BY ... patterns.
   */
  static void aggregateColumnsFromSql(String sql, Map<String, int[]> colMap) {
    // indices: 0=projection, 1=filter, 2=groupBy, 3=aggregate
    Matcher selMatch = SELECT_COLS.matcher(sql);
    if (selMatch.find()) {
      String selectList = selMatch.group(1);
      extractIdents(selectList, colMap, 0);
    }

    Matcher aggMatch = AGG_FUNC.matcher(sql);
    while (aggMatch.find()) {
      String col = aggMatch.group(1) != null ? aggMatch.group(1) : aggMatch.group(2);
      if (col != null && !"*".equals(col)) {
        colMap.computeIfAbsent(col, k -> new int[4])[3] += 1;
      }
    }

    Matcher whereMatch = WHERE_COLS.matcher(sql);
    if (whereMatch.find()) {
      extractIdents(whereMatch.group(1), colMap, 1);
    }

    Matcher groupMatch = GROUP_BY.matcher(sql);
    if (groupMatch.find()) {
      extractIdents(groupMatch.group(1), colMap, 2);
    }
  }

  private static void extractIdents(String fragment, Map<String, int[]> colMap, int idx) {
    Matcher m = COL_IDENT.matcher(fragment);
    while (m.find()) {
      String col = m.group(1) != null ? m.group(1) : m.group(2);
      if (col != null && !isKeyword(col)) {
        colMap.computeIfAbsent(col, k -> new int[4])[idx] += 1;
      }
    }
  }

  private static boolean isKeyword(String w) {
    String upper = w.toUpperCase();
    return "AND".equals(upper)
        || "OR".equals(upper)
        || "NOT".equals(upper)
        || "IN".equals(upper)
        || "IS".equals(upper)
        || "NULL".equals(upper)
        || "BETWEEN".equals(upper)
        || "LIKE".equals(upper)
        || "AS".equals(upper)
        || "ON".equals(upper)
        || "TRUE".equals(upper)
        || "FALSE".equals(upper)
        || "CASE".equals(upper)
        || "WHEN".equals(upper)
        || "THEN".equals(upper)
        || "ELSE".equals(upper)
        || "END".equals(upper)
        || "DISTINCT".equals(upper)
        || "ALL".equals(upper)
        || "SELECT".equals(upper)
        || "FROM".equals(upper);
  }

  private Map<String, Object> buildPayload(
      Map<String, Map<String, int[]>> usageMap, long startMs, long endMs) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("batchId", UUID.randomUUID().toString());
    payload.put("windowStartEpochMs", startMs);
    payload.put("windowEndEpochMs", endMs);

    List<Map<String, Object>> datasets = new ArrayList<>();
    for (Map.Entry<String, Map<String, int[]>> entry : usageMap.entrySet()) {
      Map<String, Object> ds = new HashMap<>();
      String[] parts = entry.getKey().split("\\.");
      List<String> path = new ArrayList<>();
      for (String p : parts) {
        path.add(p);
      }
      ds.put("datasetPath", path);

      List<Map<String, Object>> columnUsage = new ArrayList<>();
      for (Map.Entry<String, int[]> colEntry : entry.getValue().entrySet()) {
        int[] counts = colEntry.getValue();
        Map<String, Object> cu = new HashMap<>();
        cu.put("column", colEntry.getKey());
        cu.put("projectionCount", counts[0]);
        cu.put("filterCount", counts[1]);
        cu.put("groupByCount", counts[2]);
        cu.put("aggregateCount", counts[3]);
        columnUsage.add(cu);
      }
      ds.put("columnUsage", columnUsage);
      datasets.add(ds);
    }

    payload.put("datasets", datasets);
    return payload;
  }

  int postIngestPayload(Map<String, Object> payload) throws Exception {
    String json = MAPPER.writeValueAsString(payload);
    logger.debug("Posting ingest payload ({} bytes) to {}", json.length(), ingestUrl);

    URL url = new URL(ingestUrl);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("POST");
    conn.setRequestProperty("Content-Type", "application/json; utf-8");
    conn.setRequestProperty("Accept", "application/json");
    if (ingestToken != null && !ingestToken.isEmpty()) {
      conn.setRequestProperty("X-AR-Ingest-Token", ingestToken);
    }
    conn.setConnectTimeout(5000);
    conn.setReadTimeout(10000);
    conn.setDoOutput(true);

    try (OutputStream os = conn.getOutputStream()) {
      byte[] input = json.getBytes(StandardCharsets.UTF_8);
      os.write(input, 0, input.length);
    }

    int code = conn.getResponseCode();
    if (code != 200) {
      try (BufferedReader br =
          new BufferedReader(
              new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
          sb.append(line);
        }
        logger.warn("Ingest POST returned {}: {}", code, sb);
      }
    }
    return code;
  }

  private void scheduleNext() {
    schedulerServiceProvider
        .get()
        .schedule(
            Schedule.Builder.singleShotChain()
                .startingAt(
                    Instant.ofEpochMilli(System.currentTimeMillis() + intervalMinutes * 60_000L))
                .asClusteredSingleton(LOCAL_TASK_LEADER_NAME)
                .build(),
            this);
  }

  private static void debugLog(
      String runId,
      String hypothesisId,
      String location,
      String message,
      Map<String, Object> data) {
    try {
      Map<String, Object> payload = new HashMap<>();
      payload.put("sessionId", "947d4b");
      payload.put("runId", runId);
      payload.put("hypothesisId", hypothesisId);
      payload.put("location", location);
      payload.put("message", message);
      payload.put("data", data);
      payload.put("timestamp", System.currentTimeMillis());
      Files.writeString(
          DEBUG_LOG_PATH,
          MAPPER.writeValueAsString(payload) + System.lineSeparator(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (Exception ignored) {
      // debug logging must never affect runtime behavior
    }
  }
}
