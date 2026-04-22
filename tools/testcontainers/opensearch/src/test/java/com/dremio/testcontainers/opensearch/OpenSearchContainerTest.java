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
package com.dremio.testcontainers.opensearch;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OpenSearchContainerTest {
  private final OpenSearchContainer container = new OpenSearchContainer();

  @Test
  void test() {
    assertThatThrownBy(container::start)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Testcontainers is only allowed with JUnit 5 integration tests");
  }
}
