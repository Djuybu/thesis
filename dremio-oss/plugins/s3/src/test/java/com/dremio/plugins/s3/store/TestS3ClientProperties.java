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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dremio.common.exceptions.UserException;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.s3a.Constants;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

public class TestS3ClientProperties {
  private static class Property {
    private final String name;
    private final String value;

    public Property(String name, String value) {
      this.name = name;
      this.value = value;
    }

    public String getName() {
      return name;
    }

    public String getValue() {
      return value;
    }
  }

  private Configuration buildConfigFromProperties(Property[] properties) {
    Configuration config = new Configuration();
    for (Property property : properties) {
      if (property.getValue() != null) {
        config.set(property.getName(), property.getValue());
      }
    }
    return config;
  }

  public static Stream<Object[]> parametersValidateAndGetPropertyNameToUseSuccess() {
    // spotless messes up testcase formatting
    // spotless:off
    return Stream.of(
      new Object[] {
        "name1",
        new Property[] {
          new Property("name1", "value1")
        }
      },
      new Object[] {
        null,
        new Property[] {
          new Property("name1", null)
        }
      },
      new Object[] {
        "name1",
        new Property[] {
          new Property("name1", "value1"),
          new Property("name2", "value1")
        }
      },
      new Object[] {
        "name1",
        new Property[] {
          new Property("name1", "value1 "),
          new Property("name2", "    value1")
        }
      },
      new Object[] {
        "name1",
        new Property[] {
          new Property("name1", "   multiple word value "),
          new Property("name2", "multiple word value  ")
        }
      },
      new Object[] {
        "name1",
        new Property[] {
          new Property("name1", "value1"),
          new Property("name2", null)
        }
      },
      new Object[] {
        "name2",
        new Property[] {
          new Property("name1", null),
          new Property("name2", "value2")
        }
      },
      new Object[] {
        "name1",
        new Property[] {
          new Property("name1", "value1"),
          new Property("name2", " ")
        }
      },
      new Object[] {
        "name2",
        new Property[] {
          new Property("name1", ""),
          new Property("name2", "value2")
        }
      },
      new Object[] {
        null,
        new Property[] {
          new Property("name1", null),
          new Property("name2", null)
        }
      },
      new Object[] {
        null,
        new Property[] {
          new Property("name1", ""),
          new Property("name2", "")
        }
      },
      new Object[] {
        "name1",
        new Property[] {
          new Property("name1", "value1"),
          new Property("name2", "value1"),
          new Property("name3", "value1")
        }
      },
      new Object[] {
        "name3",
        new Property[] {
          new Property("name1", null),
          new Property("name2", null),
          new Property("name3", "value3")
        }
      },
      new Object[] {
        null,
        new Property[] {
          new Property("name1", null),
          new Property("name2", null),
          new Property("name3", null)
        }
      });
    // spotless:on
  }

  @ParameterizedTest
  @MethodSource("parametersValidateAndGetPropertyNameToUseSuccess")
  public void testValidateAndGetPropertyNameToUse(
      String expectedPropertyName, Property[] properties) {
    Configuration config = buildConfigFromProperties(properties);
    String[] propertyNames = Arrays.stream(properties).map(p -> p.getName()).toArray(String[]::new);

    String actualPropertyName =
        S3ClientProperties.validateAndGetPropertyNameToUse(config, propertyNames);
    Assertions.assertEquals(expectedPropertyName, actualPropertyName);
  }

