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

import com.dremio.common.exceptions.UserException;
import com.dremio.common.util.DremioVersionInfo;
import com.dremio.io.file.UriSchemes;
import com.dremio.plugins.util.AwsCredentialProviderUtils;
import com.dremio.plugins.util.S3PluginUtils;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.s3a.Constants;
import org.apache.iceberg.aws.AwsClientProperties;
import org.apache.iceberg.aws.s3.S3FileIOProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.client.builder.AwsAsyncClientBuilder;
import software.amazon.awssdk.awscore.client.builder.AwsClientBuilder;
import software.amazon.awssdk.awscore.client.builder.AwsSyncClientBuilder;
import software.amazon.awssdk.core.client.builder.SdkAsyncClientBuilder;
import software.amazon.awssdk.core.client.builder.SdkClientBuilder;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.client.config.SdkAdvancedAsyncClientOption;
import software.amazon.awssdk.core.client.config.SdkAdvancedClientOption;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClientBuilder;
import software.amazon.awssdk.services.s3.S3BaseClientBuilder;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

public class S3ClientProperties {
  private static final Logger logger = LoggerFactory.getLogger(S3ClientProperties.class);

  public enum PropertyName {
    PATH_STYLE_ACCESS,
    CROSS_REGION_ACCESS,
    SECURE_CONNECTIONS,
    REQUESTER_PAYS,
    REGION_OVERRIDE,
    ENDPOINT_OVERRIDE,
    API_CALL_TIMEOUT_IN_MILLIS
  }

  /**
   * A mapping of {@link PropertyName} enum values to their corresponding configuration property
   * names from various sources, including:
   *
   * <ul>
   *   <li>{@link org.apache.hadoop.fs.s3a.Constants} — core Hadoop S3A property constants
   *   <li>{@link org.apache.iceberg.aws.s3.S3FileIOProperties} and {@link
   *       org.apache.iceberg.aws.AwsClientProperties} — Iceberg AWS integration properties
   *   <li>{@link com.dremio.plugins.s3.store.S3FileSystem} and other internal Dremio-specific
   *       configuration keys
   * </ul>
   *
   * <p>This mapping is used to resolve logical property names (e.g., {@code PATH_STYLE_ACCESS}) to
   * one or more possible underlying property keys that may be defined across different
   * configuration layers.
   *
   * <p>Used primarily for normalization and backward compatibility when interpreting S3-related
   * configuration.
   *
   * <p>The order of property names in each array determines their precedence when resolving values
   * in {@link #validateAndGetPropertyNameToUse(Configuration, String...)}.
   */
  public static final ImmutableMap<PropertyName, String[]> PROPERTY_NAMES =
      // spotless:off
      ImmutableMap.<PropertyName, String[]>builder()
          .put(
              PropertyName.PATH_STYLE_ACCESS,
              new String[] {
                  Constants.PATH_STYLE_ACCESS,
                  S3FileIOProperties.PATH_STYLE_ACCESS})
          .put(
              PropertyName.CROSS_REGION_ACCESS,
              new String[] {
                  S3FileIOProperties.CROSS_REGION_ACCESS_ENABLED})
          .put(
              PropertyName.SECURE_CONNECTIONS,
              new String[] {
                  Constants.SECURE_CONNECTIONS})
          .put(
              PropertyName.REQUESTER_PAYS,
              new String[] {
                  Constants.ALLOW_REQUESTER_PAYS})
          .put(
              PropertyName.REGION_OVERRIDE,
              new String[] {
                  S3FileSystem.REGION_OVERRIDE,
                  Constants.AWS_REGION,
                  AwsClientProperties.CLIENT_REGION})
          .put(
              PropertyName.ENDPOINT_OVERRIDE,
              new String[] {
                  Constants.ENDPOINT,
                  S3FileIOProperties.ENDPOINT})
          .put(
              PropertyName.API_CALL_TIMEOUT_IN_MILLIS,
              new String[] {
                  Constants.REQUEST_TIMEOUT})
          .build();
      // spotless:on

