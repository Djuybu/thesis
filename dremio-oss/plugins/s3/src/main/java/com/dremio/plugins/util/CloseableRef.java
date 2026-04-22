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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class helps in maintaining multiple references for a closeable object. The actual close
 * happens only when all refs have released.
 *
 * @param <T>
 */
public class CloseableRef<T extends AutoCloseable> implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(CloseableRef.class);
  private int refCnt;
  private T obj;
  private final int identityHashCode;
  private final String resourceClassName;

  public CloseableRef(T obj) {
    Preconditions.checkNotNull(
        obj, "A CloseableRef object cannot be initialized with a null resource.");
    this.obj = obj;
    refCnt = 1;
    identityHashCode = System.identityHashCode(obj);
    resourceClassName = obj.getClass().getSimpleName();

    // Performance optimization: avoid expensive getStackTrace() when logging is disabled.
    if (logger.isDebugEnabled()) {
      logger.debug(
          "Class {} created a new CloseableRef for {}:{}. Reference count = 1",
          getCallingClass(),
          resourceClassName,
          identityHashCode);
    }
  }

  public synchronized T acquireRef() {
    Preconditions.checkNotNull(
        obj,
        "Attempting to acquire a reference for %s:%s after the resource was made null. Reference count = %s",
        resourceClassName,
        identityHashCode,
        refCnt);
    ++refCnt;

    // Performance optimization: avoid expensive getStackTrace() when logging is disabled.
    if (logger.isDebugEnabled()) {
      logger.debug(
          "Class {} acquired the reference for {}:{}. Reference count = {}",
          getCallingClass(),
          resourceClassName,
          identityHashCode,
          refCnt);
    }

    return obj;
  }

  @Override
  public synchronized void close() throws Exception {
    if (refCnt == 0) {
      return;
    }

    if (--refCnt == 0 && obj != null) {
      logger.debug("Closing the reference for {}:{}", resourceClassName, identityHashCode);
      obj.close();
      obj = null;
    }

    // Performance optimization: avoid expensive getStackTrace() when logging is disabled.
    if (obj != null && logger.isDebugEnabled()) {
      logger.debug(
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
