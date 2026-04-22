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
package com.dremio.exec.planner.sql;

import static java.lang.String.format;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.dremio.BaseTestQuery;
import org.junit.Test;

public class TestUnsupportedQueryPlanVisitor extends BaseTestQuery {
  @Test
  public void testIgnoreNulls() throws Exception {
    try {
      String sql =
          "SELECT first_value(c_custkey) ignore nulls over () FROM cp.tpch.\"customer.parquet\"";
      runSQL(sql);
      fail(format("query (%s) should have failed", sql));
    } catch (RuntimeException e) {
      assertTrue(e.getMessage().contains("PLAN ERROR: IGNORE NULLS is not supported."));
    }
  }
}
