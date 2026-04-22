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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.arrow.gandiva.exceptions.GandivaException;

public class Match {

  private final String type;
  private final List<String> patterns;

  public Match(String type, List<String> patterns) {
    this.type = type;
    this.patterns = patterns;
  }

  public static Match fromMap(Map<String, Object> map) {
    String type = (String) map.get("type");

    if (map.containsKey("pattern")) {
      return new Match(type, List.of((String) map.get("pattern")));
    }

    if (map.containsKey("patterns")) {
      Object patternsObj = map.get("patterns");
      List<String> patterns =
          ((List<?>) patternsObj)
              .stream().filter(Objects::nonNull).map(Object::toString).collect(Collectors.toList());

      return new Match(type, patterns);
    }

    return new Match(type, Collections.singletonList(".*"));
  }

  public Predicate<Throwable> toPredicate() {
    Predicate<Throwable> typePredicate = this.createTypePredicate();
    Predicate<Throwable> messagePredicate = this.createMessagePredicate();

    if (typePredicate == null) {
      return messagePredicate;
    }

    return typePredicate.and(messagePredicate);
  }

  private Predicate<Throwable> createTypePredicate() {
    if (this.type == null) {
      return null;
    }

    switch (this.type) {
      case "gandiva":
        return th -> th instanceof GandivaException;

      default:
        throw new UnknownExceptionTypeException(this.type);
    }
  }

  private Predicate<Throwable> createMessagePredicate() {
    final List<Pattern> compiledPatterns =
        patterns.stream().map(Pattern::compile).collect(Collectors.toList());

    return th -> compiledPatterns.stream().anyMatch(p -> p.matcher(th.getMessage()).find());
  }

  private static class UnknownExceptionTypeException extends RuntimeException {
    public UnknownExceptionTypeException(final String unknownType) {
      super(String.format("Unknown exception type to match against: %s", unknownType));
    }
  }
}
