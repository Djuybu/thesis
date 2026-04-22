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
package com.dremio.exec.vector.complex.writer;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.dremio.PlanTestBase;
import com.dremio.common.exceptions.UserException;
import com.dremio.exec.ExecConstants;
import org.junit.Test;

public class TestExcelReader extends PlanTestBase {

  @Test
  public void testRowSizeLimitExceptionForXls() throws Exception {
    try (AutoCloseable c = withOption(ExecConstants.ENABLE_ROW_SIZE_LIMIT_ENFORCEMENT, true);
        AutoCloseable c2 = withOption(ExecConstants.LIMIT_ROW_SIZE_BYTES, 7)) {
      final String query = "select * from cp.\"excel/simple.xls\"";
      test(query);
      fail("Query should have throw RowSizeLimitException");
    } catch (UserException e) {
      assertTrue(
          e.getMessage()
              .contains(
                  "UNSUPPORTED_OPERATION ERROR: Exceeded maximum allowed row size of 7 bytes reading data."));
    }
  }

  @Test
  public void testRowSizeLimitExceptionForXlsx() throws Exception {
    try (AutoCloseable c = withOption(ExecConstants.ENABLE_ROW_SIZE_LIMIT_ENFORCEMENT, true);
        AutoCloseable c2 = withOption(ExecConstants.LIMIT_ROW_SIZE_BYTES, 7)) {
      final String query = "select * from cp.\"excel/simple.xlsx\"";
      test(query);
      fail("Query should have throw RowSizeLimitException");
    } catch (UserException e) {
      assertTrue(
          e.getMessage()
              .contains(
                  "UNSUPPORTED_OPERATION ERROR: Exceeded maximum allowed row size of 7 bytes reading data."));
    }
  }

  @Test
  public void testRowSizeLimitCheckWithNull() throws Exception {
    try (AutoCloseable c = withOption(ExecConstants.ENABLE_ROW_SIZE_LIMIT_ENFORCEMENT, true)) {
      final String query = "select * from cp.\"excel/simple_null.xlsx\"";
      testBuilder()
          .sqlQuery(query)
          .ordered()
          .baselineColumns("A", "B", "C", "D", "E", "F", "G")
          .baselineValues("First_Name", "Last_Name", "Gender", "Country", "Age", "Date", "Id")
          .baselineValues(
              "Kathleen", "Abril", "Female", "United States", "32.0", "15/10/2017", "1562.0")
          .baselineValues("Mara", null, "Female", "Great Britain", "25.0", "16/08/2016", "1582.0")
          .baselineValues("Philip", "Gent", null, "France", "36.0", "21/05/2015", "2587.0")
          .baselineValues(null, "Hanner", "Female", null, "25.0", "15/10/2017", "3549.0")
          .baselineValues(
              "Nereida", "Magwood", "Female", "United States", null, "16/08/2016", "2468.0")
          .baselineValues("Gaston", "Brumm", "Male", "United States", "24.0", null, "2554.0")
          .baselineValues("Etta", "Hurn", "Female", "Great Britain", "56.0", "15/10/2017", null)
          .baselineValues(
              "Earlean", "Melgar", "Female", "United States", "27.0", "16/08/2016", "2456.0")
          .baselineValues(
              "Vincenza", "Weiland", "Female", "United States", "40.0", "21/05/2015", "6548.0")
          .build()
          .run();
    }
  }
}
