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
package com.dremio.plugins.util.awsauth;

import com.google.common.base.Suppliers;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.s3a.Constants;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;

/** Implementation of {@link AwsCredentialsProvider} to support REST session tokens. */
public class DremioSessionCredentialsProviderV2 implements AwsCredentialsProvider {

  private final Configuration config;
  private final Supplier<AwsCredentials> credentialsSupplier;

  public DremioSessionCredentialsProviderV2(final Configuration config) {
    this.config = config;
    this.credentialsSupplier =
        Suppliers.memoizeWithExpiration(this::resolveCredentialsInternal, 5, TimeUnit.MINUTES);
  }

  @Override
  public AwsCredentials resolveCredentials() {
    return credentialsSupplier.get();
  }

  private AwsCredentials resolveCredentialsInternal() {
    return AwsSessionCredentials.create(
        config.get(Constants.ACCESS_KEY),
        config.get(Constants.SECRET_KEY),
        config.get(Constants.SESSION_TOKEN));
  }
}
