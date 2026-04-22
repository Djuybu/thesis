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

public class TestTextReader extends PlanTestBase {

  @Test
  public void testRowSizeLimitExceptionForCsv() throws Exception {
    try (AutoCloseable c = withOption(ExecConstants.ENABLE_ROW_SIZE_LIMIT_ENFORCEMENT, true);
        AutoCloseable c2 = withOption(ExecConstants.LIMIT_ROW_SIZE_BYTES, 15)) {
      final String query = "select * from cp.\"csv/nationsWithCapitals.csv\"";
      test(query);
      fail("Query should have throw RowSizeLimitException");
    } catch (UserException e) {
      assertTrue(
          e.getMessage()
              .contains(
                  "UNSUPPORTED_OPERATION ERROR: Exceeded maximum allowed row size of 15 bytes reading data."));
    }
  }

  @Test
  public void testRowSizeLimitCheckWithEmpty() throws Exception {
    try (AutoCloseable c = withOption(ExecConstants.ENABLE_ROW_SIZE_LIMIT_ENFORCEMENT, true)) {
      final String query =
          "select * from TABLE(cp.\"textinput/input_with_null.csv\"(type => 'TEXT', fieldDelimiter => ',', lineDelimiter => '\n', skipFirstLine => false, extractHeader => true))";
      testBuilder()
          .sqlQuery(query)
          .ordered()
          .baselineColumns("id", "date_time", "counts", "values", "name")
          .baselineValues("1", "2009-04-26 19:26:23", "1", "8.1999999999999993", "test")
          .baselineValues("2", "", "1", "8.1999999999999993", "test")
          .baselineValues("3", "2009-04-26 19:26:23", "", "8.1999999999999993", "test")
          .baselineValues("4", "2009-04-26 19:26:23", "1", "", "test")
          .baselineValues("5", "2009-04-26 19:26:23", "1", "8.1999999999999993", null)
          .build()
          .run();
    }
  }
}
