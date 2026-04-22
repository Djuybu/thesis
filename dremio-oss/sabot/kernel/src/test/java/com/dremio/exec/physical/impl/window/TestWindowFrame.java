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
package com.dremio.exec.physical.impl.window;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.dremio.BaseTestQueryJunit5;
import com.dremio.common.exceptions.UserRemoteException;
import com.dremio.common.util.TestTools;
import com.dremio.exec.proto.UserBitShared.DremioPBError.ErrorType;
import com.dremio.sabot.op.windowframe.Partition;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class TestWindowFrame extends BaseTestQueryJunit5 {

  private static final String TEST_RES_PATH = TestTools.getWorkingPath() + "/src/test/resources";

  @BeforeAll
  public static void setupDefaultTestCluster() throws Exception {
    BaseTestQueryJunit5.setupDefaultTestCluster();
  }

  @Test
  public void testMultipleFramers() throws Exception {
    final String window = " OVER(PARTITION BY position_id ORDER by sub)";
    test(
        "SELECT COUNT(*)"
            + window
            + ", SUM(salary)"
            + window
            + ", ROW_NUMBER()"
            + window
            + ", RANK()"
            + window
            + " "
            + "FROM dfs.\""
            + TEST_RES_PATH
            + "/window/b1.p1\"");
  }

  private static Stream<Arguments> paramsForMultipleWindowFramerWithNullabilityConstraintTest() {
    return Stream.of(
        Arguments.of(
            TEMP_SCHEMA + ".nullability_bug_int_not_null",
            "col INT NOT NULL",
            Arrays.asList(1, 2, 3)),
        Arguments.of(
            TEMP_SCHEMA + ".nullability_bug_big_int_not_null",
            "col BIGINT NOT NULL",
            Arrays.asList(1L, 2L, 3L)),
        Arguments.of(
            TEMP_SCHEMA + ".nullability_bug_float_not_null",
            "col FLOAT NOT NULL",
            Arrays.asList(1f, 2f, 3f)),
        Arguments.of(
            TEMP_SCHEMA + ".nullability_bug_decimal_not_null",
            "col DECIMAL(10, 0) NOT NULL",
            Arrays.asList(new BigDecimal(1), new BigDecimal(2), new BigDecimal(3))));
  }

  @ParameterizedTest(name = "{index} {0}")
  @MethodSource("paramsForMultipleWindowFramerWithNullabilityConstraintTest")
  public void testMultipleWindowFramerWithNullabilityConstraint(
      String tableSchema, String columnSpec, List<Number> numbers) throws Exception {
    final String baseCreateTableQuery = "CREATE TABLE %s (%s);";
    final String insertQuery = "INSERT INTO %s VALUES (1.0)," + "(2.0)," + "(3.0);";
    final String windowQuery =
        "SELECT col, ROW_NUMBER() OVER() as c_numbers, SUM(1) OVER() as c_sum FROM %s";

    try {
      test(String.format(baseCreateTableQuery, tableSchema, columnSpec));
      test(String.format(insertQuery, tableSchema));
      testBuilder()
          .sqlQuery(String.format(windowQuery, tableSchema))
          .ordered()
          .baselineColumns("col", "c_numbers", "c_sum")
          .baselineValues(numbers.get(0), 1L, 3L)
          .baselineValues(numbers.get(1), 2L, 3L)
          .baselineValues(numbers.get(2), 3L, 3L)
          .go();
    } finally {
      test("DROP TABLE " + tableSchema);
    }
  }

  @Test
  public void testMultipleWindowFramerWithNullabilityConstraintsInTwoFields() throws Exception {
    final List<String> targetTableSchemas =
        List.of(
            TEMP_SCHEMA + ".nullability_bug_one_in_two_fields_not_null",
            TEMP_SCHEMA + ".nullability_bug_two_in_two_fields_not_null");
    final List<String> columnSpecs =
        List.of(
            "c_int INT,          c_dec_not_null DECIMAL(10, 0) NOT NULL",
            "c_int INT NOT NULL, c_dec_not_null DECIMAL(10, 0) NOT NULL");
    final String baseCreateTableQuery = "CREATE TABLE %s (%s);";
    final String insertQuery = "INSERT INTO %s VALUES (1.0, 1.0)," + "(2.0, 2.0)," + "(3.0, 3.0);";
    final String windowQuery =
        "SELECT c_dec_not_null, ROW_NUMBER() OVER() as c_numbers, SUM(1) OVER() as c_sum FROM %s";

    int nFailures = 0;
    StringBuilder exceptionMessages = new StringBuilder();
    for (int i = 0; i < targetTableSchemas.size(); i++) {
      try {
        test(String.format(baseCreateTableQuery, targetTableSchemas.get(i), columnSpecs.get(i)));
        test(String.format(insertQuery, targetTableSchemas.get(i)));
        testBuilder()
            .sqlQuery(String.format(windowQuery, targetTableSchemas.get(i)))
            .ordered()
            .baselineColumns("c_dec_not_null", "c_numbers", "c_sum")
            .baselineValues(BigDecimal.valueOf(1.0), 1L, 3L)
            .baselineValues(BigDecimal.valueOf(2.0), 2L, 3L)
            .baselineValues(BigDecimal.valueOf(3.0), 3L, 3L)
            .go();
      } catch (Exception e) {
        nFailures++;
        exceptionMessages.append(e.getMessage() + "\n");
      } finally {
        test("DROP TABLE " + targetTableSchemas.get(i));
      }
    }
    if (nFailures > 0) {
      fail(
          "Encountered "
              + nFailures
              + " failures out of "
              + targetTableSchemas.size()
              + " tests: "
              + exceptionMessages.toString());
    }
  }

  @Test
  public void testMultipleWindowFramerWithNullabilityConstraintsInFourFields() throws Exception {
    final List<String> targetTableSchemas =
        List.of(
            TEMP_SCHEMA + ".nullability_two_in_four_fields_not_null",
            TEMP_SCHEMA + ".nullability_one_in_four_field_not_null");
    final List<String> columnSpecs =
        List.of(
            "c_int INT, c_dec DECIMAL(10, 0), c_int_not_null INT NOT NULL, c_dec_not_null DECIMAL(10, 0) NOT NULL",
            "c_int INT, c_dec DECIMAL(10, 0), c_int_null     INT,          c_dec_not_null DECIMAL(10, 0) NOT NULL");
    final String baseCreateTableQuery = "CREATE TABLE %s (%s);";
    final String insertQuery =
        "INSERT INTO %s VALUES (1, 1.0, 1, 1.0)," + "(2, 2.0, 2, 2.0)," + "(3, 3.0, 3, 3.0);";
    final String windowQuery =
        "SELECT c_dec_not_null, ROW_NUMBER() OVER() as c_numbers, SUM(1) OVER() as c_sum FROM %s";

    int nFailures = 0;
    StringBuilder exceptionMessages = new StringBuilder();
    for (int i = 0; i < targetTableSchemas.size(); i++) {
      try {
        test(String.format(baseCreateTableQuery, targetTableSchemas.get(i), columnSpecs.get(i)));
        test(String.format(insertQuery, targetTableSchemas.get(i)));
        testBuilder()
            .sqlQuery(String.format(windowQuery, targetTableSchemas.get(i)))
            .ordered()
            .baselineColumns("c_dec_not_null", "c_numbers", "c_sum")
            .baselineValues(BigDecimal.valueOf(1.0), 1L, 3L)
            .baselineValues(BigDecimal.valueOf(2.0), 2L, 3L)
            .baselineValues(BigDecimal.valueOf(3.0), 3L, 3L)
            .go();
      } catch (Exception e) {
        nFailures++;
        exceptionMessages.append(e.getMessage() + "\n");
      } finally {
        test("DROP TABLE " + targetTableSchemas.get(i));
      }
    }
    if (nFailures > 0) {
      fail(
          "Encountered "
              + nFailures
              + " failures out of "
              + targetTableSchemas.size()
              + " tests: "
              + exceptionMessages.toString());
    }
  }

  @Test
  public void testUnboundedFollowing() throws Exception {
    testBuilder()
        .sqlQuery(getFile("window/q3.sql"), TEST_RES_PATH)
        .unOrdered()
        .sqlBaselineQuery(getFile("window/q4.sql"), TEST_RES_PATH)
        .build()
        .run();
  }

  @Test
  public void testAggregateRowsUnboundedAndCurrentRow() throws Exception {
    final String table = "dfs.\"" + TEST_RES_PATH + "/window/b4.p4\"";
    testBuilder()
        .sqlQuery(getFile("window/aggregate_rows_unbounded_current.sql"), table)
        .ordered()
        .sqlBaselineQuery(getFile("window/aggregate_rows_unbounded_current_baseline.sql"), table)
        .build()
        .run();
  }

  @Test
  public void testLastValueRowsUnboundedAndCurrentRow() throws Exception {
    final String table = "dfs.\"" + TEST_RES_PATH + "/window/b4.p4\"";
    testBuilder()
        .sqlQuery(getFile("window/last_value_rows_unbounded_current.sql"), table)
        .unOrdered()
        .sqlBaselineQuery(getFile("window/last_value_rows_unbounded_current_baseline.sql"), table)
        .build()
        .run();
  }

  @Test
  public void testAggregateRangeCurrentAndCurrent() throws Exception {
    final String table = "dfs.\"" + TEST_RES_PATH + "/window/b4.p4\"";
    testBuilder()
        .sqlQuery(getFile("window/aggregate_range_current_current.sql"), table)
        .unOrdered()
        .sqlBaselineQuery(getFile("window/aggregate_range_current_current_baseline.sql"), table)
        .build()
        .run();
  }

  @Test
  public void testFirstValueRangeCurrentAndCurrent() throws Exception {
    final String table = "dfs.\"" + TEST_RES_PATH + "/window/b4.p4\"";
    testBuilder()
        .sqlQuery(getFile("window/first_value_range_current_current.sql"), table)
        .unOrdered()
        .sqlBaselineQuery(getFile("window/first_value_range_current_current_baseline.sql"), table)
        .build()
        .run();
  }

  @Test // DRILL-1862
  public void testEmptyPartitionBy() throws Exception {
    test(
        "SELECT employee_id, position_id, salary, SUM(salary) OVER(ORDER BY position_id) FROM cp.\"employee.json\" LIMIT 10");
  }

  @Test // DRILL-3172
  public void testEmptyOverClause() throws Exception {
    test(
        "SELECT employee_id, position_id, salary, SUM(salary) OVER() FROM cp.\"employee.json\" LIMIT 10");
  }

  @Test // DRILL-3218
  public void testMaxVarChar() throws Exception {
    test(getFile("window/q3218.sql"), TEST_RES_PATH);
  }

  @Test // DRILL-3220
  public void testCountConst() throws Exception {
    test(getFile("window/q3220.sql"), TEST_RES_PATH);
  }

  @Test // DRILL-3604
  public void testFix3604() throws Exception {
    // make sure the query doesn't fail
    test(getFile("window/3604.sql"), TEST_RES_PATH);
  }

  @Test // DRILL-3605
  public void testFix3605() throws Exception {
    testBuilder()
        .sqlQuery(getFile("window/3605.sql"), TEST_RES_PATH)
        .ordered()
        .csvBaselineFile("window/3605.tsv")
        .baselineColumns("col2", "lead_col2")
        .build()
        .run();
  }

  @Test // DRILL-3606
  public void testFix3606() throws Exception {
    testBuilder()
        .sqlQuery(getFile("window/3606.sql"), TEST_RES_PATH)
        .ordered()
        .csvBaselineFile("window/3606.tsv")
        .baselineColumns("col2", "lead_col2")
        .build()
        .run();
  }

  @Test
  public void testLead() throws Exception {
    testBuilder()
        .sqlQuery(getFile("window/lead.oby.sql"), TEST_RES_PATH)
        .ordered()
        .csvBaselineFile("window/b4.p4.lead.oby.tsv")
        .baselineColumns("lead")
        .build()
        .run();
  }

  @Test
  public void testLagWithPby() throws Exception {
    testBuilder()
        .sqlQuery(getFile("window/lag.pby.oby.sql"), TEST_RES_PATH)
        .ordered()
        .csvBaselineFile("window/b4.p4.lag.pby.oby.tsv")
        .baselineColumns("lag")
        .build()
        .run();
  }

  @Test
  public void testLag() throws Exception {
    testBuilder()
        .sqlQuery(getFile("window/lag.oby.sql"), TEST_RES_PATH)
        .ordered()
        .csvBaselineFile("window/b4.p4.lag.oby.tsv")
        .baselineColumns("lag")
        .build()
        .run();
  }

  @Test
  public void testLeadWithPby() throws Exception {
    testBuilder()
        .sqlQuery(getFile("window/lead.pby.oby.sql"), TEST_RES_PATH)
        .ordered()
        .csvBaselineFile("window/b4.p4.lead.pby.oby.tsv")
        .baselineColumns("lead")
        .build()
        .run();
  }

  @Test
  public void testLeadUnderPrecedentOperation() throws Exception {
    test(
        "select 1/(LEAD(n_nationKey, 2) over (partition by n_nationKey order by n_nationKey)) \n"
            + "from cp.\"tpch/nation.parquet\"");
  }

  @Test
  public void testLeadUnderNestedPrecedentOperation() throws Exception {
    test(
        "select 1/(1/(LEAD(n_nationKey, 2) over (partition by n_nationKey order by n_nationKey))) \n"
            + "from cp.\"tpch/nation.parquet\"");
  }

  @Test
  public void testLagUnderPrecedentOperation() throws Exception {
    test(
        "select 1/(LAG(n_nationKey, 2) over (partition by n_nationKey order by n_nationKey)) \n"
            + "from cp.\"tpch/nation.parquet\"");
  }

  @Test
  public void testLagUnderNestedPrecedentOperation() throws Exception {
    test(
        "select 1/(1/(LAG(n_nationKey, 2) over (partition by n_nationKey order by n_nationKey))) \n"
            + "from cp.\"tpch/nation.parquet\"");
  }

  @Test
  public void testFirstValue() throws Exception {
    testBuilder()
        .sqlQuery(getFile("window/fval.pby.sql"), TEST_RES_PATH)
        .ordered()
        .csvBaselineFile("window/b4.p4.fval.pby.tsv")
        .baselineColumns("first_value")
        .build()
        .run();
  }

  @Test
  public void testLastValue() throws Exception {
    testBuilder()
        .sqlQuery(getFile("window/lval.pby.oby.sql"), TEST_RES_PATH)
        .ordered()
        .csvBaselineFile("window/b4.p4.lval.pby.oby.tsv")
        .baselineColumns("last_value")
        .build()
        .run();
  }

  @Test
  public void testFirstValueAllTypes() throws Exception {
    // make sure all types are handled properly
    test(getFile("window/fval.alltypes.sql"), TEST_RES_PATH);
  }

  @Test
  public void testLastValueAllTypes() throws Exception {
    // make sure all types are handled properly
    test(getFile("window/fval.alltypes.sql"), TEST_RES_PATH);
  }

  @Test
  @Disabled // DX-18534
  public void testNtile() throws Exception {
    testBuilder()
        .sqlQuery(getFile("window/ntile.sql"), TEST_RES_PATH)
        .ordered()
        .csvBaselineFile("window/b2.p4.ntile.tsv")
        .baselineColumns("ntile")
        .build()
        .run();
  }

  @Test
  public void test3648Fix() throws Exception {
    testBuilder()
        .sqlQuery(getFile("window/3648.sql"), TEST_RES_PATH)
        .ordered()
        .csvBaselineFile("window/3648.tsv")
        .baselineColumns("ntile")
        .build()
        .run();
  }

  @Test
  public void test3654Fix() throws Exception {
    test(
        "SELECT FIRST_VALUE(col8) OVER(PARTITION BY col7 ORDER BY col8) FROM dfs.\"%s/window/3648.parquet\"",
        TEST_RES_PATH);
  }

  @Test
  public void test3643Fix() throws Exception {
    try {
      test(
          "SELECT NTILE(0) OVER(PARTITION BY col7 ORDER BY col8) FROM dfs.\"%s/window/3648.parquet\"",
          TEST_RES_PATH);
      fail("Query should have failed");
    } catch (UserRemoteException e) {
      assertEquals(ErrorType.FUNCTION, e.getErrorType());
    }
  }

  @Test
  public void test3668Fix() throws Exception {
    // testNoResult("set \"store.parquet.vectorize\" = false");
    testBuilder()
        .sqlQuery(getFile("window/3668.sql"), TEST_RES_PATH)
        .ordered()
        .baselineColumns("cnt")
        .baselineValues(2L)
        .build()
        .run();
  }

  @Test
  public void testLeadParams() throws Exception {
    // make sure we only support default arguments for LEAD/LAG functions
    final String query =
        "SELECT %s OVER(PARTITION BY col7 ORDER BY col8) FROM dfs.\"%s/window/3648.parquet\"";

    test(query, "LEAD(col8, 1)", TEST_RES_PATH);
    test(query, "LAG(col8, 1)", TEST_RES_PATH);
    test(query, "LEAD(col8, 2)", TEST_RES_PATH);
    test(query, "LAG(col8, 2)", TEST_RES_PATH);
  }

  @Test
  public void testPartitionNtile() {
    Partition partition = new Partition();
    partition.updateLength(12, false);

    assertEquals(1, partition.ntile(5));
    partition.rowAggregated();
    assertEquals(1, partition.ntile(5));
    partition.rowAggregated();
    assertEquals(1, partition.ntile(5));

    partition.rowAggregated();
    assertEquals(2, partition.ntile(5));
    partition.rowAggregated();
    assertEquals(2, partition.ntile(5));
    partition.rowAggregated();
    assertEquals(2, partition.ntile(5));

    partition.rowAggregated();
    assertEquals(3, partition.ntile(5));
    partition.rowAggregated();
    assertEquals(3, partition.ntile(5));

    partition.rowAggregated();
    assertEquals(4, partition.ntile(5));
    partition.rowAggregated();
    assertEquals(4, partition.ntile(5));

    partition.rowAggregated();
    assertEquals(5, partition.ntile(5));
    partition.rowAggregated();
    assertEquals(5, partition.ntile(5));
  }

  @Test
  public void test4457() throws Exception {
    runSQL(
        "CREATE TABLE dfs_test.\"4457\" AS "
            + "SELECT columns[0] AS c0, NULLIF(columns[1], 'null') AS c1 "
            + "FROM cp.\"/window/4457.csv\"");

    testBuilder()
        .sqlQuery(
            "SELECT COALESCE(FIRST_VALUE(c1) OVER(ORDER BY c0 RANGE BETWEEN CURRENT ROW AND CURRENT ROW), 'EMPTY') AS fv FROM dfs_test.\"4457\"")
        .ordered()
        .baselineColumns("fv")
        .baselineValues("a")
        .baselineValues("b")
        .baselineValues("EMPTY")
        .go();
  }
}
