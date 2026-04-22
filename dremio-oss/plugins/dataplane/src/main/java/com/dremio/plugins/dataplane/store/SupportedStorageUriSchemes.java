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
package com.dremio.plugins.dataplane.store;

import static com.dremio.exec.store.dfs.FileSystemConf.CloudFileSystemScheme.AZURE_STORAGE_FILE_SYSTEM_SCHEME;
import static com.dremio.exec.store.dfs.FileSystemConf.CloudFileSystemScheme.GOOGLE_CLOUD_FILE_SYSTEM;
import static com.dremio.exec.store.dfs.FileSystemConf.CloudFileSystemScheme.S3_FILE_SYSTEM_SCHEME;
import static com.dremio.io.file.UriSchemes.DREMIO_AZURE_SCHEME;
import static com.dremio.io.file.UriSchemes.DREMIO_GCS_SCHEME;
import static com.dremio.io.file.UriSchemes.DREMIO_S3_SCHEME;
import static com.dremio.io.file.UriSchemes.GCS_SCHEME;
import static com.dremio.io.file.UriSchemes.S3A_SCHEME;
import static com.dremio.io.file.UriSchemes.S3_SCHEME;

import com.dremio.io.file.Path;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Map;
import java.util.Set;

public class SupportedStorageUriSchemes {
  public static final Set<String> GCS_FILE_SYSTEM = ImmutableSet.of(GCS_SCHEME, DREMIO_GCS_SCHEME);
  public static final Set<String> S3_FILE_SYSTEM =
      ImmutableSet.of(S3A_SCHEME, S3_SCHEME, DREMIO_S3_SCHEME);
  public static final Set<String> AZURE_FILE_SYSTEM =
      ImmutableSet.<String>builder()
          .addAll(Path.AZURE_FILE_SYSTEM)
          .add(DREMIO_AZURE_SCHEME)
          .build();

  private static final Map<String, Set<String>> SUPPORTED_SCHEME_MAP =
      ImmutableMap.of(
          S3_FILE_SYSTEM_SCHEME.getScheme(), S3_FILE_SYSTEM,
          GOOGLE_CLOUD_FILE_SYSTEM.getScheme(), GCS_FILE_SYSTEM,
          AZURE_STORAGE_FILE_SYSTEM_SCHEME.getScheme(), AZURE_FILE_SYSTEM);

  public static void validateScheme(String scheme) {
    if (!GCS_FILE_SYSTEM.contains(scheme)
        && !S3_FILE_SYSTEM.contains(scheme)
        && !AZURE_FILE_SYSTEM.contains(scheme)) {
      throw new IllegalArgumentException("Unsupported scheme: " + scheme);
    }
  }

  public static void validateSchemeEquivalence(String dremioInternalScheme, String externalScheme) {
    if (!SUPPORTED_SCHEME_MAP.get(dremioInternalScheme).contains(externalScheme)) {
      throw new IllegalArgumentException(
          String.format(
              "Scheme configuration '%s' does not accept '%s' as a valid scheme.",
              dremioInternalScheme, externalScheme));
    }
  }
}
