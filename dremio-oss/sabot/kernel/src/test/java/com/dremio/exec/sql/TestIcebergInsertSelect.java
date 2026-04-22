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
package com.dremio.exec.sql;

import com.dremio.BaseTestQuery;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
import org.junit.Test;

public class TestIcebergInsertSelect extends BaseTestQuery {

  /**
   * Test a streamlined version of DX-92442's original reproducer.
   *
   * <p>Table and column names have been altered, since the originals in the issue reproducer may
   * have been from the customer's data. The syntactic structure of the reproducer is unchanged; the
   * changes here are limited to symbol substitution.
   */
  @Test
  public void testDx92442() throws Exception {
    final String tableNamePrefix = "testDx92442";

    // Create two tables with the same column schema and one row each
    final List<String> fixtureQueries =
        ImmutableList.of(
                "DROP TABLE IF EXISTS %s.%s_a",
                "DROP TABLE IF EXISTS %s.%s_b",
                "DROP TABLE IF EXISTS %s.%s_c",
                "DROP TABLE IF EXISTS %s.%s_d",
                "CREATE TABLE %s.%s_a AS SELECT "
                    + "1 AS x, "
                    + "2 AS y, "
                    + "TIMESTAMPADD(DAY, -1, now()) AS z "
                    + "UNION SELECT "
                    + "2 AS x, 'abc' AS y, "
                    + "TIMESTAMPADD(DAY, +1, now()) AS z",
                "CREATE TABLE %1$s.%2$s_b STORE AS (type => 'iceberg') AS SELECT "
                    + "x, CAST(y AS INTEGER) AS y, z FROM %1$s.%2$s_a WHERE x = 1;",
                "CREATE TABLE %1$s.%2$s_c STORE AS (type => 'iceberg') AS SELECT "
                    + "x, CAST(y AS VARCHAR) AS y, z FROM %1$s.%2$s_a WHERE x = 2;",
                "CREATE TABLE %1$s.%2$s_d PARTITION BY (day(z)) STORE AS (type => 'iceberg') AS SELECT "
                    + "x, y, z FROM %1$s.%2$s_b;")
            .stream()
            .map(q -> String.format(q, TEMP_SCHEMA_HADOOP, tableNamePrefix))
            .collect(Collectors.toList());

    for (String fixtureQuery : fixtureQueries) {
      test(fixtureQuery);
    }

    final String focalQuery =
        String.format(
            "INSERT INTO %1$s.%2$s_d (x, z) SELECT x, z FROM %1$s.%2$s_c;",
            TEMP_SCHEMA_HADOOP, tableNamePrefix);
    try {
      test(focalQuery);
    } finally {
      FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tableNamePrefix));
    }
  }

  /**
   * Similar to {@link #testDx92442()}, but with CTAS statements recast as CREATE-and-INSERT
   * equivalents.
   */
  @Test
  public void insertMismatchedColumns() throws Exception {
    final String tableNamePrefix = "insertMismatchedColumns";

    // Create two tables with the same column schema and one row each
    final List<String> fixtureQueries =
        ImmutableList.of(
                "DROP TABLE IF EXISTS %s.%s_sink",
                "DROP TABLE IF EXISTS %s.%s_source",
                "CREATE TABLE %s.%s_sink (x TIMESTAMP, y INT) PARTITION BY (day(x)) STORE AS (type => 'iceberg')",
                "CREATE TABLE %s.%s_source (x TIMESTAMP, y INT) STORE AS (type => 'iceberg')",
                "INSERT INTO %s.%s_source (x, y) VALUES (TIMESTAMPADD(DAY, -1, now()), 20)")
            .stream()
            .map(q -> String.format(q, TEMP_SCHEMA_HADOOP, tableNamePrefix))
            .collect(Collectors.toList());

    for (String fixtureQuery : fixtureQueries) {
      test(fixtureQuery);
    }

    // Insert the _source table's row into the _sink table, leaving y not explicitly specified
    // The expectation is the query will succeed, since y is nullable
    final String focalQuery =
        String.format(
            "INSERT INTO %1$s.%2$s_sink (x) SELECT x from %1$s.%2$s_source",
            TEMP_SCHEMA_HADOOP, tableNamePrefix);
    try {
      test(focalQuery);
    } finally {
      FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tableNamePrefix));
    }
  }
}
