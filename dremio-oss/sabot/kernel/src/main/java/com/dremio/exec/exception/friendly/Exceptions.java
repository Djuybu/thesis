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

public class Exceptions {
  public static class YamlLoadingException extends RuntimeException {
    public YamlLoadingException(final String yamlPath, final Throwable cause) {
      super(String.format("Failed to load handlers from %s", yamlPath), cause);
    }
  }

  public static class UnknownHandlerTypeException extends RuntimeException {
    public UnknownHandlerTypeException(final String type) {
      super(String.format("Unknown handler type: %s", type));
    }
  }

  public static class InvalidHandlerConfiguration extends RuntimeException {
    public InvalidHandlerConfiguration(final String message) {
      super(message);
    }
  }
}
