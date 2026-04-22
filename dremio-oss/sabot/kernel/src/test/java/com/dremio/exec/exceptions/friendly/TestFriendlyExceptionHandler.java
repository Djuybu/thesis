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
package com.dremio.exec.exceptions.friendly;

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.exception.friendly.ExceptionHandlers;
import com.dremio.exec.exception.friendly.FriendlyExceptionHandler;
import com.dremio.test.DremioTest;
import java.util.Arrays;
import java.util.List;
import org.apache.arrow.gandiva.exceptions.GandivaException;
import org.junit.Assert;
import org.junit.Test;

public class TestFriendlyExceptionHandler extends DremioTest {
  @Test
  public void testMemoryRelatedExceptionRewrites() {
    // Arrange
    final List<String> oomErrorMessages =
        Arrays.asList(
            "buffer expand failed in java",
            "Buffer overflow for output string",
            "Could not allocate memory");

    // Act & Assert
    testOverriddenExceptions(
        oomErrorMessages,
        "Query was cancelled because it exceeded the memory limits set by the administrator.");
  }

  @Test
  public void testPassingThroughExceptionRewrites() {
    // Arrange
    final List<String> errorMessages =
        Arrays.asList(
            "divide by zero error",
            "Index in split_part must be positive, value provided was <index>",
            "Index to extract out of range",
            "Empty string cannot be converted to <type>",
            "unexpected byte <value> encountered while decoding <encoding> string",
            "Start position must be greater than 0",
            "Error parsing value <value> for given format.",
            "Failed to cast the string <value>",
            "Invalid character in time <value>",
            "Invalid millis for time value <value>",
            "Invalid timestamp or unknown zone for timestamp value",
            "Not a valid date value",
            "Not a valid day for timestamp value",
            "Not a valid time for timestamp value <value>",
            "Failed to cast the string <value> to <type>");

    // Act & Assert
    testPreservedExceptions(errorMessages);
  }

  @Test
  public void testGenericNativeExceptionRewrites() {
    // Arrange
    final List<String> errorMessages =
        List.of(
            "Failed to make LLVM module [2] due to 'like' function requires a literal as the second parameter",
            "Unable to parse condition protobuf",
            "could not finish evp cipher ctx for decryption");

    // Act & Assert
    testOverriddenExceptions(errorMessages, "There was a problem evaluating a native expression");
  }

  @Test
  public void testUnsupportedExceptionHandling() {
    // Arrange
    final List<String> errorMessages = List.of("Some exception");

    // Act & Assert
    testOverriddenExceptions(errorMessages, "Some exception");
  }

  private void testOverriddenExceptions(
      final List<String> errorMessages, final String friendlyMessage) {
    for (String errorMessage : errorMessages) {
      final GandivaException exception = new GandivaException(errorMessage);
      final Exception friendlyException =
          FriendlyExceptionHandler.makeExceptionFriendlyMaybe(
              exception, ExceptionHandlers.getHandlers());

      Assert.assertEquals(friendlyMessage, friendlyException.getMessage());
      Assert.assertEquals(UserException.class, friendlyException.getClass());
    }
  }

  private void testPreservedExceptions(final List<String> errorMessages) {
    for (String errorMessage : errorMessages) {
      final GandivaException exception = new GandivaException(errorMessage);
      final Exception friendlyException =
          FriendlyExceptionHandler.makeExceptionFriendlyMaybe(
              exception, ExceptionHandlers.getHandlers());

      Assert.assertEquals(errorMessage, friendlyException.getMessage());
      Assert.assertEquals(UserException.class, friendlyException.getClass());
    }
  }
}
