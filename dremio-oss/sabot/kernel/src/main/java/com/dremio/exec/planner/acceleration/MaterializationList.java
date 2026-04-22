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

import com.dremio.common.config.SabotConfig;
import com.dremio.exec.planner.acceleration.descriptor.MaterializationDescriptor;
import com.dremio.exec.planner.acceleration.substitution.MaterializationProvider;
import com.dremio.exec.planner.acceleration.substitution.SubstitutionUtils;
import com.dremio.exec.planner.logical.ViewTable;
import com.dremio.exec.planner.observer.AttemptObserver;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.planner.sql.SqlConverter;
import com.dremio.exec.server.MaterializationDescriptorProvider;
import com.dremio.service.namespace.NamespaceKey;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.calcite.rel.RelNode;

/**
 * MaterializationList builds a list of "considered" {@link DremioMaterialization} instances that
 * are used for the lifetime of a single query. A {@link DremioMaterialization} is usually retrieved
 * from cache and then deep-copied so that it can be mutated by the planner during target
 * normalization.
 *
 * <p>DremioMaterialization instances are lazily built during SqlToRel (for default raw reflections)
 * and logical planning phases from {@link MaterializationDescriptor} instances.
 */
public class MaterializationList implements MaterializationProvider {

  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(MaterializationList.class);
  private static final String HINT_CHECKER = "dremio.reflection.acceleration.hint-checker.class";

  private final Map<NamespaceKey, MaterializationDescriptor> materializationDescriptors =
      Maps.newHashMap();
  private List<DremioMaterialization> materializations;

  private final MaterializationDescriptorProvider provider;
  private final SqlConverter converter;
  private final AttemptObserver observer;
  private final SabotConfig config;
  private final boolean verboseAcceleration;
  private boolean isUsedForIncrementalRefresh = false;

  public MaterializationList(
      final SqlConverter converter,
      final MaterializationDescriptorProvider provider,
      final AttemptObserver observer,
      final SabotConfig config) {
    this.provider = Preconditions.checkNotNull(provider, "provider is required");
    this.converter = Preconditions.checkNotNull(converter, "converter is required");
    this.observer = Preconditions.checkNotNull(observer, "observer is required");
    this.config = Preconditions.checkNotNull(config, "config is required");
    verboseAcceleration =
        converter
            .getFunctionContext()
            .getOptions()
            .getOption(PlannerSettings.VERBOSE_ACCELERATION_PROFILE);
  }

  @Override
  public List<DremioMaterialization> getConsideredMaterializations() {
    return materializations == null ? ImmutableList.of() : materializations;
  }

  @Override
  public List<DremioMaterialization> getMaterializationsByHash(String hash) {
    HintChecker hintChecker = getHintChecker();
    List<DremioMaterialization> matches = new ArrayList<>();
    for (MaterializationDescriptor descriptor : provider.get()) {
      if (hintChecker.isExcluded(descriptor)) {
        continue;
      }
      if (hash.equals(descriptor.getMatchingHash())) {
        // Prune the reflection if the descriptor should be excluded due to staleness
        if (hintChecker.isExcludedDueToStaleness(descriptor, true)) {
          continue;
        }
        materializationDescriptors.put(new NamespaceKey(descriptor.getPath()), descriptor);
        matches.add(descriptor.getMaterializationFor(converter));
      }
    }
    return matches;
  }

  @Override
  public void notifyIncrementalRefresh() {
    isUsedForIncrementalRefresh = true;
  }

  @Override
  public java.util.Optional<DremioMaterialization> getDefaultRawMaterialization(ViewTable table) {

    final java.util.Optional<MaterializationDescriptor> opt =
        provider.getDefaultRawMaterialization(table);

    if (opt.isPresent()) {
      MaterializationDescriptor descriptor = opt.get();
      HintChecker hintChecker = getHintChecker();
      if (hintChecker.isExcluded(descriptor)) {
        return java.util.Optional.empty();
      } else if (hintChecker.isExcludedDueToStaleness(descriptor, true)) {
        // Prune the reflection if the descriptor should be excluded due to staleness
        return java.util.Optional.empty();
      } else {
        try {
          DremioMaterialization materialization = descriptor.getMaterializationFor(converter);
          materializationDescriptors.put(new NamespaceKey(descriptor.getPath()), descriptor);
          return java.util.Optional.of(materialization);
        } catch (Throwable e) {
          logger.warn("Failed to expand materialization {}", descriptor.getMaterializationId(), e);
        }
      }
    }
    return java.util.Optional.empty();
  }

