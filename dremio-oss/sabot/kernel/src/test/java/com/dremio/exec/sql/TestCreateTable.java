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

import com.dremio.PlanTestBase;
import com.dremio.common.util.TestTools;
import com.dremio.exec.proto.UserBitShared.DremioPBError.ErrorType;
import java.io.File;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.Files;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.hadoop.HadoopTables;
import org.apache.iceberg.types.Types;
import org.junit.Test;

public class TestCreateTable extends PlanTestBase {

  @Test
  public void withInvalidColumnTypes() throws Exception {
    errorMsgTestHelper(
        String.format(
            "CREATE TABLE %s.%s (region inte, region_id int)", TEMP_SCHEMA, "testTableName1"),
        "Invalid column type [`inte`]");
  }

  @Test
  public void withDuplicateColumnsInDef1() throws Exception {
    errorMsgTestHelper(
        String.format(
            "CREATE TABLE %s.%s (region_id int, region_id int)", TEMP_SCHEMA, "testTableName2"),
        "Column [region_id] specified multiple times.");
  }

  @Test
  public void withDuplicateColumnsInDef2() throws Exception {
    errorMsgTestHelper(
        String.format(
            "CREATE TABLE %s.%s (region_id int, sales_city varchar, sales_city varchar)",
            TEMP_SCHEMA, "testTableName3"),
        "Column [sales_city] specified multiple times.");
  }

  @Test
  public void withDuplicateColumnsInStruct() throws Exception {
    errorMsgTestHelper(
        String.format(
            "CREATE TABLE %s.%s (col1 ROW(region_id  int, Region_id  int))",
            TEMP_SCHEMA, "testTableNameDupStruct"),
        "Column [Region_id] specified multiple times.");
  }

  @Test
  public void withDuplicateColumnsInListOfStruct() throws Exception {
    errorMsgTestHelper(
        String.format(
            "CREATE TABLE %s.%s (col1 ARRAY(ROW(region_id  int, Region_id  int)))",
            TEMP_SCHEMA, "testTableNameDupListOfStruct"),
        "Column [Region_id] specified multiple times.");
  }

  @Test
  public void withDuplicateColumnsInListOfListOfStruct() throws Exception {
    errorMsgTestHelper(
        String.format(
            "CREATE TABLE %s.%s (col1 ARRAY(ARRAY(ROW(region_id  int, Region_id  int))))",
            TEMP_SCHEMA, "testTableNameDupListOfListOfStruct"),
        "Column [Region_id] specified multiple times.");
  }

  @Test
  public void withDuplicateColumnsInStructOfListOfStruct() throws Exception {
    errorMsgTestHelper(
        String.format(
            "CREATE TABLE %s.%s (col1 ROW( x ARRAY(ROW(x int,region_id  int, Region_id  int))))",
            TEMP_SCHEMA, "testTableNameDupStructOfListOfStruct"),
        "Column [Region_id] specified multiple times.");
  }

  @Test
  public void withDuplicateColumnsInStructOfStruct() throws Exception {
    errorMsgTestHelper(
        String.format(
            "CREATE TABLE %s.%s (col1 ROW( x ROW(x int,region_id  int, Region_id  int)))",
            TEMP_SCHEMA, "testTableNameDupStructOfStruct"),
        "Column [Region_id] specified multiple times.");
  }

  @Test
  public void createTableInvalidType() throws Exception {
    errorMsgTestHelper(
        String.format(
            "CREATE TABLE %s.%s (region_id int, sales_city Decimal(39, 4))",
            TEMP_SCHEMA, "testTableName4"),
        "Precision larger than 38 is not supported.");
  }

