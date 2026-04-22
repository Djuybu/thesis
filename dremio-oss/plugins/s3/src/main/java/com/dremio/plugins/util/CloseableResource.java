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
package com.dremio.plugins.util;

import com.google.common.base.Preconditions;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Wraps AutoCloseable implementation of non-closeable objects */
public class CloseableResource<T> implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(CloseableResource.class);
  private T resource;
  private Consumer<T> closerFunc;
  private int refCnt = 1;
  private final int identityHashCode;
  private final String resourceClassName;

  public CloseableResource(T resource, Consumer<T> closerFunc) {
    Preconditions.checkNotNull(
        resource, "A CloseableResource object cannot be initialized with a null resource.");
    this.resource = resource;
    this.closerFunc = closerFunc;
    identityHashCode = System.identityHashCode(resource);
    resourceClassName = resource.getClass().getSimpleName();

    // Performance optimization: avoid expensive getStackTrace() when logging is disabled.
    if (logger.isInfoEnabled()) {
      logger.info(
          "Class {} created a new CloseableResource for {}:{}. Reference count = 1",
          getCallingClass(),
          resourceClassName,
          identityHashCode);
    }
  }

  public synchronized T getResource() {
    Preconditions.checkNotNull(
        resource,
        "Attempting to acquire a reference for %s:%s after the resource was made null. Reference count = %s",
        resourceClassName,
        identityHashCode,
        refCnt);
    ++refCnt;

    // Performance optimization: avoid expensive getStackTrace() when logging is disabled.
    if (logger.isInfoEnabled()) {
      logger.info(
          "Class {} acquired the reference for {}:{}. Reference count = {}",
          getCallingClass(),
          resourceClassName,
          identityHashCode,
          refCnt);
    }

    return resource;
  }

  @Override
  public synchronized void close() throws Exception {
    if (refCnt == 0) {
      return;
    }

    if (--refCnt == 0 && resource != null) {
      logger.info("Closing the reference for {}:{}", resourceClassName, identityHashCode);
      closerFunc.accept(resource);
      resource = null;
    }

    // Performance optimization: avoid expensive getStackTrace() when logging is disabled.
    if (resource != null && logger.isInfoEnabled()) {
      logger.info(
          "Class {} released the reference for {}:{}. Reference count = {}",
          getCallingClass(),
          resourceClassName,
          identityHashCode,
          refCnt);
    }
  }

  private String getCallingClass() {
    final StackTraceElement traceElement = Thread.currentThread().getStackTrace()[3];
    return traceElement.getClassName()
        + ":"
        + traceElement.getMethodName()
        + ":"
        + traceElement.getLineNumber();
  }
}
