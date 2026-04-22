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
package com.dremio.exec.planner.plancache.distributable;

import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.plancache.PlanCacheEntry;
import com.dremio.exec.proto.UserBitShared;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;

public class DistributedPlanCacheEntry implements PlanCacheEntry {
  private final Prel prel;
  private final String consideredReflectionsHash;
  private UserBitShared.AccelerationProfile accelerationProfile;
  private final long creationTime;

  public DistributedPlanCacheEntry(Prel prel, String reflectionMatchHash) {
    this(prel, reflectionMatchHash, System.currentTimeMillis(), null);
  }

  @JsonCreator
  public DistributedPlanCacheEntry(
      @JsonProperty("prel") Prel prel,
      @JsonProperty("reflectionMatchHash") String consideredReflectionsHash,
      @JsonProperty("creationTime") long creationTime,
      @JsonProperty("accelerationProfile") UserBitShared.AccelerationProfile accelerationProfile) {
    this.prel = prel;
    this.consideredReflectionsHash = consideredReflectionsHash;
    this.creationTime = creationTime;
    this.accelerationProfile = accelerationProfile;
  }

  @Override
  public Prel getPrel() {
    return prel;
  }

  @Override
  public UserBitShared.AccelerationProfile getAccelerationProfile() {
    return accelerationProfile;
  }

  @Override
  public void setAccelerationProfile(UserBitShared.AccelerationProfile accelerationProfile) {
    this.accelerationProfile =
        Preconditions.checkNotNull(accelerationProfile, "accelerationProfile");
  }

  @Override
  public String getConsideredReflectionsHash() {
    return consideredReflectionsHash;
  }

  @Override
  public long getCreationTime() {
    return creationTime;
  }

  /**
   * {@link DistributablePlanCache} does not track each usage of a plan cache entry.
   *
   * @return 1
   */
  @Override
  public int updateUseCount() {
    return 1;
  }
}
