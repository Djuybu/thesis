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
package com.dremio.exec.catalog.conf;

import static org.apache.commons.collections4.CollectionUtils.isEmpty;

import com.dremio.exec.catalog.SourceNameRefreshAction;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class ConnectionConfUtils {
  public static List<SourceNameRefreshAction> getNameRefreshActionsForFoldersChange(
      String source, List<String> currentFolders, List<String> newFolders) {

    boolean noFolderFiltersAndUnchanged = isEmpty(newFolders) && isEmpty(currentFolders);
    boolean removedAllFolderFilters = isEmpty(newFolders) && !isEmpty(currentFolders);
    boolean startUsingFolderFilters = !isEmpty(newFolders) && isEmpty(currentFolders);

    if (noFolderFiltersAndUnchanged) {
      return Collections.emptyList();
    } else if (removedAllFolderFilters) {
      return List.of(SourceNameRefreshAction.newRefreshAllAction());
    } else if (startUsingFolderFilters) {
      return List.of(
          SourceNameRefreshAction.newDeleteAllAction(),
          SourceNameRefreshAction.newRefreshAllAction());
    }

    // Here we must be modifying an already existing set of folder filters (adding some and/or
    // removing some)
    Set<String> deleted = Sets.newHashSet(currentFolders);
    newFolders.forEach(deleted::remove);
    Set<String> added = Sets.newHashSet(newFolders);
    currentFolders.forEach(added::remove);

    List<SourceNameRefreshAction> actions = Lists.newArrayList();
    if (!deleted.isEmpty()) {
      List<List<String>> folders =
          deleted.stream().map(database -> List.of(source, database)).collect(Collectors.toList());
      actions.add(SourceNameRefreshAction.newDeleteFoldersAction(folders));
    }

    if (!added.isEmpty()) {
      List<List<String>> folders =
          added.stream().map(database -> List.of(source, database)).collect(Collectors.toList());
      actions.add(SourceNameRefreshAction.newRefreshFoldersAction(folders));
    }

    return actions;
  }
}
