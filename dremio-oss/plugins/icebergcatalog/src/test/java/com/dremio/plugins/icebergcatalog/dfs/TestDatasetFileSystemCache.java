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

import static com.dremio.common.UserConstants.SYSTEM_ID;
import static com.dremio.exec.store.IcebergCatalogPluginOptions.RESTCATALOG_PLUGIN_FILE_SYSTEM_EXPIRE_AFTER_WRITE_MINUTES;
import static com.dremio.exec.store.IcebergCatalogPluginOptions.RESTCATALOG_PLUGIN_FILE_SYSTEM_OPTIMISTIC_LOCKING;
import static com.dremio.io.file.UriSchemes.DREMIO_AZURE_SCHEME;
import static com.dremio.io.file.UriSchemes.DREMIO_GCS_SCHEME;
import static com.dremio.io.file.UriSchemes.DREMIO_S3_SCHEME;
import static com.dremio.service.users.SystemUser.SYSTEM_USERNAME;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.store.hive.exec.FileSystemConfUtil;
import com.dremio.io.FSOutputStream;
import com.dremio.io.file.Path;
import com.dremio.io.file.UriSchemes;
import com.dremio.options.OptionManager;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.collect.Lists;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.security.PrivilegedExceptionAction;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.security.UserGroupInformation;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class TestDatasetFileSystemCache {

  private static OptionManager mockOptionManager;
  private static Function<List<String>, Configuration> fsProvider =
      dataset -> new Configuration(false);
  private DatasetFileSystemCache fsCache;

  static MockedStatic<UserGroupInformation> withUgiMock() throws IOException, InterruptedException {
    UserGroupInformation ugi = mock(UserGroupInformation.class);
    MockedStatic<UserGroupInformation> ugiMock = mockStatic(UserGroupInformation.class);
    ugiMock.when(UserGroupInformation::getLoginUser).thenReturn(ugi);
    ugiMock.when(UserGroupInformation::getCurrentUser).thenReturn(ugi);

    FileSystem fs = mock(FileSystem.class);
    when(ugi.doAs(any(PrivilegedExceptionAction.class)))
        .thenReturn(new LockableHadoopFileSystem(fs));
    when(fs.getScheme()).thenReturn("not-interesting");
    return ugiMock;
  }

  @BeforeClass
  public static void setup() throws IOException {
    mockOptionManager = mock(OptionManager.class);
    when(mockOptionManager.getOption(RESTCATALOG_PLUGIN_FILE_SYSTEM_EXPIRE_AFTER_WRITE_MINUTES))
        .thenReturn(1L);
    when(mockOptionManager.getOption(RESTCATALOG_PLUGIN_FILE_SYSTEM_OPTIMISTIC_LOCKING))
        .thenReturn(true);
  }

  @Before
  public void before() {
    fsCache = new DatasetFileSystemCache(fsProvider, mockOptionManager);
    fsCache.initCache();
    fsCache.cache = spy(fsCache.cache);
  }

  @After
  public void teardown() {
    fsCache = null;
  }

  @Test
  public void testCacheRemovalListener() throws IOException, InterruptedException {
    fsCache = new DatasetFileSystemCache(fsProvider, mockOptionManager);
    fsCache.initCache();
    LoadingCache<DatasetFileSystemCacheKey, LockableHadoopFileSystem> cache = fsCache.cache;
    FileSystem mockFileSystem = mock(FileSystem.class);
    URI uri =
        URI.create(
            String.format(
                "%s://%s%s_%s", UriSchemes.HDFS_SCHEME, "hostname", "path", "testCacheOnRemoval"));
    DatasetFileSystemCacheKey datasetFileSystemCacheKey =
        new DatasetFileSystemCacheKey(uri, "dremio", null);
    cache.put(datasetFileSystemCacheKey, new LockableHadoopFileSystem(mockFileSystem));
    cache.invalidate(datasetFileSystemCacheKey);
    // invalidation happens in an async way
    Thread.sleep(100L);
    verify(mockFileSystem, times(1)).close();
  }

  @Test
  public void testBuildCacheKeyWithInvalidStorageProviderCreds() {
    fsCache = new DatasetFileSystemCache(fsProvider, mockOptionManager);
    fsCache.initCache();
    LoadingCache<DatasetFileSystemCacheKey, LockableHadoopFileSystem> cache = fsCache.cache;
    URI uri = URI.create(String.format("%s://%s", UriSchemes.S3_SCHEME, "bucket/path"));
    DatasetFileSystemCacheKey datasetFileSystemCacheKey =
        new DatasetFileSystemCacheKey(uri, SYSTEM_USERNAME, null);
    assertThatThrownBy(() -> cache.get(datasetFileSystemCacheKey))
        .isInstanceOf(UserException.class)
        .hasMessageContaining(
            "Credentials for the Storage Provider must be valid and have required access");
  }

  @Test
  public void testBuildCacheKeyWithSystemUsername() {
    fsCache = new DatasetFileSystemCache(fsProvider, mockOptionManager);
    fsCache.initCache();
    LoadingCache<DatasetFileSystemCacheKey, LockableHadoopFileSystem> cache = fsCache.cache;
    URI uri = URI.create(String.format("%s://%s", UriSchemes.HDFS_SCHEME, "incomplete_hdfs_uri"));
    DatasetFileSystemCacheKey datasetFileSystemCacheKey =
        new DatasetFileSystemCacheKey(uri, SYSTEM_USERNAME, null);
    assertThatThrownBy(() -> cache.get(datasetFileSystemCacheKey))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Incomplete HDFS URI, no host: hdfs://incomplete_hdfs_uri");
  }

  @Test
  public void testBuildCacheKeyWithLoginUsername() {
    fsCache = new DatasetFileSystemCache(fsProvider, mockOptionManager);
    fsCache.initCache();
    LoadingCache<DatasetFileSystemCacheKey, LockableHadoopFileSystem> cache = fsCache.cache;
    URI uri = URI.create(String.format("%s://%s", UriSchemes.HDFS_SCHEME, "incomplete_hdfs_uri"));
    DatasetFileSystemCacheKey datasetFileSystemCacheKey =
        new DatasetFileSystemCacheKey(uri, "user", null);
    assertThatThrownBy(() -> cache.get(datasetFileSystemCacheKey))
        .isInstanceOf(UserException.class)
        .hasMessageContaining("Incomplete HDFS URI, no host: hdfs://incomplete_hdfs_uri");
  }

  @Test
  public void testLoadFile() {
    fsCache = new DatasetFileSystemCache(fsProvider, mockOptionManager);
    com.dremio.io.file.FileSystem fsNullScheme =
        fsCache.load("/path/to", SYSTEM_USERNAME, SYSTEM_ID, null, null, false);
    assertTrue(fsNullScheme.unwrap(LocalFileSystem.class) instanceof LocalFileSystem);
    com.dremio.io.file.FileSystem fsFileScheme =
        fsCache.load("file:///path/to", SYSTEM_USERNAME, SYSTEM_ID, null, null, false);
    assertTrue(fsFileScheme.unwrap(LocalFileSystem.class) instanceof LocalFileSystem);
  }

  @Test
  public void testLoadS3() throws IOException, InterruptedException {
    try (MockedStatic<FileSystemConfUtil> mockFsConfUtil = mockStatic(FileSystemConfUtil.class)) {
      try (MockedStatic<UserGroupInformation> noop = withUgiMock()) {
        ((SelfManagingCachedFileSystem)
                fsCache.load("s3:///path/to", SYSTEM_USERNAME, SYSTEM_ID, null, null, false))
            .eagerInitialize();
        ArgumentCaptor<URI> argument = ArgumentCaptor.forClass(URI.class);
        mockFsConfUtil.verify(
            () -> FileSystemConfUtil.initializeConfiguration(argument.capture(), any()));
        assertEquals(DREMIO_S3_SCHEME, argument.getValue().getScheme());
      }
    }
  }

  @Test
  public void testLoadAzure() throws IOException, InterruptedException {
    try (MockedStatic<FileSystemConfUtil> mockFsConfUtil = mockStatic(FileSystemConfUtil.class)) {
      try (MockedStatic<UserGroupInformation> noop = withUgiMock()) {
        ((SelfManagingCachedFileSystem)
                fsCache.load(
                    "abfs://authority/path/to", SYSTEM_USERNAME, SYSTEM_ID, null, null, false))
            .eagerInitialize();
        ArgumentCaptor<URI> argument = ArgumentCaptor.forClass(URI.class);
        mockFsConfUtil.verify(
            () -> FileSystemConfUtil.initializeConfiguration(argument.capture(), any()));
        assertEquals(DREMIO_AZURE_SCHEME, argument.getValue().getScheme());
      }
    }
  }

  @Test
  public void testLoadGCS() throws IOException, InterruptedException {
    try (MockedStatic<FileSystemConfUtil> mockFsConfUtil = mockStatic(FileSystemConfUtil.class)) {
      try (MockedStatic<UserGroupInformation> noop = withUgiMock()) {
        ((SelfManagingCachedFileSystem)
                fsCache.load(
                    "gs://authority/path/to?query#fragment",
                    SYSTEM_USERNAME,
                    SYSTEM_ID,
                    null,
                    null,
                    false))
            .eagerInitialize();
        ArgumentCaptor<URI> argument = ArgumentCaptor.forClass(URI.class);
        mockFsConfUtil.verify(
            () -> FileSystemConfUtil.initializeConfiguration(argument.capture(), any()));
        assertEquals(DREMIO_GCS_SCHEME, argument.getValue().getScheme());
      }
    }
  }

  @Test
  public void testLoadHdfs() throws IOException, InterruptedException {
    try (MockedStatic<FileSystemConfUtil> mockFsConfUtil = mockStatic(FileSystemConfUtil.class)) {
      try (MockedStatic<UserGroupInformation> noop = withUgiMock()) {
        ((SelfManagingCachedFileSystem)
                fsCache.load(
                    "hdfs:///authority/path/to?query#fragment",
                    SYSTEM_USERNAME,
                    SYSTEM_ID,
                    null,
                    null,
                    false))
            .eagerInitialize();
        ArgumentCaptor<URI> argument = ArgumentCaptor.forClass(URI.class);
        mockFsConfUtil.verify(
            () -> FileSystemConfUtil.initializeConfiguration(argument.capture(), any()));
        assertEquals("hdfs", argument.getValue().getScheme());
      }
    }
  }

  @Test
  public void testLoadIOException() {
    try (MockedStatic<FileSystemConfUtil> mockFsConfUtil = mockStatic(FileSystemConfUtil.class)) {
      mockFsConfUtil
          .when(() -> FileSystemConfUtil.initializeConfiguration(any(), any()))
          .thenThrow(IOException.class);
      assertThatThrownBy(
              () ->
                  ((SelfManagingCachedFileSystem)
                          fsCache.load(
                              "gs://authority/path/to?query#fragment",
                              SYSTEM_USERNAME,
                              SYSTEM_ID,
                              null,
                              null,
                              false))
                      .eagerInitialize())
          .isInstanceOf(UserException.class);
    }
  }

  @Test
  public void testClose() throws Exception {
    fsCache.close();
    verify(fsCache.cache, times(1)).invalidateAll();
    verify(fsCache.cache, times(1)).cleanUp();
  }

  @Test
  public void testPessimisticFSLocking() throws IOException, InterruptedException {
    when(mockOptionManager.getOption(RESTCATALOG_PLUGIN_FILE_SYSTEM_OPTIMISTIC_LOCKING))
        .thenReturn(false);

    AtomicInteger instantInvalidations = new AtomicInteger(0);
    // -1: no-op, 0: exception during the next invocation, 1: scheduled exception for 2nd invocation
    AtomicInteger scheduleExceptionDuringCacheLookup = new AtomicInteger(-1);

    doAnswer(
            invocationOnMock -> {
              if (scheduleExceptionDuringCacheLookup.get() == 0) {
                // throw now and unschedule throw for next lookup
                scheduleExceptionDuringCacheLookup.set(-1);
                throw new IOException("fake exception");
              } else if (scheduleExceptionDuringCacheLookup.get() == 1) {
                // schedule throw in next lookup
                scheduleExceptionDuringCacheLookup.set(0);
              }
              LockableHadoopFileSystem result =
                  (LockableHadoopFileSystem) invocationOnMock.callRealMethod();
              if (instantInvalidations.getAndDecrement() > 0) {
                fsCache.cache.invalidate(invocationOnMock.getArgument(0));
              }
              return result;
            })
        .when(fsCache.cache)
        .get(any(DatasetFileSystemCacheKey.class));

    // make 2 FS references to the same path (simulating 2 consumers)
    SelfManagingCachedFileSystem fs =
        (SelfManagingCachedFileSystem)
            fsCache.load(
                "file:///path1",
                SYSTEM_USERNAME,
                SYSTEM_ID,
                Lists.newArrayList("a", "b", "c"),
                null,
                false);

    SelfManagingCachedFileSystem fs2 =
        (SelfManagingCachedFileSystem)
            fsCache.load(
                "file:///path1",
                SYSTEM_USERNAME,
                SYSTEM_ID,
                Lists.newArrayList("a", "b", "c"),
                null,
                false);

    // 1st CASE: locking is guaranteed (expiration is 1 minute)
    // instant usage; will trigger cache miss
    fs.getScheme();
    FileSystem wrappedHadoopFs1 = fs.getWrappedLockableFs().getFs();

    // instance still valid -> these operations result in cache hits
    fs.supportsAsync();
    fs.supportsBoosting();
    assertTrue(
        "Expected no new FS instance creation",
        wrappedHadoopFs1 == fs.getWrappedLockableFs().getFs());

    // 2nd CASE: unlucky scenario
    // we acquire lock on an instance that is invalidated at the same time, but goes fine on 1st
    // retry
    instantInvalidations.set(1);
    fs2.getScheme();
    assertFalse(
        "Expected mismatch due to invalidation",
        wrappedHadoopFs1 == fs2.getWrappedLockableFs().getFs());

    // 3rd CASE: exhaust retries, unable to lock on ever-refreshing FS instance
    instantInvalidations.set(SelfManagingCachedFileSystem.MAX_LOCK_RETRIES + 1);
    try {
      fs2.getScheme();
      fail("Expected exception due to failed lock acquisition");
    } catch (UserException e) {
      assertTrue(
          e.getCause()
              .getMessage()
              .contains("Unable to acquire lock on freshly produced FS instance after"));
    }

    // 4th CASE: exception happens in cache.get() while we have a lock on an instance already
    SelfManagingCachedFileSystem fs3 =
        (SelfManagingCachedFileSystem)
            fsCache.load(
                "file:///path2",
                SYSTEM_USERNAME,
                SYSTEM_ID,
                Lists.newArrayList("a", "b", "d"),
                null,
                false);

    instantInvalidations.set(0);
    fs3.getScheme();

    // simulate exception
    instantInvalidations.set(1);
    scheduleExceptionDuringCacheLookup.set(1);
    try {
      fs3.getScheme();
    } catch (Exception e) {
      // no-op catching fake exception
    }
    // lock should be released even if exception happens
    assertThrows(IllegalStateException.class, () -> fs3.getWrappedLockableFs().unlock());

    // 5th CASE: after opening an outputstream, an FS should be considered IN USE
    // until that stream is closed
    File tempFile = File.createTempFile(this.getClass().getName(), "testfile");
    tempFile.deleteOnExit();

    SelfManagingCachedFileSystem streamTestFs =
        (SelfManagingCachedFileSystem)
            fsCache.load(
                tempFile.toURI().toString(),
                SYSTEM_USERNAME,
                SYSTEM_ID,
                Lists.newArrayList("a", "b", "c"),
                null,
                false);

    FSOutputStream os = streamTestFs.create(Path.of(tempFile.toURI()), true);
    assertEquals(1, streamTestFs.getWrappedLockableFs().getRefCount());

    // test timeout logic - invoking wait without anything closing the stream
    // true result = timeout has occured
    assertTrue(streamTestFs.getWrappedLockableFs().waitForClose(100));

    // test wait logic
    AtomicBoolean testFailureInThread = new AtomicBoolean(false);
    Thread t =
        new Thread(
            () -> {
              try {
                // no timeout
                assertFalse(streamTestFs.getWrappedLockableFs().waitForClose(10000));
                // no refs on this FS anymore
                assertEquals(0, streamTestFs.getWrappedLockableFs().getRefCount());
                // ensure no negative ref count..
                assertThrows(
                    IllegalStateException.class,
                    () -> streamTestFs.getWrappedLockableFs().unlock());
                // ..but otherwise overuse of close() causes no issues e.g.
                // PartitionStatsMetadataUtil#writeMetadata
                os.close();
                os.close();
              } catch (Throwable ex) {
                testFailureInThread.set(true);
                throw new RuntimeException(ex);
              }
            });
    t.start();

    os.close();
    assertEquals(0, streamTestFs.getWrappedLockableFs().getRefCount());

    t.join();
    assertFalse(testFailureInThread.get());
  }
}
