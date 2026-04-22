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
package com.dremio.testcontainers.zookeeper;

import com.dremio.testcontainers.DremioContainer;
import com.dremio.testcontainers.DremioTestcontainersUsageValidator;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

public final class ZookeeperContainer extends GenericContainer<ZookeeperContainer>
    implements DremioContainer {
  private static final DockerImageName IMAGE = DockerImageName.parse("zookeeper:3.8.4-jre-17");
  private static final int ZK_PORT = 2181;

  public ZookeeperContainer() {
    super(IMAGE);
    addExposedPort(ZK_PORT);
  }

  public String getConnectionString() {
    return String.format("%s:%d", getHost(), getMappedPort(ZK_PORT));
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
