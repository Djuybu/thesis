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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.Lists;
import java.net.URI;
import java.net.URISyntaxException;
import org.junit.Test;

public class TestDatasetFileSystemCacheKey {

  @Test
  public void testEquals() throws URISyntaxException {
    URI uri = new URI("scheme", "authority", "/path", "query", "fragment");
    DatasetFileSystemCacheKey cacheKey = new DatasetFileSystemCacheKey(uri, "userName", null);
    URI uriDifferentScheme = new URI("differentScheme", "authority", "/path", "query", "fragment");
    DatasetFileSystemCacheKey cacheKeyDifferentScheme =
        new DatasetFileSystemCacheKey(uriDifferentScheme, "userName", null);
    URI uriDifferentAuthority =
        new URI("scheme", "differentAuthority", "/path", "query", "fragment");
    DatasetFileSystemCacheKey cacheKeyDifferentAuthority =
        new DatasetFileSystemCacheKey(uriDifferentAuthority, "userName", null);
    assertTrue(cacheKey.equals(cacheKey));
    assertFalse(cacheKey.equals(new Object()));
    assertFalse(cacheKey.equals(null));
    assertFalse(cacheKey.equals(cacheKeyDifferentScheme));
    assertFalse(cacheKey.equals(cacheKeyDifferentAuthority));
  }

  @Test
  public void testEqualsWithDataset() throws URISyntaxException {
    URI uri = new URI("scheme", "authority", "/path", "query", "fragment");
    DatasetFileSystemCacheKey cacheKey =
        new DatasetFileSystemCacheKey(uri, "userName", Lists.newArrayList("a", "b", "c"));
    DatasetFileSystemCacheKey cacheKeySame =
        new DatasetFileSystemCacheKey(uri, "userName", Lists.newArrayList("a", "b", "c"));
    DatasetFileSystemCacheKey cacheKeyDifferentDataset =
        new DatasetFileSystemCacheKey(uri, "userName", Lists.newArrayList("a", "b", "D"));
    DatasetFileSystemCacheKey cacheKeyNoDataset =
        new DatasetFileSystemCacheKey(uri, "userName", null);
    assertEquals(cacheKey, cacheKeySame);
    assertNotEquals(cacheKey, cacheKeyDifferentDataset);
    assertNotEquals(cacheKey.hashCode(), cacheKeyNoDataset.hashCode());
  }

  @Test
  public void testRelativeUri() throws URISyntaxException {
    URI uri = new URI("relative/path");
    DatasetFileSystemCacheKey cacheKey = new DatasetFileSystemCacheKey(uri, "userName", null);
    assertEquals("userName@://", cacheKey.toString());
  }

  @Test
  public void testToString() throws URISyntaxException {
    URI uri = new URI("scheme", "userInfo", "host", -1, "/path", "query", "fragment");
    DatasetFileSystemCacheKey cacheKey = new DatasetFileSystemCacheKey(uri, "userName", null);
    assertEquals("userName@scheme://userInfo@host", cacheKey.toString());
  }
}
