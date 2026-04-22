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
package com.dremio.plugins.s3.store;

import com.dremio.plugins.util.CloseableRef;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3BaseClientBuilder;
import software.amazon.awssdk.services.s3.S3Client;

public class S3ClientFactory {
  private final S3ClientProperties s3ClientProperties;

  public S3ClientFactory(S3ClientProperties s3ClientProperties) {
    this.s3ClientProperties = s3ClientProperties;
  }

  public CloseableRef<S3Client> createS3Client() {
    return new CloseableRef<>(
        S3Client.builder()
            .applyMutation(this::applyCommonConfigurations)
            .applyMutation(s3ClientProperties::applyEndpointConfigurations)
            .applyMutation(s3ClientProperties::applyCrossRegionAccessConfigurations)
            .applyMutation(s3ClientProperties::applyHttpClientConfigurations)
            .applyMutation(
                builder ->
                    s3ClientProperties.applyClientRegionConfigurations(builder, Optional.empty()))
            .build());
  }

  public CloseableRef<S3AsyncClient> createS3AsyncClient(
      ExecutorService executorService, Optional<Region> region) {
    return new CloseableRef<>(
        S3AsyncClient.builder()
            .applyMutation(this::applyCommonConfigurations)
            .applyMutation(s3ClientProperties::applyAsyncEndpointConfigurations)
            .applyMutation(s3ClientProperties::applyAsyncHttpClientConfigurations)
            .applyMutation(
                builder -> s3ClientProperties.applyAsyncConfigurations(builder, executorService))
            .applyMutation(
                builder -> s3ClientProperties.applyClientRegionConfigurations(builder, region))
            .build());
  }

  private <T extends S3BaseClientBuilder<?, ?>> void applyCommonConfigurations(T builder) {
    builder
        .applyMutation(s3ClientProperties::applyClientCredentialConfigurations)
        .applyMutation(s3ClientProperties::applyServiceConfigurations)
        .applyMutation(s3ClientProperties::applyUserAgentConfigurations)
        .applyMutation(s3ClientProperties::applyApiCallTimeoutConfigurations)
        .applyMutation(s3ClientProperties::applyRequesterPaysConfigurations);
  }
}
