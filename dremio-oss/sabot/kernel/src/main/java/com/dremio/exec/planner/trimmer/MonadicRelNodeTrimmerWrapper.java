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
import com.google.common.base.Preconditions;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.tools.RelBuilderFactory;

/**
 * Wraps a
 *
 * @see RelNodeTrimmer and make it's into a
 * @see com.dremio.exec.planner.common.ExceptionMonad based API.
 */
public final class MonadicRelNodeTrimmerWrapper implements MonadicRelNodeTrimmer {
  private final RelNodeTrimmer trimmer;

  private MonadicRelNodeTrimmerWrapper(RelNodeTrimmer trimmer) {
    this.trimmer = Preconditions.checkNotNull(trimmer);
  }

  @Override
  public ExceptionMonad<RelNode, Exception> tryTrim(
      RelNode relNode, RelBuilderFactory relBuilderFactory) {
    try {
      RelNode trimmed = trimmer.trim(relNode, relBuilderFactory);
      return ExceptionMonad.success(trimmed);
    } catch (Exception ex) {
      return ExceptionMonad.failure(ex);
    }
  }

  public static MonadicRelNodeTrimmer wrap(RelNodeTrimmer trimmer) {
    return new MonadicRelNodeTrimmerWrapper(trimmer);
  }
}
