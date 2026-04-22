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
package com.dremio.exec.catalog;

import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.concurrent.ThreadSafe;
import org.apache.commons.lang3.tuple.Pair;
import org.immutables.value.Value;

@ThreadSafe
public class CatalogAccessStats {

  private final ConcurrentMap<Pair<CatalogIdentity, CatalogEntityKey>, Stats> stats;
  private final ConcurrentMap<Pair<CatalogIdentity, CatalogEntityKey>, Boolean> requestedDatasets;

  public CatalogAccessStats() {
    this.stats = new ConcurrentHashMap<>();
    this.requestedDatasets = new ConcurrentHashMap<>();
  }

  public void addRequestedDataset(CatalogIdentity identity, CatalogEntityKey key) {
    requestedDatasets.putIfAbsent(Pair.of(identity, key), Boolean.TRUE);
  }

  public void addLoadedDataset(
      CatalogIdentity identity,
      CatalogEntityKey key,
      long accessTimeMillis,
      int resolutionCount,
      DatasetType datasetType) {
    stats.compute(
        Pair.of(identity, key),
        (k, v) ->
            v == null
                ? Stats.builder()
                    .setAccessTimeMillis(accessTimeMillis)
                    .setResolutionCount(resolutionCount)
                    .setDatasetType(datasetType)
                    .build()
                : Stats.builder()
                    .setAccessTimeMillis(v.accessTimeMillis() + accessTimeMillis)
                    .setResolutionCount(v.resolutionCount() + resolutionCount)
                    .setDatasetType(v.datasetType())
                    .build());
  }

  public CatalogAccessStats merge(CatalogAccessStats other) {
    CatalogAccessStats merged = new CatalogAccessStats();
    requestedDatasets.forEach((k, v) -> merged.addRequestedDataset(k.getLeft(), k.getRight()));
    other.requestedDatasets.forEach(
        (k, v) -> merged.addRequestedDataset(k.getLeft(), k.getRight()));
    stats.forEach(
        (k, v) ->
            merged.addLoadedDataset(
                k.getLeft(),
                k.getRight(),
                v.accessTimeMillis(),
                v.resolutionCount(),
                v.datasetType()));
    other.stats.forEach(
        (k, v) ->
            merged.addLoadedDataset(
                k.getLeft(),
                k.getRight(),
                v.accessTimeMillis(),
                v.resolutionCount(),
                v.datasetType()));
    return merged;
  }

  public boolean isEmpty() {
    return requestedDatasets.isEmpty();
  }

  public int getTotalDatasets() {
    return stats.size();
  }

  public int getTotalResolvedKeys() {
    return stats.values().stream().mapToInt(Stats::resolutionCount).sum();
  }

  public UserBitShared.PlanPhaseProfile getAverageCatalogAccessProfile() {
    double avgTime = stats.values().stream().mapToLong(Stats::accessTimeMillis).average().orElse(0);
    long totalResolutions = stats.values().stream().mapToInt(Stats::resolutionCount).sum();
    return UserBitShared.PlanPhaseProfile.newBuilder()
        .setPhaseName(
            String.format(
                "Average Catalog Access for %d Total Dataset(s): using %d resolved key(s)",
                stats.size(), totalResolutions))
        .setDurationMillis((long) avgTime)
        .build();
  }

  @Override
  public String toString() {
    StringBuilder builder = new StringBuilder();
    builder.append("\nRequested Datasets:\n");
    requestedDatasets.forEach((k, v) -> builder.append(k.getRight()).append("\n"));
    builder.append(
        String.format(
            "\nCatalog Access for %d dataset(s) using %d resolved key(s):\n",
            getTotalDatasets(), getTotalResolvedKeys()));
    stats.forEach(
        (k, v) ->
            builder.append(
                String.format(
                    "%s(%s): using %d resolved key(s) (%d ms)\n",
                    k.getRight(), v.datasetType(), v.resolutionCount(), v.accessTimeMillis())));
    return builder.toString();
  }

  @Value.Immutable
  interface Stats {

    static ImmutableStats.Builder builder() {
      return new ImmutableStats.Builder();
    }

    /** Time spent (in milliseconds) accessing a particular table or view by canonical key. */
    long accessTimeMillis();

    /**
     * Number of resolutions for a particular canonical key. For example, a query may reference a
     * table with canonical key source.table but if a context ctx is set in the query or view
     * definition then we need to be able to look up the table as both ctx.source.table and
     * source.table.
     */
    int resolutionCount();

    /** The dataset type for a particular key. */
    DatasetType datasetType();
  }
}
