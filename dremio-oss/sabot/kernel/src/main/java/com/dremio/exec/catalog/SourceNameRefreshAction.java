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
public interface SourceNameRefreshAction {
  enum Action {
    DELETE_ALL,
    REFRESH_ALL,
    DELETE_FOLDERS,
    REFRESH_FOLDERS
  }

  Action getAction();

  @Nullable
  List<List<String>> getFolders();

  static SourceNameRefreshAction newDeleteAllAction() {
    return new ImmutableSourceNameRefreshAction.Builder()
        .setAction(Action.DELETE_ALL)
        .setFolders(null)
        .build();
  }

  static SourceNameRefreshAction newRefreshAllAction() {
    return new ImmutableSourceNameRefreshAction.Builder()
        .setAction(Action.REFRESH_ALL)
        .setFolders(null)
        .build();
  }

  static SourceNameRefreshAction newDeleteFoldersAction(List<List<String>> prefixes) {
    return new ImmutableSourceNameRefreshAction.Builder()
        .setAction(Action.DELETE_FOLDERS)
        .setFolders(prefixes)
        .build();
  }

  static SourceNameRefreshAction newRefreshFoldersAction(List<List<String>> prefixes) {
    return new ImmutableSourceNameRefreshAction.Builder()
        .setAction(Action.REFRESH_FOLDERS)
        .setFolders(prefixes)
        .build();
  }
}
