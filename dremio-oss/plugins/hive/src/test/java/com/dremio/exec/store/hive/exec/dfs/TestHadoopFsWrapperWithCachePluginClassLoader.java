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
package com.dremio.exec.store.hive.exec.dfs;

import com.dremio.exec.store.hive.exec.HadoopFsCacheKeyPluginClassLoader;
import com.dremio.exec.store.hive.exec.HadoopFsCacheWrapperPluginClassLoader;
import com.dremio.io.file.UriSchemes;
import com.google.common.cache.LoadingCache;
import org.apache.hadoop.mapred.JobConf;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class TestHadoopFsWrapperWithCachePluginClassLoader {

  private static final String HOST = "localhost";
  private static final String PATH = "/sample/data";

  private HadoopFsWrapperWithCachePluginClassLoader cacheWrapper;

  @Before
  public void before() throws Exception {
    cacheWrapper = new HadoopFsWrapperWithCachePluginClassLoader();
  }

  @After
  public void after() throws Exception {
    cacheWrapper = null;
  }

  @Test
  public void testCacheOnRemovalCallsClose() throws Exception {
    LoadingCache<String, HadoopFsCacheWrapperPluginClassLoader> cache = cacheWrapper.getCache();
    HadoopFsCacheWrapperPluginClassLoader mockPluginClassLoader = mock(HadoopFsCacheWrapperPluginClassLoader.class);
    URI uri = URI.create(String.format("%s://%s%s_%s", UriSchemes.HDFS_SCHEME, HOST, PATH, "testCacheOnRemoval"));
    HadoopFsCacheKeyPluginClassLoader keyClassLoader = new HadoopFsCacheKeyPluginClassLoader(uri, new JobConf(), "dremio", true);
    cache.put("key", mockPluginClassLoader);
    cache.invalidate("key");
    verify(mockPluginClassLoader, times(1)).close();
  }

}
