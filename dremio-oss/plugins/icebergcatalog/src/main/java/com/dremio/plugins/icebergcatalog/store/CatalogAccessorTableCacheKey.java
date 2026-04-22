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
package com.dremio.plugins.icebergcatalog.store;

import java.util.Objects;
import org.apache.iceberg.catalog.TableIdentifier;

public final class CatalogAccessorTableCacheKey {
  private final String userId;
  private final TableIdentifier tableIdentifier;

  public CatalogAccessorTableCacheKey(String userId, TableIdentifier tableIdentifier) {
    if (userId == null) {
      throw new IllegalArgumentException("UserId cannot be null");
    }
    if (tableIdentifier == null) {
      throw new IllegalArgumentException("TableIdentifier cannot be null");
    }
    this.userId = userId;
    this.tableIdentifier = tableIdentifier;
  }

  public String userId() {
    return userId;
  }

  public TableIdentifier tableIdentifier() {
    return tableIdentifier;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    CatalogAccessorTableCacheKey that = (CatalogAccessorTableCacheKey) o;
    return userId.equals(that.userId) && tableIdentifier.equals(that.tableIdentifier);
  }

  @Override
  public int hashCode() {
    return Objects.hash(userId, tableIdentifier);
  }

  @Override
  public String toString() {
    return "CatalogAccessorTableCacheKey{"
        + "userId='"
        + userId
        + '\''
        + ", tableIdentifier="
        + tableIdentifier
        + '}';
  }
}
