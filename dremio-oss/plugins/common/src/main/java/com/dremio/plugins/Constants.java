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
package com.dremio.plugins;

public class Constants {

  // Temporary credentials like AWS STS or Azure SAS token might not have privileges to list buckets
  // and get bucket metadata (bucket discovery).
  // This option is used to disable bucket discovery in such cases.
  public static final String DREMIO_ENABLE_BUCKET_DISCOVERY = "dremio.bucket.discovery.enabled";
}
