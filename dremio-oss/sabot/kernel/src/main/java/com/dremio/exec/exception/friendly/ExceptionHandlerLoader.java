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

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.Yaml;

public class ExceptionHandlerLoader {
  public static List<ExceptionHandler<?>> loadHandlers(final String yamlPath) {
    Yaml yaml = new Yaml();

    try (InputStream in =
        ExceptionHandlerLoader.class.getClassLoader().getResourceAsStream(yamlPath)) {
      List<Map<String, Object>> handlers = yaml.load(in);

      return handlers.stream()
          .map(ExceptionHandlerYaml::fromMap)
          .map(ExceptionHandlerYaml::toExceptionHandler)
          .collect(Collectors.toList());
    } catch (Exception e) {
      throw new Exceptions.YamlLoadingException(yamlPath, e);
    }
  }

  private static class ExceptionHandlerYaml {
    private final String name;
    private final Match match;
    private final String treatAs;
    private final String friendlyMessage;

    public ExceptionHandlerYaml(String name, Match match, String treatAs, String friendlyMessage) {
      this.name = name;
      this.match = match;
      this.treatAs = treatAs;
      this.friendlyMessage = friendlyMessage;
    }

    public ExceptionHandler<?> toExceptionHandler() {
      switch (this.treatAs) {
        case "out-of-memory":
          return new OutOfMemoryExceptionHandler(this.name, this.match.toPredicate());

        case "function":
          return new FunctionExceptionHandler(
              this.name, this.match.toPredicate(), this.friendlyMessage);

        default:
          throw new Exceptions.UnknownHandlerTypeException(this.treatAs);
      }
    }

    public static ExceptionHandlerYaml fromMap(Map<String, Object> map) {
      String name = (String) map.get("name");
      String treatAs = (String) map.get("treatAs");
      String friendlyMessage = (String) map.get("friendlyMessage");

      final Match match = Match.fromMap((Map<String, Object>) map.get("match"));

      return new ExceptionHandlerYaml(name, match, treatAs, friendlyMessage);
    }
  }
}
