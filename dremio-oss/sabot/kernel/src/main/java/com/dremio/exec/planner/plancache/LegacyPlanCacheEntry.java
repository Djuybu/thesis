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

import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.proto.UserBitShared.AccelerationProfile;
import java.util.concurrent.atomic.AtomicInteger;

public class LegacyPlanCacheEntry implements PlanCacheEntry {
  private final Prel prel;
  private final int estimatedSize; // estimated size in byte
  private final AtomicInteger useCount;
  private final long creationTime;
  private final String consideredReflectionsHash;
  private AccelerationProfile accelerationProfile;

  private LegacyPlanCacheEntry(
      Prel prel,
      AtomicInteger useCount,
      int estimatedSize,
      long creationTime,
      String consideredReflectionsHash) {
    this.prel = prel;
    this.useCount = useCount;
    this.estimatedSize = estimatedSize;
    this.creationTime = creationTime;
    this.consideredReflectionsHash = consideredReflectionsHash;
  }

  public static LegacyPlanCacheEntry createCachedPlan(
      Prel prel, int estimatedSize, String consideredReflectionsHash) {
    return new LegacyPlanCacheEntry(
        prel,
        new AtomicInteger(0),
        estimatedSize,
        System.currentTimeMillis(),
        consideredReflectionsHash);
  }

  @Override
  public Prel getPrel() {
    return prel;
  }

  @Override
  public AccelerationProfile getAccelerationProfile() {
    return accelerationProfile;
  }

  @Override
  public void setAccelerationProfile(AccelerationProfile profile) {
    this.accelerationProfile = profile;
  }

  @Override
  public int updateUseCount() {
    return this.useCount.incrementAndGet();
  }

  public int getUseCount() {
    return useCount.get();
  }

  public int getEstimatedSize() {
    return estimatedSize;
  }

  @Override
  public long getCreationTime() {
    return creationTime;
  }

  @Override
  public String getConsideredReflectionsHash() {
    return consideredReflectionsHash;
  }
}
