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
package org.apache.parquet.internal.column.columnindex;

/**
 * A helper class to extend ColumnIndex to expose the getMinValueAsBytes and getMaxValueAsBytes
 * methods. This enables to iterate the page stats without incurring unnecessary memory allocation
 * and de-allocation.
 *
 * @link org.apache.parquet.internal.column.columnindex.ColumnIndex} in parquet-mr is
 *     package-private.
 */
import java.nio.ByteBuffer;
import org.apache.parquet.internal.column.columnindex.ColumnIndexBuilder.ColumnIndexBase;

public class ColumnIndexExtender {

  private static final ByteBuffer EMPTY = ByteBuffer.allocate(0);

  public static ByteBuffer getMinValueAsBytes(ColumnIndex columnIndex, int index) {
    ColumnIndexBase<?> columnIndexBase = (ColumnIndexBase<?>) columnIndex;
    return columnIndexBase.getMinValueAsBytes(index);
  }

  public static ByteBuffer getMaxValueAsBytes(ColumnIndex columnIndex, int index) {
    ColumnIndexBase<?> columnIndexBase = (ColumnIndexBase<?>) columnIndex;
    return columnIndexBase.getMaxValueAsBytes(index);
  }
}
