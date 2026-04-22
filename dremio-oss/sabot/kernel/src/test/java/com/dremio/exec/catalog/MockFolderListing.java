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

import com.dremio.catalog.model.ImmutableCatalogFolder;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

public class MockFolderListing implements FolderListing {
  private static final List<ImmutableCatalogFolder> DEFAULT_FOLDERS =
      Arrays.asList(
          (new ImmutableCatalogFolder.Builder())
              .setId("0")
              .setFullPath(Arrays.asList("source", "folder0"))
              .setStorageUri("file:///tmp/folder0")
              .build(),
          (new ImmutableCatalogFolder.Builder())
              .setId("1")
              .setFullPath(Arrays.asList("source", "folder1"))
              .setStorageUri("file:///tmp/folder1")
              .build(),
          (new ImmutableCatalogFolder.Builder())
              .setId("2")
              .setFullPath(Arrays.asList("source", "folder1", "folder2"))
              .setStorageUri("file:///tmp/folder1/folder2")
              .build());

  private final List<ImmutableCatalogFolder> folders;

  public MockFolderListing(@Nullable List<ImmutableCatalogFolder> folders) {
    this.folders = Objects.requireNonNullElse(folders, DEFAULT_FOLDERS);
  }

  @Override
  public Iterator<ImmutableCatalogFolder> iterator() {
    return folders.iterator();
  }
}
