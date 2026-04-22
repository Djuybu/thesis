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
package org.apache.arrow.vector;

import com.dremio.common.exceptions.RowSizeLimitExceptionHelper;
import com.dremio.common.exceptions.RowSizeLimitExceptionHelper.RowSizeLimitExceptionType;
import com.dremio.exec.record.VectorContainer;
import com.dremio.exec.record.VectorWrapper;
import com.dremio.sabot.op.join.vhash.spill.slicer.CombinedSizer;
import com.dremio.sabot.op.join.vhash.spill.slicer.Sizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;
import org.apache.arrow.memory.ArrowBuf;

public class VectorContainerHelper {

  private static final long INT_SIZE = 4L;

  /*
  Uses an arrowBuf to accumulate each column's value lengths to get final row sizes. It is caller's responsibility to provide an arrowbuf
  of sufficient size.
   */
  private static void checkForRowSizeOverLimit(
      Supplier<Integer> batchSizeSupplier,
      int recordCount,
      int rowSizeLimit,
      int rowSizeLimitForErrorMessage,
      ArrowBuf rowSizeAccumulator,
      Sizer variableVectorSizer,
      RowSizeLimitExceptionType exceptionType,
      org.slf4j.Logger logger) {
    if (rowSizeAccumulator.capacity() < (long) recordCount * INT_SIZE) { // caller's responsibility
      return;
    }

    if (batchSizeSupplier.get() > rowSizeLimit) {
      for (int index = 0; index < recordCount; index++) {
        rowSizeAccumulator.setInt(index * INT_SIZE, 0);
      }
      variableVectorSizer.accumulateFieldSizesInABuffer(rowSizeAccumulator, recordCount);
      checkForEntryOverLimit(
          rowSizeAccumulator,
          recordCount,
          rowSizeLimit,
          rowSizeLimitForErrorMessage,
          exceptionType,
          logger);
    }
  }

  public static void checkForRowSizeOverLimit(
      VectorContainer container,
      int recordCount,
      int rowSizeLimit,
      int rowSizeLimitForErrorMessage,
      ArrowBuf rowSizeAccumulator,
      Sizer variableVectorSizer,
      RowSizeLimitExceptionType exceptionType,
      org.slf4j.Logger logger) {
    checkForRowSizeOverLimit(
        () -> Sizer.getBatchSizeInBytes(container),
        recordCount,
        rowSizeLimit,
        rowSizeLimitForErrorMessage,
        rowSizeAccumulator,
        variableVectorSizer,
        exceptionType,
        logger);
  }

  /**
   * @param vectors
   * @param recordCount
   * @param rowSizeLimit
   * @param rowSizeLimitForErrorMessage Actual limit checked is only for vectors for which sizer was
   *     created whereas this field is the limit set via support option that should be displayed in
   *     error message.
   * @param rowSizeAccumulator
   * @param variableVectorSizer
   * @param exceptionType
   * @param logger
   */
  public static void checkForRowSizeOverLimit(
      Collection<ValueVector> vectors,
      int recordCount,
      int rowSizeLimit,
      int rowSizeLimitForErrorMessage,
      ArrowBuf rowSizeAccumulator,
      Sizer variableVectorSizer,
      RowSizeLimitExceptionType exceptionType,
      org.slf4j.Logger logger) {
    checkForRowSizeOverLimit(
        () -> Sizer.getBatchSizeInBytes(vectors),
        recordCount,
        rowSizeLimit,
        rowSizeLimitForErrorMessage,
        rowSizeAccumulator,
        variableVectorSizer,
        exceptionType,
        logger);
  }

  private static void checkForEntryOverLimit(
      ArrowBuf rowSizeAccumulator,
      int recordCount,
      int rowSizeLimit,
      int rowSizeLimitForErrorMessage,
      RowSizeLimitExceptionType exceptionType,
      org.slf4j.Logger logger) {
    for (int index = 0; index < recordCount; index++) {
      if (rowSizeAccumulator.getInt(index * 4L) > rowSizeLimit) {
        throw RowSizeLimitExceptionHelper.createRowSizeLimitException(
            rowSizeLimitForErrorMessage, exceptionType, logger);
      }
    }
  }

  public static CombinedSizer createSizer(
      Iterable<ValueVector> valueVectors, boolean includeFixed) {
    List<Sizer> sizerList = new ArrayList<>();

    for (ValueVector vv : valueVectors) {
      if (!includeFixed && vv instanceof BaseFixedWidthVector) {
        continue;
      }
      sizerList.add(Sizer.get(vv));
    }
    return new CombinedSizer(sizerList);
  }

  public static CombinedSizer createSizer(VectorContainer vectorContainer, boolean includeFixed) {
    List<Sizer> sizerList = new ArrayList<>();

    for (VectorWrapper<?> vw : vectorContainer) {
      if (!includeFixed && vw.getValueVector() instanceof BaseFixedWidthVector) {
        continue;
      }
      sizerList.add(Sizer.get(vw.getValueVector()));
    }
    return new CombinedSizer(sizerList);
  }

  public static int getFixedDataLenPerRow(VectorContainer container) {
    int fixedDataLenPerRow = 0;
    for (VectorWrapper<?> vw : container) {
      if (vw.getValueVector() instanceof BaseFixedWidthVector) {
        fixedDataLenPerRow += ((BaseFixedWidthVector) vw.getValueVector()).getTypeWidth();
      }
    }
    return fixedDataLenPerRow;
  }

  public static boolean isVarLenColumnPresent(VectorContainer container) {
    for (VectorWrapper<?> vw : container) {
      if (!(vw.getValueVector() instanceof BaseFixedWidthVector)) {
        return true;
      }
    }
    return false;
  }
}
