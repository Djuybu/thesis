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
package com.dremio.plugins.icebergcatalog.dfs;

import com.dremio.io.file.Path;
import java.net.URI;
import java.util.List;
import java.util.Objects;

class DatasetFileSystemCacheKey {
  private final String scheme;
  private final String authority;
  private final URI uri;
  private final String userName;
  private final List<String> dataset;

  /**
   * This key is used for the cache which loads FileSystem using the Plugin class loader to avoid
   * class Cast exceptions. This key considers uniqueness by using the scheme and authority. This
   * key is used for cache which stores the fs at plugin level.
   *
   * @param uri - uri for which fileSystem will be created or checked
   * @param userName - username tied to the FileSystem
   * @param dataset - dataset (i.e. fully qualified table name)
   */
  public DatasetFileSystemCacheKey(URI uri, String userName, List<String> dataset) {
    this.uri = uri;
    this.scheme = uri.getScheme() == null ? "" : uri.getScheme();
    this.authority = uri.getAuthority() == null ? "" : uri.getAuthority();
    this.userName = userName;
    this.dataset = dataset;
  }

  public URI getUri() {
    return uri;
  }

  public String getUserName() {
    return userName;
  }

  public List<String> getDataset() {
    return dataset;
  }

  @Override
  public int hashCode() {
    return Objects.hash(scheme, authority, userName, dataset);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    DatasetFileSystemCacheKey key = (DatasetFileSystemCacheKey) o;
    return Objects.equals(scheme, key.scheme)
        && Objects.equals(authority, key.authority)
        && Objects.equals(userName, key.userName)
        && Objects.equals(dataset, key.dataset);
  }

  @Override
  public String toString() {
    StringBuilder sb =
        new StringBuilder()
            .append(userName)
            .append(Path.CONTAINER_SEPARATOR)
            .append(scheme)
            .append("://")
            .append(authority);
    if (dataset != null) {
      sb.append(" [");
      sb.append(String.join(Path.SEPARATOR, dataset));
      sb.append("]");
    }
    return sb.toString();
  }
}
