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
package com.dremio.testcontainers.opensearch;

import com.dremio.testcontainers.DremioContainer;
import com.dremio.testcontainers.DremioTestcontainersUsageValidator;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.message.BasicHeader;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.opensearch.testcontainers.OpensearchContainer;
import org.testcontainers.utility.DockerImageName;

public final class OpenSearchContainer extends OpensearchContainer<OpenSearchContainer>
    implements DremioContainer {
  private static final DockerImageName IMAGE =
      DockerImageName.parse("opensearchproject/opensearch:2.19.2");
  private static final Base64.Encoder BASE64_ENCODER = Base64.getEncoder();

  public OpenSearchContainer() {
    super(IMAGE);
  }

  @Override
  public void start() {
    DremioTestcontainersUsageValidator.validate();
    super.start();
  }

  @Override
  public void setDockerImageName(String dockerImageName) {
    throw new UnsupportedOperationException("Docker image name can not be changed");
  }

  public OpenSearchClient buildClient() {
    URI uri;
    try {
      uri = new URI(super.getHttpHostAddress());
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
    HttpHost host = new HttpHost(uri.getScheme(), uri.getHost(), uri.getPort());
    ApacheHttpClient5TransportBuilder builder = ApacheHttpClient5TransportBuilder.builder(host);
    builder.setHttpClientConfigCallback(
        httpClientBuilder ->
            httpClientBuilder.addRequestInterceptorLast(
                (request, details, context) -> addAuthorizationHeader(request)));
    return new OpenSearchClient(builder.build());
  }

  private void addAuthorizationHeader(HttpRequest request) {
    String credentials =
        BASE64_ENCODER.encodeToString(
            String.format("%s:%s", getUsername(), getPassword()).getBytes(StandardCharsets.UTF_8));
    request.addHeader(new BasicHeader("Authorization", "Basic " + credentials));
  }
}