  public static final boolean PATH_STYLE_ACCESS_DEFAULT = false;
  public static final boolean CROSS_REGION_ACCESS_DEFAULT = true;
  public static final boolean SECURE_CONNECTIONS_DEFAULT = true;
  public static final boolean REQUESTER_PAYS_DEFAULT = true;
  public static final String REGION_OVERRIDE_DEFAULT = null;
  public static final String ENDPOINT_OVERRIDE_DEFAULT = null;
  public static final String USER_AGENT_PREFIX_DEFAULT = "Dremio " + DremioVersionInfo.getVersion();
  public static final long API_CALL_TIMEOUT_IN_MILLIS_DEFAULT = 60_000L;
  public static final Duration API_CALL_TIMEOUT_DURATION_DEFAULT =
      Duration.ofMillis(API_CALL_TIMEOUT_IN_MILLIS_DEFAULT);

  public static final Duration API_CALL_TIMEOUT_WARN_LIMIT = Duration.ofMinutes(5);

  public static final String REQUESTER_PAYS_HEADER = "x-amz-request-payer";
  public static final String REQUESTER_PAYS_HEADER_VALUE = "requester";

  private final Configuration config;
  private final boolean isPathStyleAccess;
  private final boolean isCrossRegionAccess;
  private final boolean isSecureConnections;
  private final boolean isRequesterPays;
  private final String endpointOverride;
  private final String regionOverride;
  private final Duration apiCallTimeout;

  S3ClientProperties(Configuration config) {
    this.config = config;

    isPathStyleAccess =
        validateAndGetBooleanFromConfig(
            config, PATH_STYLE_ACCESS_DEFAULT, PROPERTY_NAMES.get(PropertyName.PATH_STYLE_ACCESS));

    isCrossRegionAccess =
        validateAndGetBooleanFromConfig(
            config,
            CROSS_REGION_ACCESS_DEFAULT,
            PROPERTY_NAMES.get(PropertyName.CROSS_REGION_ACCESS));

    isSecureConnections =
        validateAndGetBooleanFromConfig(
            config,
            SECURE_CONNECTIONS_DEFAULT,
            PROPERTY_NAMES.get(PropertyName.SECURE_CONNECTIONS));

    isRequesterPays =
        validateAndGetBooleanFromConfig(
            config, REQUESTER_PAYS_DEFAULT, PROPERTY_NAMES.get(PropertyName.REQUESTER_PAYS));

    regionOverride =
        validateAndGetStringFromConfig(
            config, REGION_OVERRIDE_DEFAULT, PROPERTY_NAMES.get(PropertyName.REGION_OVERRIDE));

    endpointOverride =
        validateAndGetStringFromConfig(
            config, ENDPOINT_OVERRIDE_DEFAULT, PROPERTY_NAMES.get(PropertyName.ENDPOINT_OVERRIDE));

    apiCallTimeout =
        Duration.ofMillis(
            validateAndGetTimeDurationInMillisFromConfig(
                config,
                API_CALL_TIMEOUT_IN_MILLIS_DEFAULT,
                PROPERTY_NAMES.get(PropertyName.API_CALL_TIMEOUT_IN_MILLIS)));
  }

  static String validateAndGetPropertyNameToUse(Configuration config, String... propertyNames) {
    Preconditions.checkArgument(
        propertyNames.length > 0,
        "validateAndGetPropertyNameToUse needs at least one propertyName to retrieve from the config.");

    String value = null;
    String propertyName = null;
    boolean mismatchExists = false;

    for (String currentPropertyName : propertyNames) {
      String currentValue = config.getTrimmed(currentPropertyName);

      if (currentValue == null || currentValue.isEmpty()) {
        continue;
      }

      if (value == null) {
        value = currentValue;
        propertyName = currentPropertyName;
        continue;
      }

      if (!value.equals(currentValue)) {
        mismatchExists = true;
        logger.warn(
            "Inconsistent values for S3Client properties. {} = {}, {} = {}",
            propertyName,
            value,
            currentPropertyName,
            currentValue);
      }
    }

    if (mismatchExists) {
      logger.warn(
          "There was a mismatch in property values for {}. {} = {} was used",
          Arrays.toString(propertyNames),
          propertyName,
          value);
    }

    return propertyName;
  }