  public static Stream<Object[]> parametersValidateAndGetPropertyNameToUseMismatch() {
    // spotless messes up testcase formatting
    // spotless:off
    return Stream.of(
      new Object[] {
        "name1",
        new String[] {
          "Inconsistent values for S3Client properties. name1 = value1, name2 = value2",
          "There was a mismatch in property values for [name1, name2]. name1 = value1 was used"
        },
        new Property[] {
          new Property("name1", "value1"),
          new Property("name2", "value2")
        }
      },
      new Object[] {
        "name1",
        new String[] {
          "Inconsistent values for S3Client properties. name1 = value1, name2 = value2",
          "Inconsistent values for S3Client properties. name1 = value1, name3 = value3",
          "There was a mismatch in property values for [name1, name2, name3]. name1 = value1 was used"
        },
        new Property[] {
          new Property("name1", "value1"),
          new Property("name2", "value2"),
          new Property("name3", "value3")
        }
      },
      new Object[] {
        "name1",
        new String[] {
          "Inconsistent values for S3Client properties. name1 = value1, name3 = value3",
          "There was a mismatch in property values for [name1, name2, name3]. name1 = value1 was used"
        },
        new Property[] {
          new Property("name1", "value1"),
          new Property("name2", "value1"),
          new Property("name3", "value3")
        }
      },
      new Object[] {
        "name1",
        new String[] {
          "Inconsistent values for S3Client properties. name1 = value1, name3 = value3",
          "There was a mismatch in property values for [name1, name2, name3]. name1 = value1 was used"
        },
        new Property[] {
          new Property("name1", "value1"),
          new Property("name2", null),
          new Property("name3", "value3")
        }
      },
      new Object[] {
        "name2",
        new String[] {
          "Inconsistent values for S3Client properties. name2 = value2, name3 = value3",
          "There was a mismatch in property values for [name1, name2, name3]. name2 = value2 was used"
        },
        new Property[] {
          new Property("name1", null),
          new Property("name2", "value2"),
          new Property("name3", "value3")
        }
      });
    // spotless:on
  }

  @ParameterizedTest
  @MethodSource("parametersValidateAndGetPropertyNameToUseMismatch")
  public void testValidateAndGetPropertyNameToUseMismatch(
      String expectedPropertyName, String[] warnLogs, Property[] properties) {
    @SuppressWarnings("Slf4jIllegalPassedClass") // intentionally using logger from another class
    Logger s3ClientPropertiesLogger = (Logger) LoggerFactory.getLogger(S3ClientProperties.class);
    ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
    listAppender.start();
    s3ClientPropertiesLogger.addAppender(listAppender);

    Configuration config = buildConfigFromProperties(properties);
    String[] propertyNames = Arrays.stream(properties).map(p -> p.getName()).toArray(String[]::new);

    String actualPropertyName =
        S3ClientProperties.validateAndGetPropertyNameToUse(config, propertyNames);
    Assertions.assertEquals(expectedPropertyName, actualPropertyName);

    List<ILoggingEvent> logsList = listAppender.list;
    Assertions.assertEquals(warnLogs.length, logsList.size());

    for (int i = 0; i < logsList.size(); ++i) {
      Assertions.assertEquals(warnLogs[i], logsList.get(i).getFormattedMessage());
      Assertions.assertEquals(Level.WARN, logsList.get(i).getLevel());
    }

    listAppender.stop();
  }