  @Override
  public boolean isMaterializationCacheInitialized() {
    return provider.isMaterializationCacheInitialized();
  }

  @Override
  public List<String> dumpMaterializationStateToJson() {
    return provider.dumpMaterializationStateToJson();
  }

  public Optional<MaterializationDescriptor> getDescriptor(final List<String> path) {
    final MaterializationDescriptor descriptor =
        materializationDescriptors.get(new NamespaceKey(path));
    return Optional.ofNullable(descriptor);
  }

  @Override
  public List<DremioMaterialization> buildConsideredMaterializations(RelNode userQueryNode) {
    if (materializations != null) {
      return materializations;
    }

    final Set<SubstitutionUtils.VersionedPath> queryTablesUsed =
        SubstitutionUtils.findTables(userQueryNode);
    final Set<SubstitutionUtils.VersionedPath> queryVdsUsed =
        SubstitutionUtils.findExpansionNodes(userQueryNode);
    final Set<SubstitutionUtils.ExternalQueryDescriptor> externalQueries =
        SubstitutionUtils.findExternalQueries(userQueryNode);

    final List<String> allAvailable = Lists.newArrayList();
    final List<String> excludedHints = Lists.newArrayList();
    final List<String> excludedStaleIceberg = Lists.newArrayList();
    final List<String> excludedIncrementalRefresh = Lists.newArrayList();
    final List<String> excludedNotApplicable = Lists.newArrayList();
    final List<String> excludedNotUseTableOrVds = Lists.newArrayList();

    HintChecker hintChecker = getHintChecker();
    final List<DremioMaterialization> materializations = Lists.newArrayList();
    for (final MaterializationDescriptor descriptor : provider.get()) {
      allAvailable.add(descriptor.getMaterializationId());
      if (hintChecker.isExcluded(descriptor)) {
        excludedHints.add(descriptor.getMaterializationId());
        continue;
      }
      try {
        // Only allow a field based incremental reflection to accelerate another
        // incremental reflection refresh
        if (isUsedForIncrementalRefresh
            && (descriptor.getIncrementalUpdateSettings().isFileMtimeBasedUpdate()
                || descriptor.getIncrementalUpdateSettings().isSnapshotBasedUpdate())) {
          excludedIncrementalRefresh.add(descriptor.getMaterializationId());
          continue;
        }
        // Prune the reflection early if the descriptor is already expanded
        if (!descriptor.isApplicable(queryTablesUsed, queryVdsUsed, externalQueries)) {
          excludedNotApplicable.add(descriptor.getMaterializationId());
          continue;
        }

        // Prune the reflection if the descriptor should be excluded due to staleness
        if (hintChecker.isExcludedDueToStaleness(descriptor, false)) {
          excludedStaleIceberg.add(descriptor.getMaterializationId());
          continue;
        }

        final DremioMaterialization materialization = descriptor.getMaterializationFor(converter);
        if (materialization == null) {
          continue;
        }
        if (!SubstitutionUtils.usesTableOrVds(
            queryTablesUsed, queryVdsUsed, externalQueries, materialization.getQueryRel())) {
          excludedNotUseTableOrVds.add(descriptor.getMaterializationId());
          continue;
        }
        final NamespaceKey path = new NamespaceKey(descriptor.getPath());
        materializationDescriptors.put(path, descriptor);
        materializations.add(materialization);

      } catch (Throwable e) {
        logger.warn("failed to expand materialization {}", descriptor.getMaterializationId(), e);
      }
    }
    if (verboseAcceleration) {
      log("available", allAvailable);
      log("excludedHints", excludedHints);
      log("excludedStaleIceberg", excludedStaleIceberg);
      log("excludedIncrementalRefresh", excludedIncrementalRefresh);
      log("excludedNotApplicable", excludedNotApplicable);
      log("excludedNotUseTableOrView", excludedNotUseTableOrVds);
    }

    this.materializations = materializations;
    return materializations;
  }

  private void log(String step, Collection<String> materializations) {
    converter
        .getObserver()
        .substitutionFailures(ImmutableList.of(String.format("%s: %s", step, materializations)));
  }

  public static Set<String> parseReflectionIds(String value) {
    return Arrays.asList(value.split(",")).stream()
        .map(x -> x.trim())
        .filter(x -> !x.isEmpty())
        .collect(Collectors.toSet());
  }

  private HintChecker getHintChecker() {
    HintChecker defaultHintChecker =
        new HintChecker(converter.getFunctionContext().getOptions(), observer);
    return config.getInstance(
        HINT_CHECKER, HintChecker.class, defaultHintChecker, defaultHintChecker);
  }
}