  static boolean validateAndGetBooleanFromConfig(
      Configuration config, boolean defaultValue, String... propertyNames) {
    String propertyNameToUse = validateAndGetPropertyNameToUse(config, propertyNames);
    if (propertyNameToUse == null) {
      return defaultValue;
    }

    return config.getBoolean(propertyNameToUse, defaultValue);
  }

  static String validateAndGetStringFromConfig(
      Configuration config, String defaultValue, String... propertyNames) {
    String propertyNameToUse = validateAndGetPropertyNameToUse(config, propertyNames);
    if (propertyNameToUse == null) {
      return defaultValue;
    }

    return config.get(propertyNameToUse, defaultValue);
  }

  static long validateAndGetTimeDurationInMillisFromConfig(
      Configuration config, long defaultValue, String... propertyNames) {
    String propertyNameToUse = validateAndGetPropertyNameToUse(config, propertyNames);
    if (propertyNameToUse == null) {
      return defaultValue;
    }

    return config.getTimeDuration(propertyNameToUse, defaultValue, TimeUnit.MILLISECONDS);
  }

  URI createUriFromEndpoint(String endpoint) {
    Preconditions.checkArgument(
        !Strings.isNullOrEmpty(endpoint), "createURIFromEndpoint needs a non-empty endpoint.");

    if (!endpoint.contains(UriSchemes.SCHEME_SEPARATOR)) {
      String protocol = isSecureConnections ? "https" : "http";
      endpoint = protocol + UriSchemes.SCHEME_SEPARATOR + endpoint;
    }

    URI uri;
    try {
      uri = new URI(endpoint);
    } catch (URISyntaxException e) {
      throw UserException.sourceInBadState(e).build(logger);
    }

    return uri;
  }

  public <T extends S3ClientBuilder> void applyEndpointConfigurations(T builder) {
    if (!Strings.isNullOrEmpty(endpointOverride)) {
      URI uri = createUriFromEndpoint(endpointOverride);
      builder.endpointOverride(uri);
      logger.debug(
          "Applied endpoint configurations using endpoint override. Endpoint: {}", uri.toString());
      return;
    }

    if (!Strings.isNullOrEmpty(regionOverride)) {
      URI uri = createUriFromEndpoint(S3FileSystem.buildRegionEndpoint(regionOverride));
      builder.endpointOverride(uri);
      logger.debug(
          "Applied endpoint configurations using region override. Endpoint: {}", uri.toString());
    }
  }

  public <T extends S3AsyncClientBuilder> void applyAsyncEndpointConfigurations(T builder) {
    if (Strings.isNullOrEmpty(endpointOverride)) {
      return;
    }

    URI uri = createUriFromEndpoint(endpointOverride);
    builder.endpointOverride(uri);
    logger.debug("Applied async endpoint configurations. Endpoint: {}", uri.toString());
  }

  public <T extends AwsClientBuilder<?, ?>> void applyClientRegionConfigurations(
      T builder, Optional<Region> region) {
    if (region.isPresent()) {
      Region regionFromArgument = region.get();
      S3PluginUtils.checkIfValidAwsRegion(regionFromArgument);

      builder.region(regionFromArgument);
      logger.debug(
          "Applied client region configurations using region argument. Region: {}",
          regionFromArgument.id());
      return;
    }

    Region regionFromConfig =
        Strings.isNullOrEmpty(regionOverride)
            ? S3FileSystem.getAwsRegionFromEndpoint(endpointOverride)
            : Region.of(regionOverride);
    S3PluginUtils.checkIfValidAwsRegion(regionFromConfig);

    builder.region(regionFromConfig);
    logger.debug(
        "Applied client region configurations using the config value. Region: {}",
        regionFromConfig.id());
  }

  public <T extends S3ClientBuilder> void applyCrossRegionAccessConfigurations(T builder) {
    builder.crossRegionAccessEnabled(isCrossRegionAccess);
    logger.debug(
        "Applied cross-region access configurations. Cross-region access: {}", isCrossRegionAccess);
  }

  public <T extends S3BaseClientBuilder<?, ?>> void applyServiceConfigurations(T builder) {
    builder.serviceConfiguration(
        S3Configuration.builder().pathStyleAccessEnabled(isPathStyleAccess).build());
    logger.debug("Applied service configurations. Path-style access: {}", isPathStyleAccess);
  }

