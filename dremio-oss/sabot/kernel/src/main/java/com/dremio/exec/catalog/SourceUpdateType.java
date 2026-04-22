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

import java.util.List;
import javax.annotation.Nullable;
import org.immutables.value.Value;

@Value.Immutable
public interface SourceUpdateType {

  enum Type {
    NAMES,
    NAMES_IN_FOLDERS,
    FULL,
    NONE
  }

  SourceUpdateType NAMES =
      new ImmutableSourceUpdateType.Builder().setType(Type.NAMES).setFolders(null).build();
  SourceUpdateType FULL =
      new ImmutableSourceUpdateType.Builder().setType(Type.FULL).setFolders(null).build();
  SourceUpdateType NONE =
      new ImmutableSourceUpdateType.Builder().setType(Type.NONE).setFolders(null).build();

  Type getType();

  @Nullable
  List<List<String>> getFolders();

  static SourceUpdateType ofNamesInFolders(List<List<String>> folders) {
    return new ImmutableSourceUpdateType.Builder()
        .setType(Type.NAMES_IN_FOLDERS)
        .setFolders(folders)
        .build();
  }
}
