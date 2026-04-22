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

import static com.dremio.exec.planner.sql.DmlQueryTestUtils.createBasicTable;
import static com.dremio.exec.planner.sql.DmlQueryTestUtils.createRandomId;
import static com.dremio.exec.planner.sql.DmlQueryTestUtils.testQueryValidateStatusSummary;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dremio.common.exceptions.UserRemoteException;
import org.apache.arrow.memory.BufferAllocator;

/** Alter Iceberg table tests */
public class AlterTests extends ITDmlQueryBase {
  private static final String[] primitiveTypes = {
    "INT",
    "BIGINT",
    "FLOAT",
    "DOUBLE",
    "VARCHAR",
    "VARBINARY",
    "BOOLEAN",
    "DATE",
    "TIME",
    "TIMESTAMP"
  };

  public static void testAddColumnsNameWithDot(BufferAllocator allocator, String source)
      throws Exception {
    try (DmlQueryTestUtils.Table table = createBasicTable(source, 1, 1)) {

      testQueryValidateStatusSummary(
          allocator,
          "ALTER TABLE %s ADD COLUMNS (\"column.with.dot\" int)",
          new Object[] {table.fqn},
          table,
          true,
          "New columns added.",
          null);
    }
  }

  public static void testChangeColumnNameWithDot(BufferAllocator allocator, String source)
      throws Exception {
    try (DmlQueryTestUtils.Table table = createBasicTable(source, 1, 1)) {

      testQueryValidateStatusSummary(
          allocator,
          "ALTER TABLE %s CHANGE COLUMN \"%s\" \"column.with.dot\" INT",
          new Object[] {table.fqn, table.columns[0]},
          table,
          true,
          String.format("Column [%s] modified", table.columns[0]),
          null);
    }
  }

  /** when sorting a table, should sort the table when COLUMN is a primitive type */
  public static void testSortOrderWithPrimitiveNotNullType() throws Exception {
    String tableName = createTableName();
    String sqlQuery = buildCreateQuery(primitiveTypes, false);
    runSQL(sqlQuery, tableName);

    for (int i = 0; i < primitiveTypes.length; i++) {
      test("ALTER TABLE %s LOCALSORT BY (%s)", tableName, "col_" + i);
    }
  }

  /**
   * when sorting a table, should sort the table when COLUMN is a primitive type and NOT NULL
   * (required)
   */
  public static void testSortOrderWithPrimitiveType() throws Exception {
    String tableName = createTableName();
    String sqlQuery = buildCreateQuery(primitiveTypes, true);
    runSQL(sqlQuery, tableName);

    for (int i = 0; i < primitiveTypes.length; i++) {
      test("ALTER TABLE %s LOCALSORT BY (%s)", tableName, "col_" + i);
    }
  }

  /** when sorting a table, should throw an exception when COLUMN is a LIST */
  public static void testSortOrderShouldFailWithListType() throws Exception {
    String tableName = createTableName();
    runSQL("CREATE TABLE %s (col_0 LIST<INT>)", tableName);

    assertThatThrownBy(() -> test("ALTER TABLE %s LOCALSORT BY (col_0)", tableName))
        .isInstanceOf(UserRemoteException.class)
        .hasMessageContaining(localsortErrorMessage("col_0"));
  }

  /** when sorting a table, should throw an exception when COLUMN is a MAP */
  public static void testSortOrderShouldFailWithMapType() throws Exception {
    String tableName = createTableName();
    runSQL("CREATE TABLE %s (col_0 MAP<VARCHAR,INT>)", tableName);

    assertThatThrownBy(() -> test("ALTER TABLE %s LOCALSORT BY (col_0)", tableName))
        .isInstanceOf(UserRemoteException.class)
        .hasMessageContaining(localsortErrorMessage("col_0"));
  }

  /** when sorting a table, should throw an exception when COLUMN is a STRUCT */
  public static void testSortOrderShouldFailWithStructType() throws Exception {
    String tableName = createTableName();
    runSQL("CREATE TABLE %s (col_0 STRUCT<a :VARCHAR>)", tableName);

    assertThatThrownBy(() -> test("ALTER TABLE %s LOCALSORT BY (col_0)", tableName))
        .isInstanceOf(UserRemoteException.class)
        .hasMessageContaining(localsortErrorMessage("col_0"));
  }

  /** should sort the table when COLUMN is partitioned */
  public static void testSortOrderWithPartitionColumn() throws Exception {
    String tableName = createTableName();
    runSQL("CREATE TABLE %s (col_0 INT) PARTITION BY (truncate(2, col_0))", tableName);

    test("ALTER TABLE %s LOCALSORT BY (col_0)", tableName);
  }

  private static String buildCreateQuery(String[] types, boolean notNull) {
    String sqlQuery = "CREATE TABLE %s (";
    for (int i = 0; i < types.length; i++) {
      sqlQuery = sqlQuery + String.format("col_%s %s %s, ", i, types[i], notNull ? "NOT NULL" : "");
    }
    return sqlQuery.substring(0, sqlQuery.length() - 2) + ")";
  }

  private static String createTableName() {
    return TEMP_SCHEMA_HADOOP + "." + createRandomId();
  }

  private static String localsortErrorMessage(String column) {
    return String.format("Cannot perform LOCALSORT operation on a complex column '%s'", column);
  }
}
