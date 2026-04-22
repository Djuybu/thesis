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

import java.util.ArrayList;
import java.util.List;

public final class ExceptionHandlers {
  private static final List<ExceptionHandler<?>> handlers = composeHandlers();

  private ExceptionHandlers() {}

  private static List<ExceptionHandler<?>> composeHandlers() {
    final ArrayList<ExceptionHandler<?>> handlers = new ArrayList<>();

    handlers.addAll(ExceptionHandlerLoader.loadHandlers("friendly-exceptions/handlers.yaml"));

    return handlers;
  }

  public static List<ExceptionHandler<?>> getHandlers() {
    return ExceptionHandlers.handlers;
  }

  public static void appendHandlers(final List<ExceptionHandler<?>> handlers) {
    ExceptionHandlers.handlers.addAll(handlers);
  }
}
