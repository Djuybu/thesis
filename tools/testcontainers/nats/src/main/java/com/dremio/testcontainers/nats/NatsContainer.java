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
package com.dremio.testcontainers.nats;

import com.dremio.testcontainers.DremioContainer;
import com.dremio.testcontainers.DremioTestcontainersUsageValidator;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

public final class NatsContainer extends GenericContainer<NatsContainer>
    implements DremioContainer {
  private static final DockerImageName IMAGE = DockerImageName.parse("nats:2.10.24-alpine3.21");
  private static final int NATS_PORT = 4222;

  public NatsContainer() {
    super(IMAGE);
    setCommand("-js");
    addExposedPort(NATS_PORT);
  }

  public String getConnectionString() {
    return String.format("nats://%s:%d", getHost(), getMappedPort(NATS_PORT));
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
}
