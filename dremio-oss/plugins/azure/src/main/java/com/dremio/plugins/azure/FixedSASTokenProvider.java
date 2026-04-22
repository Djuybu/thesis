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
package com.dremio.plugins.azure;

import static com.dremio.plugins.azure.AzureStorageFileSystem.SAS_SIGNATURE;

import java.io.IOException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.azurebfs.contracts.exceptions.SASTokenProviderException;
import org.apache.hadoop.fs.azurebfs.extensions.SASTokenProvider;

/**
 * In house implementation of {@link SASTokenProvider} to use a fixed SAS token with ABFS. Use this
 * to avoid implementing a Custom Token Provider just to return fixed SAS. Fixed SAS Token to be
 * provided using the config "fs.azure.sas.fixed.token".
 *
 * <p>This class was originally derived from the Hadoop Common project. Since Hadoop 3.4 is still on
 * the horizon, we currently replicate it here. However, once 3.4 is released, we won't need to
 * maintain our own copy.
 */
public class FixedSASTokenProvider implements SASTokenProvider {
  private String fixedSASToken;

  FixedSASTokenProvider() {}

  public FixedSASTokenProvider(final String fixedSASToken) throws SASTokenProviderException {
    this.fixedSASToken = fixedSASToken;
    if (fixedSASToken == null || fixedSASToken.isEmpty()) {
      throw new SASTokenProviderException(
          String.format("Configured Fixed SAS Token is Invalid: %s", fixedSASToken));
    }
  }

  @Override
  public void initialize(final Configuration configuration, final String accountName)
      throws IOException {
    String account = accountName.split("\\.")[0];
    fixedSASToken = configuration.get(SAS_SIGNATURE + "." + account).split("\\?")[1];
  }

  /**
   * Returns the fixed SAS Token configured.
   *
   * @param account the name of the storage account.
   * @param fileSystem the name of the fileSystem.
   * @param path the file or directory path.
   * @param operation the operation to be performed on the path.
   * @return Fixed SAS Token
   */
  @Override
  public String getSASToken(
      final String account, final String fileSystem, final String path, final String operation) {
    return fixedSASToken;
  }
}
