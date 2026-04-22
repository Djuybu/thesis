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

import static org.apache.iceberg.TableProperties.MAX_SNAPSHOT_AGE_MS;
import static org.apache.iceberg.TableProperties.MAX_SNAPSHOT_AGE_MS_DEFAULT;
import static org.apache.iceberg.TableProperties.MIN_SNAPSHOTS_TO_KEEP;
import static org.apache.iceberg.TableProperties.MIN_SNAPSHOTS_TO_KEEP_DEFAULT;

import com.dremio.exec.planner.sql.parser.SqlVacuum;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import java.util.Map;
import java.util.Optional;
import org.apache.iceberg.util.PropertyUtil;

public final class VacuumOptions {
  private final Long olderThanInMillis;
  private final Integer retainLast;
  private final boolean expireSnapshots;
  private final boolean removeOrphans;
  private final String location;
  private final Long gracePeriodInMillis;

  @JsonCreator
  private VacuumOptions(
      @JsonProperty("expireSnapshots") boolean expireSnapshots,
      @JsonProperty("removeOrphans") boolean removeOrphans,
      @JsonProperty("olderThanInMillis") Long olderThanInMillis,
      @JsonProperty("retainLast") Integer retainLast,
      @JsonProperty("location") String location,
      @JsonProperty("gracePeriodInMillis") Long gracePeriodInMillis) {
    this.expireSnapshots = expireSnapshots;
    this.removeOrphans = removeOrphans;
    this.olderThanInMillis = olderThanInMillis;
    this.retainLast = retainLast;
    this.location = location;
    this.gracePeriodInMillis = gracePeriodInMillis;
  }

  public static VacuumOptions createInstance(
      boolean expireSnapshots,
      boolean removeOrphans,
      Long olderThanInMillis,
      Integer retainLast,
      String location,
      Long gracePeriodInMillis) {
    Builder instanceBuilder = new Builder();

    instanceBuilder.setExpireSnapshots(expireSnapshots);
    instanceBuilder.setRemoveOrphans(removeOrphans);
    instanceBuilder.setOlderThanInMillis(Optional.ofNullable(olderThanInMillis));
    instanceBuilder.setRetainLast(Optional.ofNullable(retainLast));
    instanceBuilder.setLocation(location);
    instanceBuilder.setGracePeriodInMillis(gracePeriodInMillis);

    return instanceBuilder.build();
  }

  public static VacuumOptions createInstance(NessieGCPolicy nessieGCPolicy) {
    Builder instanceBuilder = new Builder();

    instanceBuilder.setExpireSnapshots(true);
    instanceBuilder.setRemoveOrphans(true);
    instanceBuilder.setOlderThanInMillis(Optional.of(nessieGCPolicy.getOlderThanInMillis()));
    instanceBuilder.setRetainLast(Optional.of(1));
    instanceBuilder.setGracePeriodInMillis(nessieGCPolicy.getGracePeriodInMillis());

    return instanceBuilder.build();
  }

  public static VacuumOptions createInstance(SqlVacuum call) {
    Builder instanceBuilder = new Builder();

    instanceBuilder.setExpireSnapshots(call.isExpireSnapshots());
    instanceBuilder.setRemoveOrphans(call.isRemoveOrphans());
    instanceBuilder.setOlderThanInMillis(call.getOlderThanInMillis());
    instanceBuilder.setRetainLast(call.getRetainLast());
    instanceBuilder.setLocation(call.getLocation());

    return instanceBuilder.build();
  }

  public static VacuumOptions createInstance(Map<String, String> tableProperties, SqlVacuum call) {
    Builder instanceBuilder = new Builder();

    instanceBuilder.setExpireSnapshots(call.isExpireSnapshots());
    instanceBuilder.setRemoveOrphans(call.isRemoveOrphans());
    instanceBuilder.setOlderThanInMillis(
        call.getOlderThanInMillis()
            .or(
                () ->
                    getLongTableProperty(tableProperties, MAX_SNAPSHOT_AGE_MS)
                        .map(age -> System.currentTimeMillis() - age)));
    instanceBuilder.setRetainLast(
        call.getRetainLast().or(() -> getIntTableProperty(tableProperties, MIN_SNAPSHOTS_TO_KEEP)));
    instanceBuilder.setLocation(call.getLocation());

    return instanceBuilder.build();
  }