  public <T extends SdkClientBuilder<?, ?>> void applyRequesterPaysConfigurations(T builder) {
    if (!isRequesterPays) {
      return;
    }

    ClientOverrideConfiguration.Builder configBuilder =
        null != builder.overrideConfiguration()
            ? builder.overrideConfiguration().toBuilder()
            : ClientOverrideConfiguration.builder();

    builder.overrideConfiguration(
        configBuilder.putHeader(REQUESTER_PAYS_HEADER, REQUESTER_PAYS_HEADER_VALUE).build());
    logger.debug("Applied requester pays configurations.");
  }

  public <T extends SdkClientBuilder<?, ?>> void applyUserAgentConfigurations(T builder) {
    ClientOverrideConfiguration.Builder configBuilder =
        null != builder.overrideConfiguration()
            ? builder.overrideConfiguration().toBuilder()
            : ClientOverrideConfiguration.builder();

    builder.overrideConfiguration(
        configBuilder
            .putAdvancedOption(SdkAdvancedClientOption.USER_AGENT_PREFIX, USER_AGENT_PREFIX_DEFAULT)
            .build());
    logger.debug(
        "Applied user-agent configurations. User-agent prefix: {}", USER_AGENT_PREFIX_DEFAULT);
  }

  public <T extends SdkClientBuilder<?, ?>> void applyApiCallTimeoutConfigurations(T builder) {
    if (apiCallTimeout.isZero()) {
      logger.warn("API call timeout is zero. Using no timeout.");
      return;
    }

    Duration apiCallTimeoutToUse = apiCallTimeout;
    if (apiCallTimeout.isNegative()) {
      logger.warn(
          "API call timeout is negative: {}. Using the default instead: {}",
          apiCallTimeout,
          API_CALL_TIMEOUT_DURATION_DEFAULT);
      apiCallTimeoutToUse = API_CALL_TIMEOUT_DURATION_DEFAULT;
    }

    if (apiCallTimeoutToUse.compareTo(API_CALL_TIMEOUT_WARN_LIMIT) > 0) {
      logger.warn(
          "API call timeout is large. It may lead to requests hanging for longer than necessary. Timeout: {}",
          apiCallTimeoutToUse);
    }

    ClientOverrideConfiguration.Builder configBuilder =
        null != builder.overrideConfiguration()
            ? builder.overrideConfiguration().toBuilder()
            : ClientOverrideConfiguration.builder();

    builder.overrideConfiguration(
        configBuilder
            .apiCallAttemptTimeout(apiCallTimeoutToUse)
            .apiCallTimeout(apiCallTimeoutToUse)
            .build());
    logger.debug(
        "Applied API call timeout configurations. API call timeout in milliseconds: {}",
        apiCallTimeoutToUse.toMillis());
  }

  // TODO DX-103288: Support Iceberg property names in S3Client creation.
  public <T extends AwsClientBuilder<?, ?>> void applyClientCredentialConfigurations(T builder) {
    builder.credentialsProvider(AwsCredentialProviderUtils.getCredentialsProvider(config));
    logger.debug("Applied client credential configurations.");
  }

  // TODO DX-103288: Support Iceberg property names in S3Client creation.
  public <T extends AwsSyncClientBuilder<?, ?>> void applyHttpClientConfigurations(T builder) {
    builder.httpClientBuilder(ApacheHttpConnectionUtil.initConnectionSettings(config));
    logger.debug("Applied http client configurations.");
  }

  // TODO DX-103288: Support Iceberg property names in S3Client creation.
  public <T extends AwsAsyncClientBuilder<?, ?>> void applyAsyncHttpClientConfigurations(
      T builder) {
    builder.httpClientBuilder(ApacheHttpConnectionUtil.initAsyncConnectionSettings(config));
    logger.debug("Applied async http client configurations.");
  }

  public <T extends SdkAsyncClientBuilder<?, ?>> void applyAsyncConfigurations(
      T builder, ExecutorService executorService) {
    builder.asyncConfiguration(
        b ->
            b.advancedOption(
                SdkAdvancedAsyncClientOption.FUTURE_COMPLETION_EXECUTOR, executorService));
    logger.debug("Applied async configurations.");
  }
}
