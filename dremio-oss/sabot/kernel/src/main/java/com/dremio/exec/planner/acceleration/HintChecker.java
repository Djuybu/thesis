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
package com.dremio.exec.planner.acceleration;

import com.dremio.exec.planner.acceleration.descriptor.MaterializationDescriptor;
import com.dremio.exec.planner.observer.AttemptObserver;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.proto.UserBitShared.LayoutMaterializedViewProfile;
import com.dremio.options.OptionResolver;
import java.util.Set;

class HintChecker {

  private final OptionResolver optionResolver;
  private final AttemptObserver observer;
  private final Set<String> exclusions;
  private final Set<String> inclusions;
  private final boolean hasInclusions;
  private final boolean reflectionMaterializationStalenessEnabled;
  private final boolean currentIcebergDataOnly;

  public HintChecker(HintChecker other) {
    this.optionResolver = other.optionResolver;
    this.observer = other.observer;
    this.exclusions = other.exclusions;
    this.inclusions = other.inclusions;
    this.hasInclusions = other.hasInclusions;
    this.reflectionMaterializationStalenessEnabled =
        other.reflectionMaterializationStalenessEnabled;
    this.currentIcebergDataOnly = other.currentIcebergDataOnly;
  }

  public HintChecker(OptionResolver optionResolver, AttemptObserver observer) {
    this.optionResolver = optionResolver;
    this.observer = observer;
    exclusions = getExclusions();
    inclusions = getInclusions();
    hasInclusions = !inclusions.isEmpty();
    reflectionMaterializationStalenessEnabled =
        optionResolver.getOption(PlannerSettings.REFLECTION_MATERIALIZATION_STALENESS_ENABLED);
    currentIcebergDataOnly = optionResolver.getOption(PlannerSettings.CURRENT_ICEBERG_DATA_ONLY);
  }

  public boolean isExcluded(MaterializationDescriptor descriptor) {
    // Reflections explicitly disabled for query
    if (isNoReflections()) {
      return true;
    }

    // Once an inclusion is provided, then the descriptor must be explicitly whitelisted
    if (hasInclusions && !inclusions.contains(descriptor.getLayoutId())) {
      return true;
    }

    // An exclusion blacklists a descriptor
    if (exclusions.contains(descriptor.getLayoutId())) {
      return true;
    }

    return false;
  }

  public boolean isExcludedDueToStaleness(
      MaterializationDescriptor descriptor, boolean defaultReflection) {
    boolean isExcluded = isExcludedForCurrentIcebergDataOnlyHint(descriptor);
    if (isExcluded) {
      reportPlanConsidered(descriptor, defaultReflection);
    }
    return isExcluded;
  }

  protected boolean isExcludedForCurrentIcebergDataOnlyHint(MaterializationDescriptor descriptor) {
    return reflectionMaterializationStalenessEnabled
        && currentIcebergDataOnly
        && descriptor.isStale();
  }

  protected void reportPlanConsidered(
      MaterializationDescriptor descriptor, boolean defaultReflection) {
    final LayoutMaterializedViewProfile profile =
        LayoutMaterializedViewProfile.newBuilder(
                descriptor.getLayoutMaterializedViewProfile(observer.isVerbose()))
            .setDefaultReflection(defaultReflection)
            .build();
    observer.planConsidered(profile, null);
  }

  protected OptionResolver getOptionResolver() {
    return optionResolver;
  }

  protected boolean isReflectionMaterializationStalenessEnabled() {
    return reflectionMaterializationStalenessEnabled;
  }

  private Set<String> getInclusions() {
    Set<String> inclusions =
        MaterializationList.parseReflectionIds(
            optionResolver.getOption(PlannerSettings.CONSIDER_REFLECTIONS));
    return inclusions;
  }

  private Set<String> getExclusions() {
    Set<String> exclusions =
        MaterializationList.parseReflectionIds(
            optionResolver.getOption(PlannerSettings.EXCLUDE_REFLECTIONS));
    return exclusions;
  }

  private boolean isNoReflections() {
    return optionResolver.getOption(PlannerSettings.NO_REFLECTIONS);
  }
}