  // Used by VACUUM CATALOG to find the more conservative policy. The default policy set by nessie,
  // and table properties
  public static VacuumOptions createInstance(
      Map<String, String> tableProperties, VacuumOptions vacuumOptions) {
    Builder instanceBuilder = new Builder();

    instanceBuilder.setExpireSnapshots(vacuumOptions.isExpireSnapshots());
    instanceBuilder.setRemoveOrphans(vacuumOptions.isRemoveOrphans());
    instanceBuilder.setOlderThanInMillis(
        Optional.of(
            Math.min(
                vacuumOptions.getOlderThanInMillis(),
                System.currentTimeMillis()
                    - PropertyUtil.propertyAsLong(tableProperties, MAX_SNAPSHOT_AGE_MS, 0))));
    instanceBuilder.setRetainLast(
        Optional.of(
            Math.max(
                vacuumOptions.getRetainLast(),
                PropertyUtil.propertyAsInt(tableProperties, MIN_SNAPSHOTS_TO_KEEP, 1))));
    instanceBuilder.setLocation(vacuumOptions.getLocation());
    instanceBuilder.setGracePeriodInMillis(vacuumOptions.getGracePeriodInMillis());

    return instanceBuilder.build();
  }

  public Long getOlderThanInMillis() {
    return olderThanInMillis;
  }

  public Integer getRetainLast() {
    return retainLast;
  }

  public boolean isExpireSnapshots() {
    return expireSnapshots;
  }

  public boolean isRemoveOrphans() {
    return removeOrphans;
  }

  public String getLocation() {
    return location;
  }

  // Only used by VACUUM CATALOG. Null for VACUUM TABLE commands.
  public Long getGracePeriodInMillis() {
    return gracePeriodInMillis;
  }

  @Override
  public String toString() {
    return "VacuumOptions{"
        + "olderThanInMillis="
        + olderThanInMillis
        + ", retainLast="
        + retainLast
        + ", expireSnapshots="
        + expireSnapshots
        + ", removeOrphans="
        + removeOrphans
        + ", location="
        + location
        + ", gracePeriodInMillis="
        + gracePeriodInMillis
        + '}';
  }

  private static Optional<Long> getLongTableProperty(
      Map<String, String> tableProperties, String propertyName) {
    Preconditions.checkNotNull(propertyName, "Property name must be supplied.");

    try {
      return Optional.ofNullable(
          PropertyUtil.propertyAsNullableLong(tableProperties, propertyName));
    } catch (NumberFormatException exception) {
      return Optional.empty();
    }
  }

  private static Optional<Integer> getIntTableProperty(
      Map<String, String> tableProperties, String propertyName) {
    Preconditions.checkNotNull(propertyName, "Property name must be supplied.");

    try {
      return Optional.ofNullable(PropertyUtil.propertyAsNullableInt(tableProperties, propertyName));
    } catch (NumberFormatException exception) {
      return Optional.empty();
    }
  }

  private static class Builder {
    private boolean expireSnapshots = true;
    private boolean removeOrphans = false;
    private Optional<Long> olderThanInMillis = Optional.empty();
    private Optional<Integer> retainLast = Optional.empty();
    private String location;
    private Long gracePeriodInMillis;

    private Builder() {}

    private Builder setExpireSnapshots(boolean expireSnapshots) {
      this.expireSnapshots = expireSnapshots;
      return this;
    }

    private Builder setRemoveOrphans(boolean removeOrphans) {
      this.removeOrphans = removeOrphans;
      return this;
    }

    private Builder setOlderThanInMillis(Optional<Long> olderThanInMillis) {
      this.olderThanInMillis = olderThanInMillis;
      return this;
    }

    private Builder setRetainLast(Optional<Integer> retainLast) {
      this.retainLast = retainLast;
      return this;
    }

    private Builder setLocation(String location) {
      this.location = location;
      return this;
    }

    private Builder setGracePeriodInMillis(Long gracePeriodInMillis) {
      this.gracePeriodInMillis = gracePeriodInMillis;
      return this;
    }

    private VacuumOptions build() {
      return new VacuumOptions(
          expireSnapshots,
          removeOrphans,
          olderThanInMillis.orElse(System.currentTimeMillis() - MAX_SNAPSHOT_AGE_MS_DEFAULT),
          retainLast.orElse(MIN_SNAPSHOTS_TO_KEEP_DEFAULT),
          location,
          gracePeriodInMillis);
    }
  }
}
