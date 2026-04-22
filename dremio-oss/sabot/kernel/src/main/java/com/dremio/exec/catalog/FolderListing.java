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
import java.util.Iterator;

/** The interface for listing folders in a plugin. */
public interface FolderListing extends AutoCloseable {
  /**
   * Return an iterator of folders from the plugin.
   *
   * @return iterator of folders
   */
  Iterator<ImmutableCatalogFolder> iterator();

  /** Release any resources; this listing will not be used after this call. */
  @Override
  default void close() {}
}
