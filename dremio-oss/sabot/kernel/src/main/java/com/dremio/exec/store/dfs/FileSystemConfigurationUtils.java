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
package com.dremio.exec.store.dfs;

import com.dremio.exec.ExecConstants;
import com.dremio.exec.hadoop.HadoopFileSystem;
import com.dremio.options.OptionManager;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;

public final class FileSystemConfigurationUtils {
  /**
   * Default {@link Configuration} instances. Use {@link #getNewFsConf(OptionManager)} ()} to create
   * new copies of {@link Configuration} objects. DEFAULT_CONFIGURATION_WITH_FS_DEFAULTS has no
   * defaults loaded other than those with "fs." prefix
   */
  private static final Configuration DEFAULT_CONFIGURATION_WITH_ALL_DEFAULTS;

  private static final Configuration DEFAULT_CONFIGURATION_WITH_FS_DEFAULTS;

  static {
    DEFAULT_CONFIGURATION_WITH_ALL_DEFAULTS = new Configuration();
    DEFAULT_CONFIGURATION_WITH_FS_DEFAULTS = new Configuration(false);
    DEFAULT_CONFIGURATION_WITH_ALL_DEFAULTS.set(
        HadoopFileSystem.HADOOP_SECURITY_CREDENTIAL_PROVIDER_PATH,
        HadoopFileSystem.DREMIO_CREDENTIAL_PROVIDER_PATH);
    DEFAULT_CONFIGURATION_WITH_FS_DEFAULTS.set(
        HadoopFileSystem.HADOOP_SECURITY_CREDENTIAL_PROVIDER_PATH,
        HadoopFileSystem.DREMIO_CREDENTIAL_PROVIDER_PATH);
    // new Configuration(false) will cause both the default and non-default conf files to be avoided
    // but we do need the non-defaults, e.g. should a user place a core-site.xml in Dremio conf dir
    //
    // There's no (public) way to list the static loaded resources from Configuration, so we'll add
    // them here instead:
    //
    // Configuration adds core-site.xml
    DEFAULT_CONFIGURATION_WITH_FS_DEFAULTS.addResource("core-site.xml");
    // JobConf adds mapred-site.xml and yarn-site.xml
    DEFAULT_CONFIGURATION_WITH_FS_DEFAULTS.addResource("yarn-site.xml");
    DEFAULT_CONFIGURATION_WITH_FS_DEFAULTS.addResource("mapred-site.xml");
    // HdfsConfiguration adds hdfs-site.xml, hdfs-rbf-site.xml
    DEFAULT_CONFIGURATION_WITH_FS_DEFAULTS.addResource("hdfs-site.xml");
    DEFAULT_CONFIGURATION_WITH_FS_DEFAULTS.addResource("hdfs-rbf-site.xml");
    // reducing the default hadoop properties size from ~1500 to ~130
    final String fsPrefix = "fs.";
    for (Map.Entry<String, String> property :
        DEFAULT_CONFIGURATION_WITH_ALL_DEFAULTS.getPropsWithPrefix(fsPrefix).entrySet()) {
      DEFAULT_CONFIGURATION_WITH_FS_DEFAULTS.set(fsPrefix + property.getKey(), property.getValue());
    }
  }

  public static Configuration getNewFsConf(OptionManager optionManager) {
    boolean isAllDefaultsNeeded;

    if (optionManager == null) {
      isAllDefaultsNeeded =
          ExecConstants.FILESYSTEM_HADOOP_CONFIGURATION_PRELOAD_ALL_DEFAULTS
              .getDefault()
              .getBoolVal();
    } else {
      isAllDefaultsNeeded =
          optionManager.getOption(
              ExecConstants.FILESYSTEM_HADOOP_CONFIGURATION_PRELOAD_ALL_DEFAULTS);
    }
    return isAllDefaultsNeeded
        ? new Configuration(DEFAULT_CONFIGURATION_WITH_ALL_DEFAULTS)
        : new Configuration(DEFAULT_CONFIGURATION_WITH_FS_DEFAULTS);
  }
}
