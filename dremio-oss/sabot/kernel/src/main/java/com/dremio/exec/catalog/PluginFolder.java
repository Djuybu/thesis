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

import com.dremio.catalog.model.ResolvedVersionContext;
import java.util.List;
import javax.annotation.Nullable;
import org.immutables.value.Value;

/** PluginFolder is a class that represents a folder in the plugin layer. */
@Value.Immutable
public interface PluginFolder {
  @Nullable
  List<String> folderPath(); // The path of the folder relative to the plugin

  String contentId(); // The id of the content in the folder

  String storageUri();

  ResolvedVersionContext resolvedVersionContext();
}
