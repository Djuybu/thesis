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
package com.dremio.exec.planner.common;

import com.dremio.service.Pointer;
import org.slf4j.Logger;

public final class AssertionLogger {
  private final Logger logger;

  // Constructor to accept a Logger instance
  public AssertionLogger(Logger logger) {
    this.logger = logger;
  }

  public void assertTrue(boolean condition, String message) {
    if (!condition) {
      if (areAssertionsEnabled()) {
        throw new AssertionError(message);
      } else {
        logger.error(message);
      }
    }
  }

  public void assertFalse(boolean condition, String message) {
    assertTrue(!condition, message);
  }

  public void hitUnexpectedException(Throwable ex) {
    if (areAssertionsEnabled()) {
      throw new AssertionError(ex);
    } else {
      logger.error("Hit Unexpected Exception", ex);
    }
  }

  public void assertNotNull(Object object, String message) {
    assertTrue(object != null, message);
  }

  private boolean areAssertionsEnabled() {
    // This sets to true if assertions are enabled
    Pointer<Boolean> areAssertionsEnabled = new Pointer<>();
    areAssertionsEnabled.value = false;
    assert setAssertionsEnabled(areAssertionsEnabled);
    return areAssertionsEnabled.value;
  }

  private boolean setAssertionsEnabled(Pointer<Boolean> assertionsEnabled) {
    assertionsEnabled.value = true;
    return true;
  }
}
