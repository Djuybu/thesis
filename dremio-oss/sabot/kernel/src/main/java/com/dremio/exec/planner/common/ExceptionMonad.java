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

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A generic monadic wrapper for handling success or failure states in a functional manner.
 *
 * @param <T> The type of value held when the monad is in a success state.
 * @param <E> The type of exception held when the monad is in a failure state.
 */
public class ExceptionMonad<T, E extends Throwable> {
  private final T value;
  private final E exception;

  /**
   * Protected constructor to initialize the monad with either a value or an exception.
   *
   * @param value The successful result value, or null if it's a failure.
   * @param exception The exception associated with a failure, or null if successful.
   */
  protected ExceptionMonad(T value, E exception) {
    this.value = value;
    this.exception = exception;
  }

  /**
   * Static factory method to create a successful monad with a given value.
   *
   * @param <T> The type of the value.
   * @param <E> The type of exception (not used for success).
   * @param value The success value to wrap.
   * @return A monad representing success with the provided value.
   */
  public static <T, E extends Throwable> ExceptionMonad<T, E> success(T value) {
    return new ExceptionMonad<>(value, null);
  }

  /**
   * Static factory method to create a failed monad with a given exception.
   *
   * @param <T> The type of the value (not used for failure).
   * @param <E> The type of the exception.
   * @param exception The exception to wrap in the failure state.
   * @return A monad representing failure with the provided exception.
   */
  public static <T, E extends Throwable> ExceptionMonad<T, E> failure(E exception) {
    return new ExceptionMonad<>(null, exception);
  }

  /**
   * Applies the given function to the value if the monad is in a success state, returning a new
   * monad with the mapped result.
   *
   * @param <R> The result type after applying the function.
   * @param mapper The function to apply if successful.
   * @return A new monad with the mapped result, or the original failure.
   */
  public <R> ExceptionMonad<R, E> map(Function<T, R> mapper) {
    if (isFailure()) {
      return new ExceptionMonad<>(null, exception);
    }

    return new ExceptionMonad<>(mapper.apply(value), null);
  }

  /**
   * Applies the given function to the value if successful, and flattens the result into a new
   * monad.
   *
   * @param <R> The result type of the nested monad after applying the function.
   * @param mapper The function that returns a new monad if successful.
   * @return A new monad resulting from the application of the function, or the original failure.
   */
  public <R> ExceptionMonad<R, E> flatMap(Function<T, ExceptionMonad<R, E>> mapper) {
    if (isFailure()) {
      return new ExceptionMonad<>(null, exception);
    }

    return mapper.apply(value);
  }

  /**
   * Retries the operation using a supplier up to the specified number of attempts if the monad is
   * in a failure state.
   *
   * @param supplier The supplier that provides new attempts of the operation.
   * @param maxAttempts The maximum number of retry attempts.
   * @return A successful monad if any attempt succeeds, otherwise the last failure.
   */
  public ExceptionMonad<T, E> retry(Supplier<ExceptionMonad<T, E>> supplier, int maxAttempts) {
    if (this.isSuccess() || maxAttempts <= 0) {
      return this;
    }

    ExceptionMonad<T, E> attempt = supplier.get();
    if (attempt.isSuccess()) {
      return attempt;
    }

    return attempt.retry(supplier, maxAttempts - 1);
  }

  /**
   * Retries the operation once using a supplier if the monad is in a failure state.
   *
   * @param supplier The supplier that provides a new attempt of the operation.
   * @return A successful monad if the retry succeeds, otherwise the original failure.
   */
  public ExceptionMonad<T, E> retry(Supplier<ExceptionMonad<T, E>> supplier) {
    return retry(supplier, 1);
  }

  /**
   * Applies one of two functions depending on whether the monad is in a success or failure state.
   *
   * @param <R> The result type of the function.
   * @param onSuccess The function to apply if successful.
   * @param onFailure The function to apply if failed.
   * @return The result of applying the appropriate function based on the state of the monad.
   */
  public <R> R clamp(Function<T, R> onSuccess, Function<E, R> onFailure) {
    if (isSuccess()) {
      return onSuccess.apply(value);
    } else {
      return onFailure.apply(exception);
    }
  }

  /**
   * Retrieves the value if successful, or throws the stored exception if in a failure state.
   *
   * @return The successful value.
   * @throws E The exception stored in the failure state.
   */
  public T getOrThrow() throws E {
    if (exception != null) {
      throw exception;
    }

    return value;
  }

  /**
   * Checks whether the monad is in a success state.
   *
   * @return True if successful, false otherwise.
   */
  public boolean isSuccess() {
    return exception == null;
  }

  /**
   * Checks whether the monad is in a failure state.
   *
   * @return True if failed, false otherwise.
   */
  public boolean isFailure() {
    return exception != null;
  }

  /**
   * Retrieves the exception if in a failure state.
   *
   * @return The exception, or null if successful.
   */
  public E getException() {
    return exception;
  }

  /**
   * Retrieves the successful value, or null if in a failure state.
   *
   * @return The successful value, or null if failed.
   */
  public T get() {
    return value;
  }

  public T unsafeGet() {
    if (exception != null) {
      throw new RuntimeException(exception);
    }

    return value;
  }
}
