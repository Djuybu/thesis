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
package com.dremio.plugins.util;

import com.google.common.base.Preconditions;
import software.amazon.awssdk.regions.Region;

public final class S3PluginUtils {
  private S3PluginUtils() {
    // Not to be instantiated
  }

  public static boolean isValidAwsRegion(String regionString) {
    return Region.regions().stream().anyMatch(region -> region.id().equals(regionString));
  }

  public static void checkIfValidAwsRegion(String regionString) {
    Preconditions.checkArgument(
        isValidAwsRegion(regionString),
        String.format("%s is not a valid AWS region.", regionString));
  }

  public static void checkIfValidAwsRegion(Region region) {
    checkIfValidAwsRegion(region.id());
  }
}
