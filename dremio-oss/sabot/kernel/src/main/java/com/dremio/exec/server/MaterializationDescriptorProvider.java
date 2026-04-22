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
package com.dremio.exec.server;

import com.dremio.exec.planner.acceleration.descriptor.MaterializationDescriptor;
import com.dremio.exec.planner.logical.ViewTable;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Optional;

/** A materialization provider for planning with reflections */
public interface MaterializationDescriptorProvider {
  /**
   * Provides a list of materialization instances.
   *
   * @return a list of {@code Materialization} instances. Might be empty.
   */
  List<MaterializationDescriptor> get();

  /**
   * Returns the default raw materialization that provider considers for substitution for the VDS
   * with the given path
   *
   * @return The default reflection for the VDS
   */
  Optional<MaterializationDescriptor> getDefaultRawMaterialization(ViewTable table);

  /**
   * Returns true if the provider has a reflection goal that is enabled and qualifies for default
   * matching for the VDS with the given path
   */
  boolean hasDefaultRawReflection(ViewTable viewTable);

  /** Returns true when materialization cache is primed and ready to go */
  boolean isMaterializationCacheInitialized();

  default List<String> dumpMaterializationStateToJson() {
    return ImmutableList.of();
  }

  /** Empty materialization provider. */
  MaterializationDescriptorProvider EMPTY =
      new MaterializationDescriptorProvider() {

        @Override
        public List<MaterializationDescriptor> get() {
          return ImmutableList.of();
        }

        @Override
        public Optional<MaterializationDescriptor> getDefaultRawMaterialization(ViewTable table) {
          return Optional.empty();
        }

        @Override
        public boolean hasDefaultRawReflection(ViewTable viewTable) {
          return false;
        }

        @Override
        public boolean isMaterializationCacheInitialized() {
          return true;
        }
      };
}
