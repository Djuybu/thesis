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
package com.dremio.provision.yarn.service;

import com.dremio.provision.ClusterType;
import com.dremio.provision.DistroType;
import com.dremio.provision.service.DistroSecurityKey;
import com.dremio.provision.service.DistroTypeConfigurator;
import com.dremio.provision.service.ProvisioningDefaultsConfigurator;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Class to keep templates for different distros defaults */
public class YarnDefaultsConfigurator implements ProvisioningDefaultsConfigurator {
  public static final String JAVA_LOGIN = "java.security.auth.login.config";
  public static final String ZK_SASL_CLIENT = "zookeeper.client.sasl";
  public static final String ZK_SASL_CLIENT_CONFIG = "zookeeper.sasl.clientconfig";
  public static final String ZK_SASL_PROVIDER = "zookeeper.saslprovider";
  public static final String SPILL_PATH = "paths.spilling";
  public static final String CLASSPATH_JARS = "default.classpath.jars";

  private static Map<String, Boolean> yarnDefaultNames =
      ImmutableMap.of(
          JAVA_LOGIN, false,
          ZK_SASL_CLIENT, false,
          ZK_SASL_CLIENT_CONFIG, false,
          ZK_SASL_PROVIDER, false,
          SPILL_PATH, true);

  private static List<YarnConfiguratorBaseClass> yarnConfiguratorClasses =
      ImmutableList.of(new BaseYarnDefaults(), new BaseYarnDefaults.BaseYarnDefaultsSecurityOn());

  private Map<DistroSecurityKey, YarnConfigurator> yarnConfigurators = Maps.newHashMap();

  public YarnDefaultsConfigurator() {
    for (YarnConfigurator yarnConfigurator : yarnConfiguratorClasses) {
      EnumSet<DistroType> supportedTypes = yarnConfigurator.getSupportedTypes();
      boolean isSecurityOn = yarnConfigurator.isSecure();
      for (DistroType dType : supportedTypes) {
        yarnConfigurators.put(new DistroSecurityKey(dType, isSecurityOn), yarnConfigurator);
      }
    }
  }

  @Override
  public ClusterType getType() {
    return ClusterType.YARN;
  }

  @Override
  public Set<String> getDefaultPropertiesNames() {
    return yarnDefaultNames.keySet();
  }

  @Override
  public DistroTypeConfigurator getDistroTypeDefaultsConfigurator(
      final DistroType dType, boolean isSecurityOn) {
    final DistroTypeConfigurator distroConfigurator =
        yarnConfigurators.get(new DistroSecurityKey(dType, isSecurityOn));
    if (distroConfigurator == null) {
      throw new IllegalArgumentException(
          "Unsupported combination of DistroType: " + dType + " and security: " + isSecurityOn);
    }
    return distroConfigurator;
  }

  private static ImmutableMap<String, String> createNettyDefaultProps() {
    ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();

    // netty property to use the JVM's accounting for direct allocations.
    builder.put("io.netty.maxDirectMemory", "0");

    return builder.build();
  }

  /** Class to keep base YARN defaults */
  public static class BaseYarnDefaults extends YarnConfiguratorBaseClass {

    private static Map<String, String> baseYarnDefaultPropsSecurityOn =
        ImmutableMap.of(SPILL_PATH, "[\"file:///tmp/dremio/spill\"]");
    private static Map<String, String> baseYarnDefaultPropsSecurityOff =
        ImmutableMap.of(SPILL_PATH, "[\"file:///tmp/dremio/spill\"]");

    private static Map<String, String> baseYarnDefaultPropsSecurityOnShow =
        ImmutableMap.of(SPILL_PATH, "[\"file:///tmp/dremio/spill\"]");
    private static Map<String, String> baseYarnDefaultPropsSecurityOffShow =
        ImmutableMap.of(SPILL_PATH, "[\"file:///tmp/dremio/spill\"]");

    private EnumSet<DistroType> supportedTypes =
        EnumSet.of(DistroType.APACHE, DistroType.CDH, DistroType.HDP, DistroType.OTHER);

    @Override
    public EnumSet<DistroType> getSupportedTypes() {
      return supportedTypes;
    }

    @Override
    public boolean isSecure() {
      return false;
    }

    @Override
    public Map<String, String> getAllDefaults() {
      return ImmutableMap.<String, String>builder()
          .putAll(baseYarnDefaultPropsSecurityOff)
          .putAll(createNettyDefaultProps())
          .build();
    }

    @Override
    public Map<String, String> getAllToShowDefaults() {
      return baseYarnDefaultPropsSecurityOffShow;
    }

    private static final class BaseYarnDefaultsSecurityOn extends BaseYarnDefaults {
      @Override
      public boolean isSecure() {
        return true;
      }

      @Override
      public Map<String, String> getAllDefaults() {
        return ImmutableMap.<String, String>builder()
            .putAll(baseYarnDefaultPropsSecurityOn)
            .putAll(createNettyDefaultProps())
            .build();
      }

      @Override
      public Map<String, String> getAllToShowDefaults() {
        return baseYarnDefaultPropsSecurityOnShow;
      }
    }
  }
}
