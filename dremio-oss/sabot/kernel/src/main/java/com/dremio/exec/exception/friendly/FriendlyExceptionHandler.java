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
package com.dremio.exec.exception.friendly;

import static com.dremio.telemetry.api.metrics.CommonTags.TAGS_OUTCOME_ERROR;
import static com.dremio.telemetry.api.metrics.CommonTags.TAGS_OUTCOME_SUCCESS;

import com.dremio.common.exceptions.UserException;
import com.google.common.base.Joiner;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Metrics;
import java.util.List;
import java.util.Optional;

public class FriendlyExceptionHandler {
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(FriendlyExceptionHandler.class);
  private static final ExceptionHandler<UserException> DEFAULT_HANDLER =
      new DefaultExceptionHandler();

  public static Exception makeExceptionFriendlyMaybe(
      final Exception e, final List<ExceptionHandler<?>> handlers) {
    Optional<ExceptionHandler<?>> matchingHandler =
        handlers.stream().filter(h -> h.isMatch(e)).findFirst();

    if (matchingHandler.isPresent()) {
      logger.debug("Overriding the original exception using {}", matchingHandler);

      Telemetry.getProcessedExceptionsCounter().withTags(TAGS_OUTCOME_SUCCESS).increment();

      return matchingHandler.get().makeFriendly(e);
    }

    logger.debug("Could not find a handler for exception {}", e.getMessage());

    Telemetry.getProcessedExceptionsCounter().withTags(TAGS_OUTCOME_ERROR).increment();

    return DEFAULT_HANDLER.makeFriendly(e);
  }

  private static class Telemetry {
    private static final String PREFIX = "friendly_exceptions";

    private static final Meter.MeterProvider<Counter> processedExceptionsCounter =
        Counter.builder(Joiner.on(".").join(PREFIX, "processed_exceptions"))
            .description(
                "Counts exceptions that may have been overridden with user-friendly alternatives")
            .withRegistry(Metrics.globalRegistry);

    public static Meter.MeterProvider<Counter> getProcessedExceptionsCounter() {
      return processedExceptionsCounter;
    }
  }
}
