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
package com.dremio.exec.store.easy.text.compliant;

import java.io.IOException;

/** Handler for events during the text reading. */
interface TextRecordReaderEventHandler {

  static void raiseIO(Exception error) throws IOException {
    try {
      throw error;
    } catch (IOException | RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

  /**
   * Handles a text reading error.
   *
   * @param fieldIndex the index of the field where the error happened (0-based)
   * @param recordIndexInBatch the index of the record in the current batch where the error happened
   *     (0-based)
   * @param linePosition the index of the line where the error happened (0-based)
   * @param error the actual cause of the reading error
   * @throws IOException might be thrown if the implementation wants to escalate the error (e.g.
   *     abort)
   */
  void handleTextReadingError(
      int fieldIndex, int recordIndexInBatch, long linePosition, Exception error)
      throws IOException;

  /**
   * Handles a record starting event. It is implemented to keep track of the global record/line
   * indices.
   *
   * @param recordIndexInBatch the index of the record in the current batch which parsing is to be
   *     be started (0-based)
   * @param linePosition the index of the line where the record parsing is to be started (0-based)
   */
  default void handleStartingNewRecord(int recordIndexInBatch, long linePosition) {
    // no-op
  }
}
