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

public class GcsStorageConfProperties {
  public static final String DREMIO_PROJECT_ID = "dremio.gcs.projectId";
  public static final String DREMIO_KEY_FILE = "dremio.gcs.use_keyfile";
  public static final String DREMIO_CLIENT_EMAIL = "dremio.gcs.clientEmail";
  public static final String DREMIO_CLIENT_ID = "dremio.gcs.clientId";
  public static final String DREMIO_PRIVATE_KEY_ID = "dremio.gcs.privateKeyId";
  public static final String DREMIO_PRIVATE_KEY = "dremio.gcs.privateKey";
}
