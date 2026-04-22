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
package com.dremio.plugins.gcs;

import com.google.cloud.hadoop.util.AccessTokenProvider;
import java.io.IOException;
import org.apache.hadoop.conf.Configuration;

public class GCSAccessTokenProvider implements AccessTokenProvider {
  private Configuration conf;
  private String accessToken;
  private Long expirationTime;

  public GCSAccessTokenProvider() {}

  @Override
  public AccessToken getAccessToken() {
    return new AccessToken(accessToken, expirationTime);
  }

  @Override
  public void refresh() throws IOException {
    // Do nothing, token has to be updated based on configuration change
  }

  @Override
  public void setConf(Configuration conf) {
    this.conf = conf;
    this.accessToken = conf.get(GoogleBucketFileSystem.DREMIO_OAUTH2_TOKEN);
    this.expirationTime =
        Long.parseLong(conf.get(GoogleBucketFileSystem.DREMIO_OAUTH2_TOKEN_EXPIRY));
  }

  @Override
  public Configuration getConf() {
    return this.conf;
  }
}
