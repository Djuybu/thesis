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

class MetadataSynchronizerStatus {

  private final boolean fullRefresh;

  private long shallowDatasetAdded;
  private long shallowDatasetDeleted;
  private long shallowDatasetUnchanged;

  private long extendedDatasetChanged;
  private long extendedDatasetUnchanged;
  private long extendedDatasetUnreadable;
  private long extendedDatasetDeleted;

  private boolean wasSuccessfullyRefreshed;

  MetadataSynchronizerStatus(boolean fullRefresh) {
    this.fullRefresh = fullRefresh;
  }

  void incrementDatasetShallowAdded() {
    shallowDatasetAdded++;
  }

  void incrementDatasetShallowDeleted() {
    shallowDatasetDeleted++;
  }

  void incrementDatasetShallowUnchanged() {
    shallowDatasetUnchanged++;
  }

  void incrementDatasetExtendedChanged() {
    extendedDatasetChanged++;
  }

  void incrementDatasetExtendedUnchanged() {
    extendedDatasetUnchanged++;
  }

  void incrementDatasetExtendedUnreadable() {
    extendedDatasetUnreadable++;
  }

  void incrementDatasetExtendedDeleted() {
    extendedDatasetDeleted++;
  }

  void setWasSuccessfullyRefreshed() {
    this.wasSuccessfullyRefreshed = true;
  }

  boolean wasSyncSuccessful() {
    return wasSuccessfullyRefreshed;
  }

  @Override
  public String toString() {
    final StringBuilder builder = new StringBuilder();
    builder.append(
        String.format(
            "Shallow probed %d datasets: %d added, %d unchanged, %d deleted\n",
            shallowDatasetAdded + shallowDatasetUnchanged + shallowDatasetDeleted,
            shallowDatasetAdded,
            shallowDatasetUnchanged,
            shallowDatasetDeleted));
    if (fullRefresh) {
      builder.append(
          String.format(
              "Deep probed %d queried datasets: %d changed, %d unchanged, %d deleted, %d unreadable\n",
              extendedDatasetChanged
                  + extendedDatasetUnchanged
                  + extendedDatasetDeleted
                  + extendedDatasetUnreadable,
              extendedDatasetChanged,
              extendedDatasetUnchanged,
              extendedDatasetDeleted,
              extendedDatasetUnreadable));
    }
    return builder.toString();
  }
}