  @Test
  public void testValidateAndGetPropertyNameToUseEmptyPropertyNames() {
    String expectedExceptionMessage =
        "validateAndGetPropertyNameToUse needs at least one propertyName to retrieve from the config.";

    Exception exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> {
              S3ClientProperties.validateAndGetPropertyNameToUse(
                  new Configuration(), new String[] {});
            });
    Assertions.assertEquals(expectedExceptionMessage, exception.getMessage());
  }

  public static Stream<Object[]> parametersValidateAndGetBooleanFromConfigSuccess() {
    // spotless messes up testcase formatting
    // spotless:off
    return Stream.of(
      new Object[] {
        false, false,
        new Property[] {
          new Property("name1", null),
          new Property("name2", null),
        }
      },
      new Object[] {
        true, true,
        new Property[] {
          new Property("name1", ""),
          new Property("name2", " "),
        }
      },
      new Object[] {
        false, true,
        new Property[] {
          new Property("name1", "false"),
          new Property("name2", null),
        }
      },
      new Object[] {
        true, false,
        new Property[] {
          new Property("name1", null),
          new Property("name2", "true"),
        }
      },
      new Object[] {
        true, true,
        new Property[] {
          new Property("name1", null),
          new Property("name2", "true"),
        }
      },
      new Object[] {
        true, true,
        new Property[] {
          new Property("name1", "    true"),
          new Property("name2", "true     "),
          new Property("name3", "     true"),
        }
      });
    // spotless:on
  }

  @ParameterizedTest
  @MethodSource("parametersValidateAndGetBooleanFromConfigSuccess")
  public void testValidateAndGetBooleanFromConfigSuccess(
      boolean expectedValue, boolean defaultValue, Property[] properties) {
    Configuration config = buildConfigFromProperties(properties);
    String[] propertyNames = Arrays.stream(properties).map(p -> p.getName()).toArray(String[]::new);

    boolean actualValue =
        S3ClientProperties.validateAndGetBooleanFromConfig(config, defaultValue, propertyNames);
    Assertions.assertEquals(expectedValue, actualValue);
  }

  public static Stream<Object[]> parametersValidateAndGetTimeDurationInMillisFromConfigSuccess() {
    // spotless messes up testcase formatting
    // spotless:off
    return Stream.of(
      // All null properties return default value
      new Object[] {
        42, 42,
        new Property[] {
          new Property("name1", null),
          new Property("name2", null),
        }
      },
      // Empty and whitespace properties return default value
      new Object[] {
        42, 42,
        new Property[] {
          new Property("name1", ""),
          new Property("name2", " "),
        }
      },
      // Plain number without unit (uses default unit - milliseconds)
      new Object[] {
        5000, 0,
        new Property[] {
          new Property("name1", "5000"),
          new Property("name2", null),
        }
      },
      // Nanoseconds (ns) - 1000000ns = 1ms
      new Object[] {
        1, 0,
        new Property[] {
          new Property("name1", null),
          new Property("name2", "1000000ns"),
        }
      },
      // Microseconds (us) - 1000us = 1ms
      new Object[] {
        1, 0,
        new Property[] {
          new Property("name1", "1000us"),
          new Property("name2", "1000us"),
          new Property("name3", "1000us"),
        }
      },
      // Milliseconds (ms)
      new Object[] {
        500, 0,
        new Property[] {
          new Property("name1", "500ms"),
        }
      },
      // Seconds (s) - 30s = 30000ms
      new Object[] {
        30000, 0,
        new Property[] {
          new Property("name1", "30s"),
        }
      },
      // Minutes (m) - 2m = 120000ms
      new Object[] {
        120000, 0,
        new Property[] {
          new Property("name1", "2m")
        }
      },
      // Hours (h) - 1h = 3600000ms
      new Object[] {
        3600000, 0,
        new Property[] {
          new Property("name1", "1h")
        }
      },
      // Days (d) - 1d = 86400000ms
      new Object[] {
        86400000, 0,
        new Property[] {
          new Property("name1", "1d")
        }
      },
      // Whitespace handling - should trim spaces
      new Object[] {
        1000, 0,
        new Property[] {
          new Property("name1", "  1000ms  ")
        }
      });
    // spotless:on
  }

  @ParameterizedTest
  @MethodSource("parametersValidateAndGetTimeDurationInMillisFromConfigSuccess")
  public void testValidateAndGetTimeDurationInMillisFromConfigSuccess(
      long expectedValue, long defaultValue, Property[] properties) {
    Configuration config = buildConfigFromProperties(properties);
    String[] propertyNames = Arrays.stream(properties).map(p -> p.getName()).toArray(String[]::new);

    long actualValue =
        S3ClientProperties.validateAndGetTimeDurationInMillisFromConfig(
            config, defaultValue, propertyNames);
    Assertions.assertEquals(expectedValue, actualValue);
  }

  public static Stream<Object[]> parametersValidateAndGetTimeDurationInMillisFromConfigFailure() {
    // spotless messes up testcase formatting
    // spotless:off
    return Stream.of(
      // not a number
      new Object[] {
        "For input string: \"not a number\"",
        new Property[] {
          new Property("name1", "not a number")
        }
      },
      // decimal number without unit
      new Object[] {
        "For input string: \"4.2\"",
        new Property[] {
          new Property("name1", "4.2"),
        }
      },
      // number too large for long
      new Object[] {
        "For input string: \"99999999999999999999\"",
        new Property[] {
          new Property("name1", "99999999999999999999ms"),
        }
      },
      // invalid unit suffix
      new Object[] {
        "For input string: \"42x\"",
        new Property[] {
          new Property("name1", "42x"),
        }
      },
      // invalid format - unit without number
      new Object[] {
        "For input string: \"\"",
        new Property[] {
          new Property("name1", "ms"),
        }
      },
      // multiple units
      new Object[] {
        "For input string: \"10s20\"",
        new Property[] {
          new Property("name1", "10s20ms"),
        }
      },
      // hex number
      new Object[] {
        "For input string: \"0xff\"",
        new Property[] {
          new Property("name1", "0xff"),
        }
      });
    // spotless:on
  }

  @ParameterizedTest
  @MethodSource("parametersValidateAndGetTimeDurationInMillisFromConfigFailure")
  public void testValidateAndGetTimeDurationInMillisFromConfigFailure(
      String expectedExceptionMessage, Property[] properties) {
    Configuration config = buildConfigFromProperties(properties);
    String[] propertyNames = Arrays.stream(properties).map(p -> p.getName()).toArray(String[]::new);

    Exception exception =
        Assertions.assertThrows(
            NumberFormatException.class,
            () -> {
              S3ClientProperties.validateAndGetTimeDurationInMillisFromConfig(
                  config, 42, propertyNames);
            });
    Assertions.assertEquals(expectedExceptionMessage, exception.getMessage());
  }

  public static Stream<Object[]> parametersValidateAndGetStringFromConfig() {
    // spotless messes up testcase formatting
    // spotless:off
    return Stream.of(
      new Object[] {
        "value1", "default",
        new Property[] {
          new Property("name1", "value1"),
        }
      },
      new Object[] {
        "default", "default",
        new Property[] {
          new Property("name1", null),
          new Property("name2", null),
        }
      },
      new Object[] {
        "default", "default",
        new Property[] {
          new Property("name1", null),
          new Property("name2", ""),
        }
      },
      new Object[] {
        "default", "default",
        new Property[] {
          new Property("name1", null),
          new Property("name2", " "),
        }
      },
      new Object[] {
        null, null,
        new Property[] {
          new Property("name1", null),
          new Property("name2", null),
          new Property("name3", null),
        }
      });
    // spotless:on
  }

  @ParameterizedTest
  @MethodSource("parametersValidateAndGetStringFromConfig")
  public void testValidateAndGetStringFromConfig(
      String expectedValue, String defaultValue, Property[] properties) {
    Configuration config = buildConfigFromProperties(properties);
    String[] propertyNames = Arrays.stream(properties).map(p -> p.getName()).toArray(String[]::new);

    String actualValue =
        S3ClientProperties.validateAndGetStringFromConfig(config, defaultValue, propertyNames);
    Assertions.assertEquals(expectedValue, actualValue);
  }

  @Test
  public void testCreateURIFromEndpointAlreadyHasHttpsEndpoint() throws IOException {
    URI uri = URI.create("https://localhost:9000");
    String endpoint = uri.toString();

    Configuration config = new Configuration();
    config.set(Constants.ENDPOINT, endpoint);

    S3ClientProperties s3ClientProperties = new S3ClientProperties(config);
    Assertions.assertEquals(uri, s3ClientProperties.createUriFromEndpoint(endpoint));
  }

  @Test
  public void testCreateURIFromEndpointAlreadyHasHttpEndpoint() throws IOException {
    URI uri = URI.create("http://localhost:9000");
    String endpoint = uri.toString();

    Configuration config = new Configuration();
    config.set(Constants.ENDPOINT, endpoint);

    S3ClientProperties s3ClientProperties = new S3ClientProperties(config);
    Assertions.assertEquals(uri, s3ClientProperties.createUriFromEndpoint(endpoint));
  }

  @Test
  public void testCreateURIFromEndpointSecureConnections() throws IOException {
    URI uri = URI.create("https://localhost:9000");
    String endpoint = uri.getAuthority();

    Configuration config = new Configuration();
    config.set(Constants.SECURE_CONNECTIONS, "true");
    config.set(Constants.ENDPOINT, endpoint);

    S3ClientProperties s3ClientProperties = new S3ClientProperties(config);
    Assertions.assertEquals(uri, s3ClientProperties.createUriFromEndpoint(endpoint));
  }

  @Test
  public void testCreateURIFromEndpointNotSecureConnections() throws IOException {
    URI uri = URI.create("http://localhost:9000");
    String endpoint = uri.getAuthority();

    Configuration config = new Configuration();
    config.set(Constants.SECURE_CONNECTIONS, "false");
    config.set(Constants.ENDPOINT, endpoint);

    S3ClientProperties s3ClientProperties = new S3ClientProperties(config);

    Assertions.assertEquals(uri, s3ClientProperties.createUriFromEndpoint(endpoint));
  }

  @Test
  public void testCreateURIFromEndpointEmpty() throws IOException {
    Configuration config = new Configuration();
    S3ClientProperties s3ClientProperties = new S3ClientProperties(config);

    String endpoint = "";
    IllegalArgumentException exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> s3ClientProperties.createUriFromEndpoint(endpoint));
    Assertions.assertEquals(
        "createURIFromEndpoint needs a non-empty endpoint.", exception.getMessage());
  }

  @Test
  public void testCreateURIFromEndpointNull() throws IOException {
    Configuration config = new Configuration();
    S3ClientProperties s3ClientProperties = new S3ClientProperties(config);

    String endpoint = null;
    IllegalArgumentException exception =
        Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> s3ClientProperties.createUriFromEndpoint(endpoint));
    Assertions.assertEquals(
        "createURIFromEndpoint needs a non-empty endpoint.", exception.getMessage());
  }

  @Test
  public void testCreateURIFromEndpointMalformedEndpoint() throws IOException {
    Configuration config = new Configuration();
    S3ClientProperties s3ClientProperties = new S3ClientProperties(config);

    String endpoint = "://http";
    UserException exception =
        Assertions.assertThrows(
            UserException.class, () -> s3ClientProperties.createUriFromEndpoint(endpoint));
    Assertions.assertEquals("Expected scheme name at index 0: ://http", exception.getMessage());
  }

  @Test
  public void testApplyApiCallTimeoutConfigurationsPositiveTimeout() {
    Configuration config = new Configuration();
    config.set(Constants.REQUEST_TIMEOUT, "100");
    S3ClientProperties s3ClientProperties = new S3ClientProperties(config);

    S3ClientBuilder builder = S3Client.builder();
    builder.applyMutation(s3ClientProperties::applyApiCallTimeoutConfigurations);

    Duration expectedApiCallTimeout = Duration.ofMillis(100);
    Duration expectedApiCallAttemptTimeout = Duration.ofMillis(100);
    Optional<Duration> apiCallTimeout = builder.overrideConfiguration().apiCallTimeout();
    Optional<Duration> apiCallAttemptTimeout =
        builder.overrideConfiguration().apiCallAttemptTimeout();

    Assertions.assertTrue(apiCallTimeout.isPresent());
    Assertions.assertTrue(apiCallAttemptTimeout.isPresent());
    Assertions.assertEquals(expectedApiCallTimeout, apiCallTimeout.get());
    Assertions.assertEquals(expectedApiCallAttemptTimeout, apiCallAttemptTimeout.get());
  }

  @Test
  public void testApplyApiCallTimeoutConfigurationsNegativeTimeout() {
    Configuration config = new Configuration();
    config.set(Constants.REQUEST_TIMEOUT, "-100");
    S3ClientProperties s3ClientProperties = new S3ClientProperties(config);

    S3ClientBuilder builder = S3Client.builder();
    builder.applyMutation(s3ClientProperties::applyApiCallTimeoutConfigurations);

    Duration expectedApiCallTimeout = Duration.ofSeconds(60);
    Duration expectedApiCallAttemptTimeout = Duration.ofSeconds(60);
    Optional<Duration> apiCallTimeout = builder.overrideConfiguration().apiCallTimeout();
    Optional<Duration> apiCallAttemptTimeout =
        builder.overrideConfiguration().apiCallAttemptTimeout();

    Assertions.assertTrue(apiCallTimeout.isPresent());
    Assertions.assertTrue(apiCallAttemptTimeout.isPresent());
    Assertions.assertEquals(expectedApiCallTimeout, apiCallTimeout.get());
    Assertions.assertEquals(expectedApiCallAttemptTimeout, apiCallAttemptTimeout.get());
  }

  @Test
  public void testApplyApiCallTimeoutConfigurationsZeroTimeout() {
    Configuration config = new Configuration();
    config.set(Constants.REQUEST_TIMEOUT, "0");
    S3ClientProperties s3ClientProperties = new S3ClientProperties(config);

    S3ClientBuilder builder = S3Client.builder();
    builder.applyMutation(s3ClientProperties::applyApiCallTimeoutConfigurations);

    Optional<Duration> apiCallTimeout = builder.overrideConfiguration().apiCallTimeout();
    Optional<Duration> apiCallAttemptTimeout =
        builder.overrideConfiguration().apiCallAttemptTimeout();

    Assertions.assertTrue(apiCallTimeout.isEmpty());
    Assertions.assertTrue(apiCallAttemptTimeout.isEmpty());
  }
}
