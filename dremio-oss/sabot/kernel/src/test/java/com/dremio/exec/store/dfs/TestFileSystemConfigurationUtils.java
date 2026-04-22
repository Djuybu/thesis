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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.exec.ExecConstants;
import com.dremio.options.OptionManager;
import org.apache.hadoop.conf.Configuration;
import org.junit.Test;

public class TestFileSystemConfigurationUtils {

  @Test
  public void testDefaultFSConfiguration() {
    OptionManager mockOptionManager;
    Configuration configuration;
    // default case (should not have any *-site-default.xml's loaded)
    mockOptionManager = mock(OptionManager.class);
    when(mockOptionManager.getOption(
            ExecConstants.FILESYSTEM_HADOOP_CONFIGURATION_PRELOAD_ALL_DEFAULTS))
        .thenReturn(false);
    configuration = FileSystemConfigurationUtils.getNewFsConf(mockOptionManager);
    assertFalse(configuration.toString().contains("default"));
    assertTrue(configuration.size() < 200);
    // legacy case (should have at least a core-site-default.xml loaded)
    mockOptionManager = mock(OptionManager.class);
    when(mockOptionManager.getOption(
            ExecConstants.FILESYSTEM_HADOOP_CONFIGURATION_PRELOAD_ALL_DEFAULTS))
        .thenReturn(true);
    configuration = FileSystemConfigurationUtils.getNewFsConf(mockOptionManager);
    assertTrue(configuration.toString().contains("default"));
    assertTrue(configuration.size() > 300);
  }
}
