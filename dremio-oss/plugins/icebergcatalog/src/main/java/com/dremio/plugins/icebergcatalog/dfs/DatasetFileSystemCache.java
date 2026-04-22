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

import static com.dremio.exec.store.IcebergCatalogPluginOptions.RESTCATALOG_PLUGIN_FILE_SYSTEM_EXPIRE_AFTER_WRITE_MINUTES;
import static com.dremio.exec.store.IcebergCatalogPluginOptions.RESTCATALOG_PLUGIN_FILE_SYSTEM_OPTIMISTIC_LOCKING;
import static com.dremio.exec.store.hive.exec.FileSystemConfUtil.AZURE_FILE_SYSTEM;
import static com.dremio.exec.store.hive.exec.FileSystemConfUtil.GCS_FILE_SYSTEM;
import static com.dremio.exec.store.hive.exec.FileSystemConfUtil.S3_FILE_SYSTEM;
import static com.dremio.io.file.UriSchemes.DREMIO_AZURE_SCHEME;
import static com.dremio.io.file.UriSchemes.DREMIO_GCS_SCHEME;
import static com.dremio.io.file.UriSchemes.DREMIO_S3_SCHEME;
import static com.dremio.service.users.SystemUser.SYSTEM_USERNAME;

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.hadoop.HadoopFileSystem;
import com.dremio.exec.store.hive.exec.FileSystemConfUtil;
import com.dremio.io.file.Path;
import com.dremio.options.OptionManager;
import com.dremio.sabot.exec.context.OperatorStats;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.PrivilegedExceptionAction;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.ReflectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** This class is wrapper for the cache which holds the FileSystem objects */
public class DatasetFileSystemCache implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(DatasetFileSystemCache.class);
  private static final int MAX_HOURS_WAIT_FOR_FS_WRITE_LOCK = 4;

  @VisibleForTesting
  protected LoadingCache<DatasetFileSystemCacheKey, LockableHadoopFileSystem> cache;

  private final Function<List<String>, Configuration> fsConfProvider;
  private final OptionManager optionManager;

  public DatasetFileSystemCache(
      Function<List<String>, Configuration> fsConfProvider, OptionManager optionManager) {
    this.fsConfProvider = fsConfProvider;
    this.optionManager = optionManager;
  }

  @VisibleForTesting
  synchronized void initCache() {
    if (cache != null) {
      return;
    }
    this.cache =
        buildCacheExpiration(Caffeine.newBuilder(), optionManager)
            .removalListener(
                (key, lockableFs, cause) -> {
                  if (lockableFs != null) {
                    try {
                      if (lockableFs.waitForClose(
                          TimeUnit.HOURS.toMillis(MAX_HOURS_WAIT_FOR_FS_WRITE_LOCK))) {
                        logger.warn("Timed out waiting for FS closure on {}", lockableFs);
                      }
                      logger.debug("Closing FS instance {} on cache removal.", lockableFs.getFs());
                      lockableFs.getFs().close();
                    } catch (IOException e) {
                      // Ignore
                      logger.error("Unable to clean FS from HadoopFileSystemCache", e);
                    } catch (InterruptedException e) {
                      // Ignore
                      logger.error(
                          "Interrupted while waiting for FS to be de-referenced for closure.", e);
                    }
                  }
                })
            .build(
                key -> {
                  final UserGroupInformation loginUser = UserGroupInformation.getLoginUser();
                  final UserGroupInformation ugi;
                  if (key.getUserName().equals(loginUser.getUserName())
                      || SYSTEM_USERNAME.equals(key.getUserName())) {
                    ugi = loginUser;
                  } else {
                    ugi = UserGroupInformation.createProxyUser(key.getUserName(), loginUser);
                  }

                  Configuration fsConf = fsConfProvider.apply(key.getDataset());
                  URI uri = injectDremioFsImpl(key.getUri(), fsConf);
                  String scheme = Optional.ofNullable(uri.getScheme()).orElse("");

                  final PrivilegedExceptionAction<LockableHadoopFileSystem> fsFactory =
                      () -> {
                        // Do not use FileSystem#newInstance(Configuration) as it adds filesystem
                        // into the Hadoop cache :(
                        // Mimic instead Hadoop FileSystem#createFileSystem() method
                        final Class<? extends FileSystem> fsClass =
                            FileSystem.getFileSystemClass(scheme, fsConf);
                        final FileSystem fs = ReflectionUtils.newInstance(fsClass, fsConf);
                        fs.initialize(uri, fsConf);
                        return new LockableHadoopFileSystem(fs);
                      };

                  try {
                    return ugi.doAs(fsFactory);
                  } catch (IOException | InterruptedException e) {
                    logger.error(
                        "Failed to create FileSystem for path: {} with user: {}",
                        key.getUri(),
                        key.getUserName(),
                        e);
                    throw UserException.ioExceptionError(e).buildSilently();
                  } catch (Exception e) {
                    logger.error(
                        "Failed to create FileSystem for path: {} with user: {}",
                        key.getUri(),
                        key.getUserName(),
                        e);
                    throw UserException.validationError(e)
                        .message(
                            "Credentials for the Storage Provider must be valid and have required access")
                        .buildSilently();
                  }
                });
  }

  protected Caffeine<DatasetFileSystemCacheKey, LockableHadoopFileSystem> buildCacheExpiration(
      Caffeine builder, OptionManager optionManager) {
    long expirationMinutes =
        optionManager.getOption(RESTCATALOG_PLUGIN_FILE_SYSTEM_EXPIRE_AFTER_WRITE_MINUTES);
    Preconditions.checkState(expirationMinutes > 0, "FS cache expiration must not be 0");

    return builder.expireAfterWrite(expirationMinutes, TimeUnit.MINUTES);
  }

  private static URI injectDremioFsImpl(URI uri, Configuration conf) {
    try {
      URI modifiedURI = uri;
      String scheme = uri.getScheme();
      if (scheme == null || "file".equalsIgnoreCase(scheme)) {
        modifiedURI = HadoopFileSystem.getLocal(conf).makeQualified(Path.of(uri)).toURI();
      } else {
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (S3_FILE_SYSTEM.contains(scheme)) {
          // The authority duplication here is intentional, it will break if removed.
          modifiedURI =
              new URI(
                  DREMIO_S3_SCHEME,
                  uri.getRawAuthority(),
                  "/" + uri.getRawAuthority() + uri.getPath(),
                  uri.getQuery(),
                  uri.getFragment());
        } else if (AZURE_FILE_SYSTEM.contains(scheme)) {
          modifiedURI =
              new URI(
                  DREMIO_AZURE_SCHEME,
                  uri.getRawAuthority(),
                  "/" + uri.getUserInfo() + uri.getPath(),
                  uri.getQuery(),
                  uri.getFragment());
          conf.set("old_scheme", scheme);
          conf.set("authority", uri.getRawAuthority());
        } else if (GCS_FILE_SYSTEM.contains(scheme)) {
          modifiedURI =
              new URI(
                  DREMIO_GCS_SCHEME,
                  uri.getRawAuthority(),
                  "/" + uri.getRawAuthority() + uri.getPath(),
                  uri.getQuery(),
                  uri.getFragment());
        }
        // else if: HDFS: no URI manipulation required
        FileSystemConfUtil.initializeConfiguration(modifiedURI, conf);
      }
      return modifiedURI;
    } catch (IOException | URISyntaxException e) {
      throw UserException.ioExceptionError(e).buildSilently();
    }
  }

  public com.dremio.io.file.FileSystem load(
      String filePath,
      String userName,
      String userId,
      List<String> dataset,
      OperatorStats stats,
      boolean isAsyncEnabled) {
    if (cache == null) {
      initCache();
    }

    Path path = Path.of(filePath);
    URI originalUri = path.toURI();
    DatasetFileSystemCacheKey key =
        new DatasetFileSystemCacheKey(
            originalUri, userName, isCachingPerDataset() ? dataset : null);

    boolean optimisticLocking =
        optionManager.getOption(RESTCATALOG_PLUGIN_FILE_SYSTEM_OPTIMISTIC_LOCKING);

    return new SelfManagingCachedFileSystem(key, cache, stats, isAsyncEnabled, optimisticLocking);
  }

  /**
   * Whether to store FileSystem instances on a per dataset (i.e. table) basis
   *
   * @return the setting
   */
  protected boolean isCachingPerDataset() {
    return false;
  }

  @Override
  public void close() throws Exception {
    // Empty cache
    cache.invalidateAll();
    cache.cleanUp();
  }
}
