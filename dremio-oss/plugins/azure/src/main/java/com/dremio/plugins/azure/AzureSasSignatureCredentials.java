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

import com.google.common.base.Preconditions;
import com.microsoft.azure.storage.StorageCredentials;
import com.microsoft.azure.storage.StorageCredentialsSharedAccessSignature;
import java.io.IOException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.azurebfs.extensions.SASTokenProvider;

public class AzureSasSignatureCredentials implements AzureAuthTokenProvider, SASTokenProvider {

  private String account;
  private Configuration conf;

  public AzureSasSignatureCredentials() {}

  public StorageCredentials getStorageCredentials(String account) {
    final String storageAccount = this.account != null ? this.account : account;
    return new StorageCredentialsSharedAccessSignature(
        conf.get(SAS_SIGNATURE + "." + storageAccount));
  }

  @Override
  public boolean checkAndUpdateToken() {
    return false;
  }

  @Override
  public String getSasSignature(final boolean withPath) {
    String sasSignatureWithPath = conf.get(SAS_SIGNATURE + "." + account);
    if (withPath) {
      return sasSignatureWithPath;
    } else {
      return "?" + sasSignatureWithPath.split("\\?")[1];
    }
  }

  @Override
  public void initialize(Configuration conf, String account) throws IOException {
    Preconditions.checkNotNull(conf);
    this.conf = conf;
    this.account = account.split("\\.")[0];
  }

  @Override
  public String getSASToken(String account, String fileSystem, String path, String operation) {
    return conf.get(SAS_SIGNATURE + "." + account.split("\\.")[0]);
  }
}
