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
import com.dremio.exec.planner.logical.RelDataTypeEqualityComparer;
import java.util.Optional;
import org.apache.calcite.rel.RelNode;

/**
 * Takes a
 *
 * @see Decorrelator and makes it monadic by catching all the failure cases and returning a monad
 *     instead.
 */
public final class MonadicDecorrelatorWrapper implements MonadicDecorrelator {
  private final Decorrelator decorrelator;

  public MonadicDecorrelatorWrapper(Decorrelator decorrelator) {
    this.decorrelator = decorrelator;
  }

  @Override
  public ExceptionMonad<RelNode, DecorrelationException> tryDecorrelate(RelNode relNode) {
    try {
      RelNode decorrelated = decorrelator.decorrelate(relNode);
      Optional<String> optionalFailureReason =
          DecorrelationFailureReasonDetector.investigateFailures(decorrelated);
      if (optionalFailureReason.isPresent()) {
        return DecorrelationMonad.failure(new DecorrelationException(optionalFailureReason.get()));
      } else {
        if (!RelDataTypeEqualityComparer.areEqual(
            decorrelated.getRowType(), relNode.getRowType())) {
          return DecorrelationMonad.failure(
              new DecorrelationException("DECORRELATOR DID NOT PRESERVE SCHEMA."));
        }

        return DecorrelationMonad.success(decorrelated);
      }
    } catch (Throwable ex) {
      return DecorrelationMonad.failure(new DecorrelationException("INTERNAL EXCEPTION", ex));
    }
  }

  private static final class DecorrelationMonad
      extends ExceptionMonad<RelNode, DecorrelationException> {
    private DecorrelationMonad(RelNode value, DecorrelationException exception) {
      super(value, exception);
    }

    public static DecorrelationMonad success(RelNode value) {
      return new DecorrelationMonad(value, null);
    }

    public static DecorrelationMonad failure(DecorrelationException exception) {
      return new DecorrelationMonad(null, exception);
    }
  }
}