  @Test
  public void createTableWithVarcharPrecision() throws Exception {
    final String newTblName = "createTableVarcharPrecision";
    try {
      final String ctasQuery =
          String.format(
              "create table %s.%s ( VOTER_ID INT, NAME VARCHAR(40), AGE INT, REGISTRATION CHAR(51), "
                  + "CONTRIBUTIONS float, VOTERZONE INT, CREATE_TIMESTAMP TIMESTAMP, create_date date) partition by(AGE)",
              TEMP_SCHEMA, newTblName);
      test(ctasQuery);
    } finally {
      FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), newTblName));
    }
  }

  @Test
  public void createTableAndSelect() throws Exception {
    final String newTblName = "createEmptyTable";

    try {
      final String ctasQuery =
          String.format(
              "CREATE TABLE %s.%s (id int, name varchar, distance Decimal(38, 3))",
              TEMP_SCHEMA, newTblName);

      test(ctasQuery);

      final String describeCreatedTable = String.format("describe %s.%s", TEMP_SCHEMA, newTblName);
      testBuilder()
          .sqlQuery(describeCreatedTable)
          .unOrdered()
          .baselineColumns(
              "COLUMN_NAME",
              "DATA_TYPE",
              "IS_NULLABLE",
              "NUMERIC_PRECISION",
              "NUMERIC_SCALE",
              "EXTENDED_PROPERTIES",
              "MASKING_POLICY",
              "SORT_ORDER_PRIORITY")
          .baselineValues("id", "INTEGER", "YES", 32, 0, "[]", null, null)
          .baselineValues("name", "CHARACTER VARYING", "YES", null, null, "[]", null, null)
          .baselineValues("distance", "DECIMAL", "YES", 38, 3, "[]", null, null)
          .build()
          .run();

      final String selectFromCreatedTable =
          String.format("select count(*) as cnt from %s.%s", TEMP_SCHEMA, newTblName);
      testBuilder()
          .sqlQuery(selectFromCreatedTable)
          .unOrdered()
          .baselineColumns("cnt")
          .baselineValues(0L)
          .build()
          .run();

    } finally {
      FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), newTblName));
    }
  }

  @Test
  public void createTableWhenATableWithSameNameAlreadyExists() throws Exception {
    final String newTblName = "createTableWhenTableAlreadyExists";

    try {
      final String ctasQuery =
          String.format("CREATE TABLE %s.%s (id int, name varchar)", TEMP_SCHEMA, newTblName);

      test(ctasQuery);

      errorMsgTestHelper(
          ctasQuery,
          String.format(
              "A table or view with given name [%s.%s] already exists", TEMP_SCHEMA, newTblName));
    } finally {
      FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), newTblName));
    }
  }

  @Test
  public void createEmptyTablePartitionWithEmptyList() throws Exception {
    final String newTblName = "ctasPartitionWithEmptyList";
    final String ctasQuery =
        String.format(
            "CREATE TABLE %s.%s (id int, name varchar) PARTITION BY ", TEMP_SCHEMA, newTblName);

    try {
      errorTypeTestHelper(ctasQuery, ErrorType.PARSE);
    } finally {
      FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), newTblName));
    }
  }

  @Test
  public void testDroppingOfMapTypeColumn() throws Exception {
    String table1 = "iceberg_map_test";
    try {
      File table1Folder = new File(getDfsTestTmpSchemaLocation(), table1);
      HadoopTables hadoopTables = new HadoopTables(new Configuration());

      Schema schema =
          new Schema(
              Types.NestedField.optional(
                  1,
                  "col1",
                  Types.MapType.ofOptional(
                      2,
                      3,
                      Types.StructType.of(
                          Types.NestedField.optional(4, "f1", Types.StringType.get())),
                      Types.StringType.get())),
              Types.NestedField.optional(5, "col2", Types.IntegerType.get()));
      PartitionSpec spec = PartitionSpec.builderFor(schema).build();
      Table table = hadoopTables.create(schema, spec, table1Folder.getPath());
      Transaction transaction = table.newTransaction();
      AppendFiles appendFiles = transaction.newAppend();
      final String testWorkingPath =
          TestTools.getWorkingPath() + "/src/test/resources/iceberg/mapTest";
      final String parquetFile = "iceberg_map_test.parquet";
      File dataFile = new File(testWorkingPath, parquetFile);
      appendFiles.appendFile(
          DataFiles.builder(spec)
              .withInputFile(Files.localInput(dataFile))
              .withRecordCount(1)
              .withFormat(FileFormat.PARQUET)
              .build());
      appendFiles.commit();
      transaction.commitTransaction();

      testBuilder()
          .sqlQuery("select * from dfs_test_hadoop.iceberg_map_test")
          .unOrdered()
          .baselineColumns("col2")
          .baselineValues(1)
          .build()
          .run();

      Thread.sleep(1001);
      String insertCommandSql =
          "insert into  dfs_test_hadoop.iceberg_map_test select * from (values(2))";
      test(insertCommandSql);
      Thread.sleep(1001);

      testBuilder()
          .sqlQuery("select * from dfs_test_hadoop.iceberg_map_test")
          .unOrdered()
          .baselineColumns("col2")
          .baselineValues(1)
          .baselineValues(2)
          .build()
          .run();
    } finally {
      FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), table1));
    }
  }

  private void addFileToTable(
      Table table, PartitionSpec spec, String testWorkingPath, String parquetFile) {
    Transaction transaction = table.newTransaction();
    AppendFiles appendFiles = transaction.newAppend();

    File dataFile = new File(testWorkingPath, parquetFile);
    appendFiles.appendFile(
        DataFiles.builder(spec)
            .withInputFile(Files.localInput(dataFile))
            .withRecordCount(1)
            .withFormat(FileFormat.PARQUET)
            .build());
    appendFiles.commit();
    transaction.commitTransaction();
  }

  @Test
  public void testReadingFromRootPointer() throws Exception {
    String table1 = "root_pointer";
    try {
      File table1Folder = new File(getDfsTestTmpSchemaLocation(), table1);
      HadoopTables hadoopTables = new HadoopTables(new Configuration());

      Schema schema =
          new Schema(
              Types.NestedField.optional(1, "col1", Types.IntegerType.get()),
              Types.NestedField.optional(4, "col2", Types.IntegerType.get()));
      PartitionSpec spec = PartitionSpec.builderFor(schema).build();
      Table table = hadoopTables.create(schema, spec, table1Folder.getPath());
      final String testWorkingPath =
          TestTools.getWorkingPath() + "/src/test/resources/iceberg/root_pointer";
      addFileToTable(table, spec, testWorkingPath, "f1.parquet");

      testBuilder()
          .sqlQuery("select * from dfs_test_hadoop.root_pointer")
          .unOrdered()
          .baselineColumns("col1", "col2")
          .baselineValues(1, 2)
          .build()
          .run();

      Thread.sleep(1001);
      addFileToTable(table, spec, testWorkingPath, "f2.parquet");

      testBuilder()
          .sqlQuery("select * from dfs_test_hadoop.root_pointer")
          .unOrdered()
          .baselineColumns("col1", "col2")
          .baselineValues(1, 2)
          .build()
          .run();

      String refreshMetadata = "alter table dfs_test_hadoop.root_pointer refresh metadata";
      test(refreshMetadata);

      testBuilder()
          .sqlQuery("select * from dfs_test_hadoop.root_pointer")
          .unOrdered()
          .baselineColumns("col1", "col2")
          .baselineValues(1, 2)
          .baselineValues(1, 2)
          .build()
          .run();
    } finally {
      FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), table1));
    }
  }

  @Test
  public void createTableWithComplexTypes() throws Exception {
    final String newTblName = "createTableComplexTypes";
    try {
      final String ctasQuery =
          String.format(
              "create table %s.%s (point ROW(x INT , y  DECIMAL(38,3)), list ARRAY(BIGINT), listoflist ARRAY(ARRAY(DECIMAL(34,4))), listofstruct ARRAY(ROW(x BIGINT)),"
                  + "structofstruct ROW(x ROW(z INT)), structoflist ROW(x ARRAY(INT)), point2 STRUCT<x : INT,y: INT>, list2 LIST<BIGINT>,listoflist2 LIST<LIST<DECIMAL(34,4)>>,listofstruct2 LIST<STRUCT<x: BIGINT>>,"
                  + "structofstruct2 STRUCT<x :ROW(z INT)>,  structofstruct3 STRUCT<x :STRUCT<z :INT>>,"
                  + "map MAP<BIGINT,BIGINT>, mapOfMap MAP<BIGINT, MAP<BIGINT, BIGINT>>)",
              TEMP_SCHEMA, newTblName);
      test(ctasQuery);
    } finally {
      FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), newTblName));
    }
  }

  @Test
  public void createTableIfNotExists() throws Exception {
    final String newTblName = "createTableIfNotExists";

    try {
      final String createQuery =
          String.format(
              "CREATE TABLE IF NOT EXISTS %s.%s (id int, name varchar)", TEMP_SCHEMA, newTblName);

      testBuilder()
          .sqlQuery(createQuery)
          .unOrdered()
          .baselineColumns("ok", "summary")
          .baselineValues(true, "Table created")
          .build()
          .run();

      testBuilder()
          .sqlQuery(createQuery)
          .unOrdered()
          .baselineColumns("ok", "summary")
          .baselineValues(
              true, String.format("Table [%s.%s] already exists.", TEMP_SCHEMA, newTblName))
          .build()
          .run();
    } finally {
      FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), newTblName));
    }
  }

  @Test
  public void ctasIfNotExists() throws Exception {
    final String newTblName1 = "ctasTable1";
    final String newTblName2 = "ctasTable2";
    final String newTblName3 = "ctasTable3";

    try {
      final String ctasQuery1 =
          String.format(
              "CREATE TABLE IF NOT EXISTS %s.%s (id int, name varchar)", TEMP_SCHEMA, newTblName1);
      final String ctasQuery2 =
          String.format(
              "CREATE TABLE IF NOT EXISTS %s.%s (id int, name varchar)", TEMP_SCHEMA, newTblName2);
      final String ctasQuery3 =
          String.format(
              "CREATE TABLE IF NOT EXISTS %s.%s AS SELECT * FROM %s.%s ",
              TEMP_SCHEMA, newTblName2, TEMP_SCHEMA, newTblName1);
      final String ctasQuery4 =
          String.format(
              "CREATE TABLE IF NOT EXISTS %s.%s AS SELECT * FROM %s.%s ",
              TEMP_SCHEMA, newTblName3, TEMP_SCHEMA, newTblName1);

      test(ctasQuery1);
      test(ctasQuery2);

      testBuilder()
          .sqlQuery(ctasQuery3)
          .unOrdered()
          .baselineColumns("ok", "summary")
          .baselineValues(
              true, String.format("Table [%s.%s] already exists.", TEMP_SCHEMA, newTblName2))
          .build()
          .run();

      testBuilder()
          .sqlQuery(ctasQuery4)
          .unOrdered()
          .baselineColumns(
              "Fragment",
              "Records",
              "Path",
              "Metadata",
              "Partition",
              "FileSize",
              "IcebergMetadata",
              "fileschema",
              "PartitionData",
              "OperationType")
          .expectsEmptyResultSet()
          .build()
          .run();
    } finally {
      FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), newTblName1));
      FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), newTblName2));
      FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), newTblName3));
    }
  }

  @Test
  public void testCreateTableWithNullability() throws Exception {
    String tableName = "createTableWithNullability";
    try {
      String createQuery =
          String.format(
              "create table %s.%s (id int not null, name varchar, distance Decimal(38, 3) not null)",
              TEMP_SCHEMA, tableName);
      test(createQuery);
      String describeQuery = String.format("describe %s.%s", TEMP_SCHEMA, tableName);
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
        {"id", "INTEGER", "NO", 32, 0, "[]", null, null},
        {"name", "CHARACTER VARYING", "YES", null, null, "[]", null, null},
        {"distance", "DECIMAL", "NO", 38, 3, "[]", null, null}
      };
      List<Map<String, Object>> baselineRecords =
          prepareBaselineRecords(baselineCols, baselineValues);
      testBuilder().sqlQuery(describeQuery).unOrdered().baselineRecords(baselineRecords).go();

    } finally {
      FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tableName));
    }
  }

  @Test
  public void testCreateTableWithComplexTypesAndNullability() throws Exception {
    final String tableName = "createTableComplexTypesAndNullability";
    try {
      // note: if the root complex type is nullable, all its children are nullable as well
      // this is how calcite handles null propagation in complex types
      final String createQuery =
          String.format(
              "create table %s.%s ("
                  + "struct1 struct<x INT, y INT>, "
                  + "struct2 struct<x INT, y INT> not null, "
                  + "struct3 struct<x INT, y INT not null> not null, "
                  + "structofstruct1 struct<x struct<z INT> not null>, "
                  + "structofstruct2 struct<x struct<z INT>> not null, "
                  + "structofstruct3 struct<x struct<z INT> not null> not null, "
                  + "structofstruct4 struct<x struct<z INT not null> not null> not null, "
                  + "structoflist1 struct<x array<INT>>, "
                  + "structoflist2 struct<x array<INT>> not null, "
                  + "structoflist3 struct<x array<INT> not null> not null, "
                  + "structoflist4 struct<x array<INT not null> not null> not null, "
                  + "structofmap1 struct<x map<INT, INT>>, "
                  + "structofmap2 struct<x map<INT, INT>> not null, "
                  + "structofmap3 struct<x map<INT, INT> not null> not null, "
                  + "strucfofmap4 struct<x map<INT, INT not null> not null> not null, "
                  + "list1 list<INT>, "
                  + "list2 list<INT> not null, "
                  + "list3 list<INT not null> not null, "
                  + "listofstruct1 list<struct<x INT>>, "
                  + "listofstruct2 list<struct<x INT>> not null, "
                  + "listofstruct3 list<struct<x INT> not null> not null, "
                  + "listofstruct4 list<struct<x INT not null> not null> not null, "
                  + "listoflist1 list<list<INT>>, "
                  + "listoflist2 list<list<INT>> not null, "
                  + "listoflist3 list<list<INT> not null> not null, "
                  + "listoflist4 list<list<INT not null> not null> not null, "
                  + "listofmap1 list<map<INT, INT>>, "
                  + "listofmap2 list<map<INT, INT>> not null, "
                  + "listofmap3 list<map<INT, INT> not null> not null, "
                  + "listofmap4 list<map<INT, INT not null> not null> not null, "
                  + "map1 map<INT, INT>, "
                  + "map2 map<INT, INT> not null, "
                  + "map3 map<INT, INT not null> not null, "
                  + "mapofstruct1 map<INT, struct<x INT>>, "
                  + "mapofstruct2 map<INT, struct<x INT>> not null, "
                  + "mapofstruct3 map<INT, struct<x INT> not null> not null, "
                  + "mapofstruct4 map<INT, struct<x INT not null> not null> not null, "
                  + "mapoflist1 map<INT, list<INT>>, "
                  + "mapoflist2 map<INT, list<INT>> not null, "
                  + "mapoflist3 map<INT, list<INT> not null> not null, "
                  + "mapoflist4 map<INT, list<INT not null> not null> not null, "
                  + "mapofmap1 map<INT, map<INT, INT>>, "
                  + "mapofmap2 map<INT, map<INT, INT>> not null, "
                  + "mapofmap3 map<INT, map<INT, INT> not null> not null,"
                  + "mapofmap4 map<INT, map<INT, INT not null> not null> not null"
                  + ")",
              TEMP_SCHEMA, tableName);
      test(createQuery);
      String describeQuery = String.format("describe %s.%s", TEMP_SCHEMA, tableName);
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
        new Object[] {"struct1", "ROW", "YES", null, null, "[]", null, null},
        new Object[] {"struct2", "ROW", "NO", null, null, "[]", null, null},
        new Object[] {"struct3", "ROW", "NO", null, null, "[]", null, null},
        new Object[] {"structofstruct1", "ROW", "YES", null, null, "[]", null, null},
        new Object[] {"structofstruct2", "ROW", "NO", null, null, "[]", null, null},
        new Object[] {"structofstruct3", "ROW", "NO", null, null, "[]", null, null},
        new Object[] {"structofstruct4", "ROW", "NO", null, null, "[]", null, null},
        new Object[] {"structoflist1", "ROW", "YES", null, null, "[]", null, null},
        new Object[] {"structoflist2", "ROW", "NO", null, null, "[]", null, null},
        new Object[] {"structoflist3", "ROW", "NO", null, null, "[]", null, null},
        new Object[] {"structoflist4", "ROW", "NO", null, null, "[]", null, null},
        new Object[] {"structofmap1", "ROW", "YES", null, null, "[]", null, null},
        new Object[] {"structofmap2", "ROW", "NO", null, null, "[]", null, null},
        new Object[] {"structofmap3", "ROW", "NO", null, null, "[]", null, null},
        new Object[] {"strucfofmap4", "ROW", "NO", null, null, "[]", null, null},
        new Object[] {"list1", "ARRAY", "YES", null, null, "[]", null, null},
        new Object[] {"list2", "ARRAY", "NO", null, null, "[]", null, null},
        new Object[] {"list3", "ARRAY", "NO", null, null, "[]", null, null},
        new Object[] {"listofstruct1", "ARRAY", "YES", null, null, "[]", null, null},
        new Object[] {"listofstruct2", "ARRAY", "NO", null, null, "[]", null, null},
        new Object[] {"listofstruct3", "ARRAY", "NO", null, null, "[]", null, null},
        new Object[] {"listofstruct4", "ARRAY", "NO", null, null, "[]", null, null},
        new Object[] {"listoflist1", "ARRAY", "YES", null, null, "[]", null, null},
        new Object[] {"listoflist2", "ARRAY", "NO", null, null, "[]", null, null},
        new Object[] {"listoflist3", "ARRAY", "NO", null, null, "[]", null, null},
        new Object[] {"listoflist4", "ARRAY", "NO", null, null, "[]", null, null},
        new Object[] {"listofmap1", "ARRAY", "YES", null, null, "[]", null, null},
        new Object[] {"listofmap2", "ARRAY", "NO", null, null, "[]", null, null},
        new Object[] {"listofmap3", "ARRAY", "NO", null, null, "[]", null, null},
        new Object[] {"listofmap4", "ARRAY", "NO", null, null, "[]", null, null},
        new Object[] {"map1", "MAP", "YES", null, null, "[]", null, null},
        new Object[] {"map2", "MAP", "NO", null, null, "[]", null, null},
        new Object[] {"map3", "MAP", "NO", null, null, "[]", null, null},
        new Object[] {"mapofstruct1", "MAP", "YES", null, null, "[]", null, null},
        new Object[] {"mapofstruct2", "MAP", "NO", null, null, "[]", null, null},
        new Object[] {"mapofstruct3", "MAP", "NO", null, null, "[]", null, null},
        new Object[] {"mapofstruct4", "MAP", "NO", null, null, "[]", null, null},
        new Object[] {"mapoflist1", "MAP", "YES", null, null, "[]", null, null},
        new Object[] {"mapoflist2", "MAP", "NO", null, null, "[]", null, null},
        new Object[] {"mapoflist3", "MAP", "NO", null, null, "[]", null, null},
        new Object[] {"mapoflist4", "MAP", "NO", null, null, "[]", null, null},
        new Object[] {"mapofmap1", "MAP", "YES", null, null, "[]", null, null},
        new Object[] {"mapofmap2", "MAP", "NO", null, null, "[]", null, null},
        new Object[] {"mapofmap3", "MAP", "NO", null, null, "[]", null, null},
        new Object[] {"mapofmap4", "MAP", "NO", null, null, "[]", null, null}
      };
      List<Map<String, Object>> baselineRecords =
          prepareBaselineRecords(baselineCols, baselineValues);
      testBuilder().sqlQuery(describeQuery).unOrdered().baselineRecords(baselineRecords).go();
    } finally {
      FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tableName));
    }
  }
}
