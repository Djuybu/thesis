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
package com.dremio.testcontainers;

import java.util.Optional;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

public class DremioTestcontainersUsageValidator implements TestExecutionListener {
  private static final ThreadLocal<Class<?>> CURRENT_TEST_CLASS = new ThreadLocal<>();

  @Override
  public void executionStarted(TestIdentifier testIdentifier) {
    testClass(testIdentifier).ifPresent(CURRENT_TEST_CLASS::set);
  }

  @Override
  public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult result) {
    testClass(testIdentifier).ifPresent(testClass -> CURRENT_TEST_CLASS.remove());
  }

  private Optional<Class<?>> testClass(TestIdentifier testIdentifier) {
    return testIdentifier
        .getSource()
        .filter(testSource -> testSource instanceof ClassSource)
        .map(testSource -> (ClassSource) testSource)
        .map(ClassSource::getJavaClass);
  }

  public static void validate() {
    if (Boolean.getBoolean("dremio.testcontainers.validate.skip")) {
      return;
    }

    if (!Boolean.getBoolean("dremio.testcontainers.enabled")) {
      throw new IllegalStateException(
          "Testcontainers usage is not enabled. Please seek approval for your use case");
    }

    if (!isIntegrationTestClass()) {
      throw new IllegalStateException(
          "Testcontainers is only allowed with JUnit 5 integration tests");
    }
  }

  private static boolean isIntegrationTestClass() {
    return Optional.ofNullable(CURRENT_TEST_CLASS.get())
        .map(Class::getSimpleName)
        .filter(name -> name.startsWith("IT") || name.endsWith("IT"))
        .isPresent();
  }
}
