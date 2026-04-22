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
package com.dremio.exec.store.iceberg;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.dremio.BaseTestQuery;
import com.dremio.common.exceptions.UserException;
import com.dremio.exec.ExecConstants;
import org.junit.Test;

public class TestIcebergRowLimits extends BaseTestQuery {
  @Test
  public void testRowLimitVarColumns() throws Exception {
    final String tableName = "column_counts";

    final String ctasQuery =
        String.format(
            "CREATE TABLE %s.%s  " + " AS SELECT * from cp.\"parquet/null_test_data.json\"",
            TEMP_SCHEMA, tableName);

    test(ctasQuery);

    try (AutoCloseable ac = withOption(ExecConstants.ENABLE_ROW_SIZE_LIMIT_ENFORCEMENT, true);
        AutoCloseable ac1 = withOption(ExecConstants.LIMIT_ROW_SIZE_BYTES, 28); ) {
      final String selectQuery = String.format("select * from %s.%s", TEMP_SCHEMA, tableName);
      test(selectQuery);
      fail("Query should have throw RowSizeLimitException");
    } catch (UserException e) {
      assertTrue(e.getMessage().contains("Exceeded maximum allowed row size"));
    }
  }

  @Test
  public void testRowLimitComplexColumns() throws Exception {
    final String tableName = "column_counts";

    final String ctasQuery =
        String.format(
            "CREATE TABLE %s.%s  "
                + " AS SELECT * from cp.\"parquet/struct_array_int_bigint.parquet\"",
            TEMP_SCHEMA, tableName);

    test(ctasQuery);

    try (AutoCloseable ac = withOption(ExecConstants.ENABLE_ROW_SIZE_LIMIT_ENFORCEMENT, true);
        AutoCloseable ac1 = withOption(ExecConstants.LIMIT_ROW_SIZE_BYTES, 11); ) {
      final String selectQuery = String.format("select * from %s.%s", TEMP_SCHEMA, tableName);
      test(selectQuery);
      fail("Query should have throw RowSizeLimitException");
    } catch (UserException e) {
      assertTrue(e.getMessage().contains("Exceeded maximum allowed row size"));
    }
  }

  @Test
  public void testRowLimitFixedLenColumns() throws Exception {
    final String tableName = "column_counts";

    final String ctasQuery =
        String.format(
            "CREATE TABLE %s.%s  " + " AS SELECT * from cp.\"parquet/decimals.parquet\"",
            TEMP_SCHEMA, tableName);

    test(ctasQuery);

    try (AutoCloseable ac = withOption(ExecConstants.ENABLE_ROW_SIZE_LIMIT_ENFORCEMENT, true);
        AutoCloseable ac1 = withOption(ExecConstants.LIMIT_ROW_SIZE_BYTES, 15); ) {
      final String selectQuery = String.format("select * from %s.%s", TEMP_SCHEMA, tableName);
      test(selectQuery);
      fail("Query should have throw RowSizeLimitException");
    } catch (UserException e) {
      assertTrue(e.getMessage().contains("Exceeded maximum allowed row size"));
    }
  }
}
