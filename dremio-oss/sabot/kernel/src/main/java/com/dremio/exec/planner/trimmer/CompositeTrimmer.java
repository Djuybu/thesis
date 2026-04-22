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
package com.dremio.exec.planner.trimmer;

import com.dremio.exec.planner.common.ExceptionMonad;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.tools.RelBuilderFactory;

public class CompositeTrimmer implements MonadicRelNodeTrimmer {
  private final MonadicRelNodeTrimmer calciteTrimmer;
  private final MonadicRelNodeTrimmer pushdownTrimmer;

  public CompositeTrimmer(
      MonadicRelNodeTrimmer calciteTrimmer, MonadicRelNodeTrimmer pushdownTrimmer) {
    this.calciteTrimmer = calciteTrimmer;
    this.pushdownTrimmer = pushdownTrimmer;
  }

  @Override
  public ExceptionMonad<RelNode, Exception> tryTrim(
      RelNode relNode, RelBuilderFactory relBuilderFactory) {
    ExceptionMonad<RelNode, Exception> tryPushdown =
        pushdownTrimmer.tryTrim(relNode, relBuilderFactory);
    if (tryPushdown.isFailure()) {
      return calciteTrimmer.tryTrim(relNode, relBuilderFactory);
    }

    RelNode trimmedWithPushdown = tryPushdown.get();
    ExceptionMonad<RelNode, Exception> tryCalcite =
        pushdownTrimmer.tryTrim(trimmedWithPushdown, relBuilderFactory);
    return tryCalcite;
  }
}
