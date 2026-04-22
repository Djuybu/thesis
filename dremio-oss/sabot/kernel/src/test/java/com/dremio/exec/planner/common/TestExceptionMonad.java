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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.function.Supplier;
import org.junit.Test;

/** Test for ExceptionMonad */
public class TestExceptionMonad {
  // Example custom exception for testing purposes
  private static final class CustomException extends Exception {
    public CustomException(String message) {
      super(message);
    }
  }

  @Test
  public void testSuccessMonad() {
    ExceptionMonad<String, CustomException> successMonad = ExceptionMonad.success("Success!");

    // Check if it is a success state
    assertTrue(successMonad.isSuccess());
    assertFalse(successMonad.isFailure());

    // Check if the value is retrieved correctly
    assertEquals("Success!", successMonad.get());

    // Check that no exception is present
    assertNull(successMonad.getException());
  }

  @Test
  public void testFailureMonad() {
    CustomException exception = new CustomException("Failure!");
    ExceptionMonad<String, CustomException> failureMonad = ExceptionMonad.failure(exception);

    // Check if it is a failure state
    assertFalse(failureMonad.isSuccess());
    assertTrue(failureMonad.isFailure());

    // Check if the exception is retrieved correctly
    assertEquals(exception, failureMonad.getException());

    // Check that no value is present
    assertNull(failureMonad.get());
  }

  @Test
  public void testMapSuccess() {
    ExceptionMonad<Integer, CustomException> successMonad = ExceptionMonad.success(10);
    ExceptionMonad<Integer, CustomException> mappedMonad = successMonad.map(value -> value * 2);

    // Check if mapping preserves the success state and applies the function
    assertTrue(mappedMonad.isSuccess());
    assertEquals(20, (int) mappedMonad.get());
  }

  @Test
  public void testMapFailure() {
    CustomException exception = new CustomException("Failure!");
    ExceptionMonad<Integer, CustomException> failureMonad = ExceptionMonad.failure(exception);
    ExceptionMonad<Integer, CustomException> mappedMonad = failureMonad.map(value -> value * 2);

    // Check if mapping preserves the failure state and does not apply the function
    assertTrue(mappedMonad.isFailure());
    assertNull(mappedMonad.get());
  }

  @Test
  public void testFlatMapSuccess() {
    ExceptionMonad<Integer, CustomException> successMonad = ExceptionMonad.success(5);
    ExceptionMonad<Integer, CustomException> flatMappedMonad =
        successMonad.flatMap(value -> ExceptionMonad.success(value * 2));

    // Check if flatMapping works for success case
    assertTrue(flatMappedMonad.isSuccess());
    assertEquals(10, (int) flatMappedMonad.get());
  }

  @Test
  public void testFlatMapFailure() {
    CustomException exception = new CustomException("Failure!");
    ExceptionMonad<Integer, CustomException> failureMonad = ExceptionMonad.failure(exception);
    ExceptionMonad<Integer, CustomException> flatMappedMonad =
        failureMonad.flatMap(value -> ExceptionMonad.success(value * 2));

    // Check if flatMapping preserves the failure state
    assertTrue(flatMappedMonad.isFailure());
    assertNull(flatMappedMonad.get());
  }

  @Test
  public void testRetrySuccess() {
    Supplier<ExceptionMonad<Integer, CustomException>> successSupplier =
        () -> ExceptionMonad.success(42);

    ExceptionMonad<Integer, CustomException> monad =
        ExceptionMonad.failure(new CustomException("Initial Failure"));
    monad = monad.retry(successSupplier, 3);

    // Check if retry eventually succeeds
    assertTrue(monad.isSuccess());
    assertEquals(42, (int) monad.get());
  }

  @Test
  public void testRetryFailure() {
    Supplier<ExceptionMonad<Integer, CustomException>> failureSupplier =
        () -> ExceptionMonad.failure(new CustomException("Still Failing"));

    ExceptionMonad<Integer, CustomException> monad =
        ExceptionMonad.failure(new CustomException("Initial Failure"));
    monad = monad.retry(failureSupplier, 3);

    // Check if retry continues to fail after all attempts
    assertTrue(monad.isFailure());
    assertEquals("Still Failing", monad.getException().getMessage());
  }

  @Test
  public void testClampSuccess() {
    ExceptionMonad<Integer, CustomException> successMonad = ExceptionMonad.success(10);

    String result =
        successMonad.clamp(value -> "Success: " + value, ex -> "Failure: " + ex.getMessage());

    // Check if the success function is applied correctly
    assertEquals("Success: 10", result);
  }

  @Test
  public void testClampFailure() {
    ExceptionMonad<Integer, CustomException> failureMonad =
        ExceptionMonad.failure(new CustomException("Failure"));

    String result =
        failureMonad.clamp(value -> "Success: " + value, ex -> "Failure: " + ex.getMessage());

    // Check if the failure function is applied correctly
    assertEquals("Failure: Failure", result);
  }

  @Test
  public void testGetOrThrowSuccess() throws CustomException {
    ExceptionMonad<Integer, CustomException> successMonad = ExceptionMonad.success(42);

    // Should not throw an exception, should return the value
    assertEquals(42, (int) successMonad.getOrThrow());
  }

  @Test
  public void testGetOrThrowFailure() {
    ExceptionMonad<Integer, CustomException> failureMonad =
        ExceptionMonad.failure(new CustomException("Failure"));

    // Should throw the exception
    CustomException thrown = assertThrows(CustomException.class, failureMonad::getOrThrow);
    assertEquals("Failure", thrown.getMessage());
  }
}
