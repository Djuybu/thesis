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
package com.dremio.exec.planner.decorrelation;

import com.dremio.exec.planner.common.ExceptionMonad;
import java.util.List;
import org.apache.calcite.rel.RelNode;

/**
 * Takes a list of:
 *
 * @see MonadicDecorrelator And returns the result from the first one that succeeds. This is useful
 *     for when you have decorrelators that handle different subsets of queries.
 */
public final class FirstSuccessMonadicDecorrelator implements MonadicDecorrelator {
  private final List<MonadicDecorrelator> monadicDecorrelators;

  public FirstSuccessMonadicDecorrelator(List<MonadicDecorrelator> monadicDecorrelators) {
    this.monadicDecorrelators = monadicDecorrelators;
  }

  @Override
  public ExceptionMonad<RelNode, DecorrelationException> tryDecorrelate(RelNode relNode) {
    // Return the first successful monad.
    ExceptionMonad<RelNode, DecorrelationException> monad = null;
    for (MonadicDecorrelator monadicDecorrelator : monadicDecorrelators) {
      monad = monadicDecorrelator.tryDecorrelate(relNode);
      if (monad.isSuccess()) {
        return monad;
      }
    }

    return monad;
  }
}
