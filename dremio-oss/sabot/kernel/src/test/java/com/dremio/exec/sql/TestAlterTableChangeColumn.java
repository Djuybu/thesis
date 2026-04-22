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

import static org.assertj.core.api.Assertions.assertThatCode;

import com.dremio.BaseTestQuery;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.store.iceberg.model.IcebergModel;
import com.dremio.test.UserExceptionAssert;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.FileUtils;
import org.apache.iceberg.Table;
import org.junit.Assert;
import org.junit.Test;

public class TestAlterTableChangeColumn extends BaseTestQuery {

  @Test
  public void badSql() {
    String[] queries = {
      "ALTER TABLE tbl CHANGE ",
      "ALTER TABLE CHANGE col1 col2 varchar int",
      "ALTER TABLE %s.%s CHANGE COLUMN version commit_message varchar",
      "ALTER TABLE CHANGE col1 col2 varchar"
    };
    for (String q : queries) {
      UserExceptionAssert.assertThatThrownBy(() -> test(q))
          .hasErrorType(UserBitShared.DremioPBError.ErrorType.PARSE);
    }
  }

  @Test
  public void renameToExistingColumn() throws Exception {
    for (String testSchema : SCHEMAS_FOR_TEST) {
      String tableName = "changecol1";
      try {

        final String createTableQuery =
            String.format(
                "CREATE TABLE %s.%s as select * from INFORMATION_SCHEMA.CATALOGS",
                testSchema, tableName);
        test(createTableQuery);
        Thread.sleep(1001);

        String query =
            String.format(
                "ALTER TABLE %s.%s CHANGE CATALOG_NAME CATALOG_DESCRIPTION varchar",
                testSchema, tableName);
        errorMsgTestHelper(
            query,
            "Column [CATALOG_DESCRIPTION] already present in table ["
                + testSchema
                + ".changecol1]");

      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tableName));
      }
    }
  }

  @Test
  public void renameToSameColumnAndSameType() throws Exception {
    for (String testSchema : SCHEMAS_FOR_TEST) {
      String tableName = "changesamecol1";
      File tableFolder = new File(getDfsTestTmpSchemaLocation(), tableName);
      try {
        final String createTableQuery =
            String.format(
                "CREATE TABLE %s.%s as select * from INFORMATION_SCHEMA.CATALOGS",
                testSchema, tableName);
        test(createTableQuery);
        Thread.sleep(1001);

        IcebergModel icebergModel = getIcebergModel(testSchema);
        Table icebergTable = getIcebergTable(icebergModel, tableFolder);
        String currentRootPointer =
            icebergModel
                .getIcebergTableLoader(icebergModel.getTableIdentifier(tableFolder.getPath()))
                .getRootPointer();
        Assert.assertEquals("v1.metadata.json", new File(currentRootPointer).getName());

        // Query to test that changing column name to the same name and type does not change
        // as it returns early without doing modifications to Iceberg table metadata.
        String queryDoesNotChangeMetadata =
            String.format(
                "ALTER TABLE %s.%s CHANGE CATALOG_NAME CATALOG_NAME varchar",
                testSchema, tableName);
        assertThatCode(() -> testNoResult(queryDoesNotChangeMetadata)).doesNotThrowAnyException();
        icebergTable.refresh();
        currentRootPointer =
            icebergModel
                .getIcebergTableLoader(icebergModel.getTableIdentifier(tableFolder.getPath()))
                .getRootPointer();
        Assert.assertEquals("v1.metadata.json", new File(currentRootPointer).getName());
        Assert.assertTrue(icebergTable.schema().findField("CATALOG_NAME") != null);

        // Check that renaming a column does indeed modify Iceberg table metadata.
        String queryChangesMetadata =
            String.format(
                "ALTER TABLE %s.%s CHANGE CATALOG_NAME CATALOG_NAME_VARCHAR varchar",
                testSchema, tableName);
        assertThatCode(() -> testNoResult(queryChangesMetadata)).doesNotThrowAnyException();
        icebergTable.refresh();
        currentRootPointer =
            icebergModel
                .getIcebergTableLoader(icebergModel.getTableIdentifier(tableFolder.getPath()))
                .getRootPointer();
        Assert.assertEquals("v2.metadata.json", new File(currentRootPointer).getName());
        Assert.assertTrue(
            icebergTable.schema().findField("CATALOG_NAME_VARCHAR") != null
                && icebergTable.schema().findField("CATALOG_NAME") == null);
      } finally {
        FileUtils.deleteQuietly(tableFolder);
      }
    }
  }

  @Test
  public void invalidType() throws Exception {
    for (String testSchema : SCHEMAS_FOR_TEST) {
      String tableName = "changecol2";
      try {

        final String createTableQuery =
            String.format(
                "CREATE TABLE %s.%s as select * from INFORMATION_SCHEMA.CATALOGS",
                testSchema, tableName);
        test(createTableQuery);
        Thread.sleep(1001);

        String query =
            String.format(
                "ALTER TABLE %s.%s CHANGE CATALOG_NAME commit_message2 varchare",
                testSchema, tableName);
        errorMsgTestHelper(query, "Invalid column type [`varchare`] specified");

      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tableName));
      }
    }
  }

  @Test
  public void invalidPromoteStringToInt() throws Exception {
    for (String testSchema : SCHEMAS_FOR_TEST) {
      String tableName = "changecol3";
      try {

        final String createTableQuery =
            String.format(
                "CREATE TABLE %s.%s as select * from INFORMATION_SCHEMA.CATALOGS",
                testSchema, tableName);
        test(createTableQuery);
        Thread.sleep(1001);

        String query =
            String.format(
                "ALTER TABLE %s.%s CHANGE CATALOG_NAME commit_message2 int", testSchema, tableName);
        errorMsgTestHelper(
            query, "Cannot change data type of column [CATALOG_NAME] from VARCHAR to INTEGER");

      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tableName));
      }
    }
  }

  @Test
  public void rename() throws Exception {
    for (String testSchema : SCHEMAS_FOR_TEST) {
      String tableName = "changecol4";
      try {

        final String createTableQuery =
            String.format(
                "CREATE TABLE %s.%s as "
                    + "SELECT n_regionkey from cp.\"tpch/nation.parquet\" where n_regionkey < 2 GROUP BY n_regionkey ",
                testSchema, tableName);
        test(createTableQuery);

        final String selectFromCreatedTable =
            String.format("select * from %s.%s", testSchema, tableName);
        testBuilder()
            .sqlQuery(selectFromCreatedTable)
            .unOrdered()
            .baselineColumns("n_regionkey")
            .baselineValues(0)
            .baselineValues(1)
            .build()
            .run();

        Thread.sleep(1001);
        String renameQuery =
            String.format(
                "ALTER TABLE %s.%s CHANGE n_regionkey regionkey int", testSchema, tableName);
        test(renameQuery);

        testBuilder()
            .sqlQuery(selectFromCreatedTable)
            .unOrdered()
            .baselineColumns("regionkey")
            .baselineValues(0)
            .baselineValues(1)
            .build()
            .run();

      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tableName));
      }
    }
  }

  @Test
  public void renamePrimitiveNonNullableColumn() throws Exception {
    for (String testSchema : SCHEMAS_FOR_TEST) {
      String tableName = "alterTablePrimitiveTypesWithNullability";
      Object[][] columnNamesAndTypes = {
        {"c_boolean", "new_c_boolean", "BOOLEAN", "NOT NULL,"},
        {"c_varbinary", "new_c_varbinary", "VARBINARY", "NOT NULL,"},
        {"c_date", "new_c_date", "DATE", "NOT NULL,"},
        {"c_float", "new_c_float", "FLOAT", "NOT NULL,"},
        {"c_double", "new_c_double", "DOUBLE", "NOT NULL,"},
        {"c_decimal", "new_c_decimal", "DECIMAL(10, 2)", "NOT NULL,"},
        {"c_time", "new_c_time", "TIME", "NOT NULL,"},
        {"c_timestamp", "new_c_timestamp", "TIMESTAMP", "NOT NULL,"},
        {"c_varchar", "new_c_varchar", "VARCHAR", "NOT NULL,"},
        {"c_int", "new_c_int", "INT", "NOT NULL,"},
        {"c_bigint", "new_c_bigint", "BIGINT", "NOT NULL"}
      };
      try {
        StringBuilder createTableQuery = new StringBuilder();
        createTableQuery.append(String.format("CREATE TABLE %s.%s (", testSchema, tableName));
        Arrays.stream(columnNamesAndTypes)
            .forEach(
                column -> {
                  createTableQuery.append(
                      String.format(" %s %s %s ", column[0], column[2], column[3]));
                });
        createTableQuery.append(");");
        test(createTableQuery.toString());

        // Rename the columns.
        StringBuilder alterTableQuery = new StringBuilder();
        Arrays.stream(columnNamesAndTypes)
            .forEach(
                column -> {
                  alterTableQuery.append(
                      String.format(
                          "ALTER TABLE %s.%s CHANGE %s %s %s;",
                          testSchema, tableName, column[0], column[1], column[2]));
                });
        test(alterTableQuery.toString());

        // Check that the column was renamed and the nullability is preserved.
        String describeQuery = String.format("DESCRIBE %s.%s", testSchema, tableName);
        String[] baselineCols =
            new String[] {
              "`COLUMN_NAME`",
              "`DATA_TYPE`",
              "`IS_NULLABLE`",
              "`NUMERIC_PRECISION`",
              "`NUMERIC_SCALE`",
              "`EXTENDED_PROPERTIES`",
              "`MASKING_POLICY`",
              "`SORT_ORDER_PRIORITY`"
            };
        Object[][] baselineValues = {
          {"new_c_boolean", "BOOLEAN", "NO", null, null, "[]", null, null},
          {"new_c_varbinary", "BINARY VARYING", "NO", null, null, "[]", null, null},
          {"new_c_date", "DATE", "NO", null, null, "[]", null, null},
          {"new_c_float", "FLOAT", "NO", 24, null, "[]", null, null},
          {"new_c_double", "DOUBLE", "NO", 53, null, "[]", null, null},
          {"new_c_decimal", "DECIMAL", "NO", 10, 2, "[]", null, null},
          {"new_c_time", "TIME", "NO", null, null, "[]", null, null},
          {"new_c_timestamp", "TIMESTAMP", "NO", null, null, "[]", null, null},
          {"new_c_varchar", "CHARACTER VARYING", "NO", null, null, "[]", null, null},
          {"new_c_int", "INTEGER", "NO", 32, 0, "[]", null, null},
          {"new_c_bigint", "BIGINT", "NO", 64, 0, "[]", null, null}
        };
        List<Map<String, Object>> baselineRecords =
            prepareBaselineRecords(baselineCols, baselineValues);
        testBuilder()
            .sqlQuery(describeQuery)
            .baselineColumns(baselineCols)
            .unOrdered()
            .baselineRecords(baselineRecords)
            .go();

        // Alter all columns by dropping NOT NULL.
        StringBuilder alterTableQueryDropNotNull = new StringBuilder();
        Arrays.stream(columnNamesAndTypes)
            .forEach(
                column -> {
                  alterTableQueryDropNotNull.append(
                      String.format(
                          "ALTER TABLE %s.%s CHANGE %s %s %s DROP NOT NULL;",
                          testSchema, tableName, column[1], column[1], column[2]));
                });
        test(alterTableQueryDropNotNull.toString());

        // Baseline columns are nullable to check that the columns are now nullable.
        Object[][] baselineValuesAfterChange =
            Arrays.stream(baselineValues)
                .map(
                    row -> {
                      row[2] = "YES";
                      return row;
                    })
                .toArray(Object[][]::new);
        List<Map<String, Object>> baselineRecordsAfterChange =
            prepareBaselineRecords(baselineCols, baselineValuesAfterChange);
        testBuilder()
            .sqlQuery(describeQuery)
            .baselineColumns(baselineCols)
            .unOrdered()
            .baselineRecords(baselineRecordsAfterChange)
            .go();
      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tableName));
      }
    }
  }

  @Test
  public void complexColumnDropNotNull() throws Exception {
    for (String testSchema : SCHEMAS_FOR_TEST) {
      String tableName = "alterTableComplexTypesWithNullability";
      Object[][] columnNamesAndTypes = {
        {"c_array_int", "ARRAY<INT NOT NULL>", "NOT NULL,"},
        {"c_struct_int", "STRUCT<x: INT NOT NULL>", "NOT NULL"},
      };

      try {
        StringBuilder createTableQuery = new StringBuilder();
        createTableQuery.append(String.format("CREATE TABLE %s.%s (", testSchema, tableName));
        Arrays.stream(columnNamesAndTypes)
            .forEach(
                column -> {
                  createTableQuery.append(
                      String.format(" %s %s %s ", column[0], column[1], column[2]));
                });
        createTableQuery.append(");");
        test(createTableQuery.toString());

        String describeQuery = String.format("DESCRIBE %s.%s", testSchema, tableName);
        String[] baselineCols =
            new String[] {
              "`COLUMN_NAME`",
              "`DATA_TYPE`",
              "`IS_NULLABLE`",
              "`NUMERIC_PRECISION`",
              "`NUMERIC_SCALE`",
              "`EXTENDED_PROPERTIES`",
              "`MASKING_POLICY`",
              "`SORT_ORDER_PRIORITY`"
            };
        Object[][] baselineValues = {
          {"c_array_int", "ARRAY", "NO", null, null, "[]", null, null},
          {"c_struct_int", "ROW", "NO", null, null, "[]", null, null},
        };

        List<Map<String, Object>> baselineRecords =
            prepareBaselineRecords(baselineCols, baselineValues);
        testBuilder()
            .sqlQuery(describeQuery)
            .baselineColumns(baselineCols)
            .unOrdered()
            .baselineRecords(baselineRecords)
            .go();

        StringBuilder alterTableQueryDropNotNull = new StringBuilder();
        Arrays.stream(columnNamesAndTypes)
            .forEach(
                column -> {
                  alterTableQueryDropNotNull.append(
                      String.format(
                          "ALTER TABLE %s.%s CHANGE %s %s %s DROP NOT NULL;",
                          testSchema, tableName, column[0], column[0], column[1]));
                });
        test(alterTableQueryDropNotNull.toString());

        // Baseline columns are nullable to check that the columns are now nullable.
        Object[][] baselineValuesAfterChange =
            Arrays.stream(baselineValues)
                .map(
                    row -> {
                      row[2] = "YES";
                      return row;
                    })
                .toArray(Object[][]::new);
        List<Map<String, Object>> baselineRecordsAfterChange =
            prepareBaselineRecords(baselineCols, baselineValuesAfterChange);
        testBuilder()
            .sqlQuery(describeQuery)
            .baselineColumns(baselineCols)
            .unOrdered()
            .baselineRecords(baselineRecordsAfterChange)
            .go();
      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tableName));
      }
    }
  }

  @Test
  public void uppromote() throws Exception {
    for (String testSchema : SCHEMAS_FOR_TEST) {
      String tableName = "changecol5";
      try {

        final String createTableQuery =
            String.format(
                "CREATE TABLE %s.%s as "
                    + "SELECT n_regionkey from cp.\"tpch/nation.parquet\" where n_regionkey < 2 GROUP BY n_regionkey ",
                testSchema, tableName);
        test(createTableQuery);

        final String selectFromCreatedTable =
            String.format("select * from %s.%s", testSchema, tableName);
        testBuilder()
            .sqlQuery(selectFromCreatedTable)
            .unOrdered()
            .baselineColumns("n_regionkey")
            .baselineValues(0)
            .baselineValues(1)
            .build()
            .run();

        Thread.sleep(1001);
        String uppromote =
            String.format(
                "ALTER TABLE %s.%s CHANGE n_regionkey regionkey bigint", testSchema, tableName);
        test(uppromote);

        testBuilder()
            .sqlQuery(selectFromCreatedTable)
            .unOrdered()
            .baselineColumns("regionkey")
            .baselineValues(0L)
            .baselineValues(1L)
            .build()
            .run();

      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tableName));
      }
    }
  }

  @Test
  public void renameFollowedByUppromote() throws Exception {
    for (String testSchema : SCHEMAS_FOR_TEST) {
      String tableName = "changecol6";
      try {

        final String createTableQuery =
            String.format(
                "CREATE TABLE %s.%s as "
                    + "SELECT n_regionkey from cp.\"tpch/nation.parquet\" where n_regionkey < 2 GROUP BY n_regionkey ",
                testSchema, tableName);
        test(createTableQuery);

        final String selectFromCreatedTable =
            String.format("select * from %s.%s", testSchema, tableName);
        testBuilder()
            .sqlQuery(selectFromCreatedTable)
            .unOrdered()
            .baselineColumns("n_regionkey")
            .baselineValues(0)
            .baselineValues(1)
            .build()
            .run();

        Thread.sleep(1001);
        String renameQuery =
            String.format(
                "ALTER TABLE %s.%s CHANGE n_regionkey regionkey int", testSchema, tableName);
        test(renameQuery);

        testBuilder()
            .sqlQuery(selectFromCreatedTable)
            .unOrdered()
            .baselineColumns("regionkey")
            .baselineValues(0)
            .baselineValues(1)
            .build()
            .run();

        Thread.sleep(1001);
        String uppromote =
            String.format(
                "ALTER TABLE %s.%s CHANGE regionkey regionkey bigint", testSchema, tableName);
        test(uppromote);

        testBuilder()
            .sqlQuery(selectFromCreatedTable)
            .unOrdered()
            .baselineColumns("regionkey")
            .baselineValues(0L)
            .baselineValues(1L)
            .build()
            .run();

      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tableName));
      }
    }
  }

  @Test
  public void renameAndUppromote() throws Exception {
    for (String testSchema : SCHEMAS_FOR_TEST) {
      String tableName = "changecol7";
      try {

        final String createTableQuery =
            String.format(
                "CREATE TABLE %s.%s as "
                    + "SELECT n_regionkey from cp.\"tpch/nation.parquet\" where n_regionkey < 2 GROUP BY n_regionkey ",
                testSchema, tableName);
        test(createTableQuery);

        final String selectFromCreatedTable =
            String.format("select * from %s.%s", testSchema, tableName);
        testBuilder()
            .sqlQuery(selectFromCreatedTable)
            .unOrdered()
            .baselineColumns("n_regionkey")
            .baselineValues(0)
            .baselineValues(1)
            .build()
            .run();

        Thread.sleep(1001);
        String renameQuery =
            String.format(
                "ALTER TABLE %s.%s CHANGE n_regionkey regionkey bigint", testSchema, tableName);
        test(renameQuery);

        testBuilder()
            .sqlQuery(selectFromCreatedTable)
            .unOrdered()
            .baselineColumns("regionkey")
            .baselineValues(0L)
            .baselineValues(1L)
            .build()
            .run();

      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tableName));
      }
    }
  }

  @Test
  public void renamePartitionColumn() throws Exception {
    for (String testSchema : SCHEMAS_FOR_TEST) {
      String tableName = "changecol8";
      try {

        final String createTableQuery =
            String.format(
                "CREATE TABLE %s.%s PARTITION BY (n_regionkey) as "
                    + "SELECT n_regionkey from cp.\"tpch/nation.parquet\" where n_regionkey < 2 GROUP BY n_regionkey ",
                testSchema, tableName);
        test(createTableQuery);

        final String selectFromCreatedTable =
            String.format("select * from %s.%s", testSchema, tableName);
        testBuilder()
            .sqlQuery(selectFromCreatedTable)
            .unOrdered()
            .baselineColumns("n_regionkey")
            .baselineValues(0)
            .baselineValues(1)
            .build()
            .run();

        Thread.sleep(1001);
        String addColQuery =
            String.format("ALTER TABLE %s.%s ADD COLUMNS(key int)", testSchema, tableName);
        test(addColQuery);

        testBuilder()
            .sqlQuery(selectFromCreatedTable)
            .unOrdered()
            .baselineColumns("n_regionkey", "key")
            .baselineValues(0, null)
            .baselineValues(1, null)
            .build()
            .run();

        Thread.sleep(1001);
        String dropColQuery =
            String.format(
                "ALTER TABLE %s.%s CHANGE n_RegiOnkey regionkey int", testSchema, tableName);
        errorMsgTestHelper(
            dropColQuery,
            "[n_regionkey] is a partition column. Partition spec change is not supported.");

      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tableName));
      }
    }
  }

  @Test
  public void noContextFail() throws Exception {
    for (String testSchema : SCHEMAS_FOR_TEST) {
      String tableName = "changecol9";
      try {

        final String createTableQuery =
            String.format(
                "CREATE TABLE %s.%s as "
                    + "SELECT n_regionkey from cp.\"tpch/nation.parquet\" where n_regionkey < 2 GROUP BY n_regionkey ",
                testSchema, tableName);
        test(createTableQuery);

        final String selectFromCreatedTable =
            String.format("select * from %s.%s", testSchema, tableName);
        testBuilder()
            .sqlQuery(selectFromCreatedTable)
            .unOrdered()
            .baselineColumns("n_regionkey")
            .baselineValues(0)
            .baselineValues(1)
            .build()
            .run();

        Thread.sleep(1001);
        String changeColQuery =
            String.format("ALTER TABLE %s CHANGE n_RegiOnkey regionkey int", tableName);
        errorMsgTestHelper(changeColQuery, "Table [changecol9] does not exist");
      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tableName));
      }
    }
  }

  @Test
  public void messageHasPrecAndScale() throws Exception {
    for (String testSchema : SCHEMAS_FOR_TEST) {
      String tableName = "changecol10";
      try {

        final String createTableQuery =
            String.format("CREATE TABLE %s.%s (dec1 DECIMAL(10,2))", testSchema, tableName);
        test(createTableQuery);
        String changeColQuery =
            String.format(
                "ALTER TABLE %s.%s CHANGE dec1 dec1 decimal(11, 3)", testSchema, tableName);
        errorMsgTestHelper(
            changeColQuery,
            "Cannot change data type of column [dec1] from DECIMAL(10, 2) to DECIMAL(11, 3)");
      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tableName));
      }
    }
  }

  @Test
  public void addExistingColumnInStruct() throws Exception {
    for (String testSchema : SCHEMAS_FOR_TEST) {
      String tableName = "structcol";
      try {

        final String createTableQuery =
            String.format(
                "CREATE TABLE %s.%s as select * from INFORMATION_SCHEMA.CATALOGS",
                testSchema, tableName);
        test(createTableQuery);
        Thread.sleep(1001);

        String query =
            String.format(
                "ALTER TABLE %s.%s CHANGE COLUMN CATALOG_NAME CATALOG_NAME ROW(col1 int,COL1 double)",
                testSchema, tableName);
        errorMsgTestHelper(query, "Column [COL1] specified multiple times.");
      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tableName));
      }
    }
  }

  @Test
  public void addExistingColumnInListOfStruct() throws Exception {
    for (String testSchema : SCHEMAS_FOR_TEST) {
      String tableName = "listofstructcol";
      try {

        final String createTableQuery =
            String.format(
                "CREATE TABLE %s.%s as select * from INFORMATION_SCHEMA.CATALOGS",
                testSchema, tableName);
        test(createTableQuery);
        Thread.sleep(1001);

        String query =
            String.format(
                "ALTER TABLE %s.%s CHANGE COLUMN CATALOG_NAME CATALOG_NAME ARRAY(ROW(col1 int,COL1 double))",
                testSchema, tableName);
        errorMsgTestHelper(query, "Column [COL1] specified multiple times.");
      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tableName));
      }
    }
  }

  @Test
  public void addExistingColumnInListOfListOfStruct() throws Exception {
    for (String testSchema : SCHEMAS_FOR_TEST) {
      String tableName = "listoflistofstructcol";
      try {

        final String createTableQuery =
            String.format(
                "CREATE TABLE %s.%s as select * from INFORMATION_SCHEMA.CATALOGS",
                testSchema, tableName);
        test(createTableQuery);
        Thread.sleep(1001);

        String query =
            String.format(
                "ALTER TABLE %s.%s CHANGE COLUMN CATALOG_NAME CATALOG_NAME ARRAY(ARRAY(ROW(col1 int,COL1 double)))",
                testSchema, tableName);
        errorMsgTestHelper(query, "Column [COL1] specified multiple times.");
      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tableName));
      }
    }
  }

  @Test
  public void addExistingColumnInStructOfListOfStruct() throws Exception {
    for (String testSchema : SCHEMAS_FOR_TEST) {
      String tableName = "structoflistofstructcol";
      try {

        final String createTableQuery =
            String.format(
                "CREATE TABLE %s.%s as select * from INFORMATION_SCHEMA.CATALOGS",
                testSchema, tableName);
        test(createTableQuery);
        Thread.sleep(1001);

        String query =
            String.format(
                "ALTER TABLE %s.%s CHANGE COLUMN CATALOG_NAME CATALOG_NAME ROW(col ARRAY(ROW(col int, col1 int,COL1 double)))",
                testSchema, tableName);
        errorMsgTestHelper(query, "Column [COL1] specified multiple times.");
      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tableName));
      }
    }
  }

  @Test
  public void addExistingColumnInStructOfStruct() throws Exception {
    for (String testSchema : SCHEMAS_FOR_TEST) {
      String tableName = "structofstructcol";
      try {

        final String createTableQuery =
            String.format(
                "CREATE TABLE %s.%s as select * from INFORMATION_SCHEMA.CATALOGS",
                testSchema, tableName);
        test(createTableQuery);
        Thread.sleep(1001);

        String query =
            String.format(
                "ALTER TABLE %s.%s CHANGE COLUMN CATALOG_NAME CATALOG_NAME ROW(col ROW(col int, col1 int,COL1 double))",
                testSchema, tableName);
        errorMsgTestHelper(query, "Column [COL1] specified multiple times.");
      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tableName));
      }
    }
  }
}
