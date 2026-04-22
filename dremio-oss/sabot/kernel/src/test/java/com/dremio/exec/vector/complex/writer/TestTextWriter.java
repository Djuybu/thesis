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

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.dremio.PlanTestBase;
import com.dremio.common.exceptions.UserException;
import com.dremio.exec.ExecConstants;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TestTextWriter extends PlanTestBase {

  @Rule public TemporaryFolder folder = new TemporaryFolder();

  @Test
  public void testRowSizeLimitException() throws Exception {
    final String testValues = "(values('a'), ('b,2'), ('\"c,3,3\"'), ('d,\"4')) as t(testvals)";
    try (AutoCloseable c = withOption(ExecConstants.ENABLE_ROW_SIZE_LIMIT_ENFORCEMENT, true);
        AutoCloseable c2 = withOption(ExecConstants.LIMIT_ROW_SIZE_BYTES, 100);
        AutoCloseable c3 = withOption(ExecConstants.LIMIT_BATCH_ROW_SIZE_BYTES, 100)) {
      UserException exception =
          assertThrows(
              UserException.class,
              () ->
                  test(
                      "create table dfs_test.text_test STORE AS (type => 'text') WITH SINGLE WRITER AS select trim(testvals) as testvals, repeat('a', 100) from "
                          + testValues));
      assertTrue(
          exception
              .getMessage()
              .contains(
                  "UNSUPPORTED_OPERATION ERROR: Exceeded maximum allowed row size of 100 bytes processing data."));
    }
  }

  @Test
  public void testRowSizeLimitExceptionWithMap() throws Exception {
    try (AutoCloseable c = withOption(ExecConstants.ENABLE_ROW_SIZE_LIMIT_ENFORCEMENT, true);
        AutoCloseable c2 = withOption(ExecConstants.LIMIT_ROW_SIZE_BYTES, 100);
        AutoCloseable c3 = withOption(ExecConstants.LIMIT_BATCH_ROW_SIZE_BYTES, 100)) {
      final String query =
          "create table dfs_test.text_map STORE AS (type => 'text') WITH SINGLE WRITER AS SELECT *,  repeat('a', 100) FROM cp.\"json/map_list_map.json\"";
      UserException exception = assertThrows(UserException.class, () -> test(query));
      assertTrue(
          exception
              .getMessage()
              .contains(
                  "UNSUPPORTED_OPERATION ERROR: Exceeded maximum allowed row size of 100 bytes processing data."));
    }
  }

  @Test
  public void testRowSizeLimitCheckWithStringNull() throws Exception {
    try (AutoCloseable c = withOption(ExecConstants.ENABLE_ROW_SIZE_LIMIT_ENFORCEMENT, true)) {
      final String query =
          "create table dfs_test.text_string STORE AS (type => 'text', fieldDelimiter => ',', extractHeader => true, skipFirstLine => false, autoGenerateColumnNames => true, trimHeader => true) WITH SINGLE WRITER AS select * from cp.\"jsoninput/nullable1.json\"";
      test(query);

      testBuilder()
          .sqlQuery(
              "select * from table(dfs_test.text_string(type => 'text', fieldDelimiter => ',', extractHeader => true, skipFirstLine => false, autoGenerateColumnNames => true, trimHeader => true))")
          .ordered()
          .baselineColumns("a1", "b1")
          .baselineValues("1", "abc")
          .baselineValues("2", "")
          .build()
          .run();
    }
  }

  @Test
  public void testRowSizeLimitCheckWithNumericNull() throws Exception {
    try (AutoCloseable c = withOption(ExecConstants.ENABLE_ROW_SIZE_LIMIT_ENFORCEMENT, true)) {
      final String query =
          "create table dfs_test.text_num STORE AS (type => 'text', fieldDelimiter => ',', extractHeader => true, skipFirstLine => false, autoGenerateColumnNames => true, trimHeader => true) WITH SINGLE WRITER AS select * from cp.\"jsoninput/nullable3.json\"";
      test(query);

      testBuilder()
          .sqlQuery(
              "select * from table(dfs_test.text_num(type => 'text', fieldDelimiter => ',', extractHeader => true, skipFirstLine => false, autoGenerateColumnNames => true, trimHeader => true))")
          .ordered()
          .baselineColumns("a", "b")
          .baselineValues("1", "3")
          .baselineValues("", "3")
          .baselineValues("1", "")
          .build()
          .run();
    }
  }
}
