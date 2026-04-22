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
package com.dremio.exec.catalog.conf;

public class AzureStorageConfProperties {
  public static final String ACCOUNT = "dremio.azure.account";
  public static final String KEY = "dremio.azure.key";
  public static final String MODE = "dremio.azure.mode";
  public static final String SECURE = "dremio.azure.secure";
  public static final String ROOT_PATH = "dremio.azure.rootPath";
  public static final String CREDENTIALS_TYPE = "dremio.azure.credentialsType";
  public static final String CLIENT_ID = "dremio.azure.clientId";
  public static final String TOKEN_ENDPOINT = "dremio.azure.tokenEndpoint";
  public static final String CLIENT_SECRET = "dremio.azure.clientSecret";
  public static final String AZURE_SHAREDKEY_SIGNER_TYPE = "fs.azure.sharedkey.signer.type";
}
