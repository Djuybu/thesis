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
package com.dremio.testcontainers.dockerclient;

import com.dremio.common.SuppressForbidden;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.dockerclient.DockerClientProviderStrategy;
import org.testcontainers.dockerclient.EnvironmentAndSystemPropertyClientProviderStrategy;
import org.testcontainers.dockerclient.InvalidConfigurationException;
import org.testcontainers.dockerclient.TransportConfig;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.JsonNode;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.MappingIterator;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;
import org.testcontainers.shaded.org.apache.commons.lang3.SystemUtils;
import org.testcontainers.utility.CommandLine;

/**
 * Provider strategy for Colima
 *
 * <p>Only tested on MacOS. Check for colima executable and socket at ~/.colima/default.
 *
 * @deprecated this class is used by the SPI and should not be used directly
 */
@Deprecated
@SuppressForbidden
// Following testcontainers convention
// CHECKSTYLE:OFF ConstantName
public class ColimaClientProviderStrategy extends DockerClientProviderStrategy {
  private static final Logger log = LoggerFactory.getLogger(ColimaClientProviderStrategy.class);

  private static final ObjectMapper objectMapper = new ObjectMapper();

  private static final String DEFAULT_PROFILE_NAME = "default";

  public static final int PRIORITY =
      EnvironmentAndSystemPropertyClientProviderStrategy.PRIORITY - 10;

  @Nullable private final Path socketPath = resolveSocketPath();

  private final boolean colimaExists = resolveColimaExists();

  private Path getSocketPath() {
    return socketPath;
  }

  private Path resolveSocketPath() {
    Path macosPath = Paths.get(System.getProperty("user.home"), ".colima", DEFAULT_PROFILE_NAME);
    return tryFolder(macosPath).orElse(null);
  }

  private boolean resolveColimaExists() {
    return CommandLine.executableExists("colima");
  }

  @Override
  public String getDescription() {
    return "Docker accessed via Colima unix socket ( " + this.socketPath + ")";
  }

  @Override
  public TransportConfig getTransportConfig() throws InvalidConfigurationException {
    return TransportConfig.builder()
        .dockerHost(URI.create("unix://" + getSocketPath().toString()))
        .build();
  }

  @Override
  public synchronized String getDockerHostIpAddress() {
    String listInstances = CommandLine.runShellCommand("colima", "list", "-j");
    String address = null;
    try (MappingIterator<JsonNode> it =
        objectMapper.readerFor(JsonNode.class).readValues(listInstances)) {
      while (it.hasNext()) {
        JsonNode node = it.next();
        String instanceName = node.get("name").asText();

        if (!DEFAULT_PROFILE_NAME.equals(instanceName)) {
          continue;
        }

        address = node.get("address").asText();
        // Stop after first match
        break;
      }
    } catch (IOException e) {
      throw new RuntimeException("Could not parse colima output", e);
    }
    return address;
  }

  @Override
  public String getRemoteDockerUnixSocketPath() {
    return "/var/run/docker.sock";
  }

  @Override
  protected int getPriority() {
    return PRIORITY;
  }

  @Override
  protected boolean isPersistable() {
    return false;
  }

  @Override
  protected boolean isApplicable() {
    return SystemUtils.IS_OS_MAC && this.colimaExists && this.socketPath != null;
  }

  private Optional<Path> tryFolder(Path path) {
    if (!Files.exists(path)) {
      log.debug("'{}' does not exist.", path);
      return Optional.empty();
    }
    Path socketPath = path.resolve("docker.sock");
    if (!Files.exists(socketPath)) {
      log.debug("'{}' does not exist.", socketPath);
      return Optional.empty();
    }
    return Optional.of(socketPath);
  }
}
