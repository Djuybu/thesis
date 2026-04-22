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

import static com.dremio.exec.ExecConstants.PARQUET_WRITER_COMPRESSION_TYPE_VALIDATOR;
import static com.dremio.exec.ExecConstants.PARQUET_WRITER_COMPRESSION_ZSTD_LEVEL_VALIDATOR;
import static com.dremio.exec.ExecConstants.WRITE_TARGET_FILE_SIZE_BYTES_DREMIO_DEFAULT;
import static org.apache.iceberg.TableProperties.WRITE_TARGET_FILE_SIZE_BYTES;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dremio.BaseTestQuery;
import com.dremio.common.exceptions.UserRemoteException;
import com.dremio.common.util.TestTools;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.sabot.rpc.user.QueryDataBatch;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.io.FileUtils;
import org.assertj.core.api.Assertions;
import org.junit.Assert;
import org.junit.Test;

public class TestIcebergTableProperties extends BaseTestQuery {

  @Test
  public void ctasWithTableProperties() throws Exception {
    try (AutoCloseable ignored = enableIcebergTablePropertiesSupportFlag()) {
      final String newTblName = "ctasWithTableProperties";
      final String testWorkingPath = TestTools.getWorkingPath();
      final String parquetFiles = testWorkingPath + "/src/test/resources/iceberg/orders";
      final String ctasQuery =
          String.format(
              "CREATE TABLE %s.%s TBLPROPERTIES ('property_name' = 'property_value') "
                  + " AS SELECT * from dfs.\""
                  + parquetFiles
                  + "\" limit 1",
              TEMP_SCHEMA_HADOOP,
              newTblName);
      final String showTablePropertiesQuery =
          String.format("SHOW TBLPROPERTIES %s.%s ", TEMP_SCHEMA_HADOOP, newTblName);

      try {
        test(ctasQuery);
        String metadataJsonString = getMetadataJsonString(newTblName);
        validateTablePropertiesFromMetadataJson(
            metadataJsonString, "property_name", "property_value");
        List<String> expectedResult =
            Stream.of("property_name", "property_value").collect(Collectors.toList());
        validateTablePropertiesViaShowTblproperties(showTablePropertiesQuery, expectedResult);
      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), newTblName));
      }
    }
  }

  @Test
  public void defaultTableProperties() throws Exception {
    try (AutoCloseable ignored = enableIcebergTablePropertiesSupportFlag()) {
      final String tableName = "defaultTableProperties";
      final String createTableStatement =
          String.format("CREATE TABLE %s.%s (i INT)", TEMP_SCHEMA_HADOOP, tableName);
      final String showTablePropertiesQuery =
          String.format("SHOW TBLPROPERTIES %s.%s ", TEMP_SCHEMA_HADOOP, tableName);

      try {
        test(createTableStatement);
        List<String> expectedResult =
            Stream.of(
                    "format-version",
                    WRITE_TARGET_FILE_SIZE_BYTES,
                    Long.toString(WRITE_TARGET_FILE_SIZE_BYTES_DREMIO_DEFAULT))
                .collect(Collectors.toList());
        validateTablePropertiesViaShowTblproperties(showTablePropertiesQuery, expectedResult);
      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tableName));
      }
    }
  }

  @Test
  public void overrideDefaultTableProperties() throws Exception {
    try (AutoCloseable ignored = enableIcebergTablePropertiesSupportFlag()) {
      final String tableName = "defaultTableProperties";
      final String writeTargetFileSizeBytesValue = "1000";
      final String createTableStatement =
          String.format(
              "CREATE TABLE %s.%s (i INT) TBLPROPERTIES ('%s' = '%s')",
              TEMP_SCHEMA_HADOOP,
              tableName,
              WRITE_TARGET_FILE_SIZE_BYTES,
              writeTargetFileSizeBytesValue);
      final String showTablePropertiesQuery =
          String.format("SHOW TBLPROPERTIES %s.%s ", TEMP_SCHEMA_HADOOP, tableName);

      try {
        test(createTableStatement);
        List<String> expectedResult =
            Stream.of("format-version", WRITE_TARGET_FILE_SIZE_BYTES, writeTargetFileSizeBytesValue)
                .collect(Collectors.toList());
        validateTablePropertiesViaShowTblproperties(showTablePropertiesQuery, expectedResult);
      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tableName));
      }
    }
  }

  @Test
  public void ctasDefaultTableProperties() throws Exception {
    try (AutoCloseable ignored = enableIcebergTablePropertiesSupportFlag()) {
      final String newTblName = "ctasDefaultTableProperties";
      final String testWorkingPath = TestTools.getWorkingPath();
      final String parquetFiles = testWorkingPath + "/src/test/resources/iceberg/orders";
      final String ctasQuery =
          String.format(
              "CREATE TABLE %s.%s AS SELECT * from dfs.\"" + parquetFiles + "\" limit 1",
              TEMP_SCHEMA_HADOOP,
              newTblName);
      final String showTablePropertiesQuery =
          String.format("SHOW TBLPROPERTIES %s.%s ", TEMP_SCHEMA_HADOOP, newTblName);

      try {
        test(ctasQuery);
        List<String> expectedResult =
            Stream.of(
                    "format-version",
                    WRITE_TARGET_FILE_SIZE_BYTES,
                    Long.toString(WRITE_TARGET_FILE_SIZE_BYTES_DREMIO_DEFAULT))
                .collect(Collectors.toList());
        validateTablePropertiesViaShowTblproperties(showTablePropertiesQuery, expectedResult);
      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), newTblName));
      }
    }
  }

  @Test
  public void ctasOverrideDefaultTableProperties() throws Exception {
    try (AutoCloseable ignored = enableIcebergTablePropertiesSupportFlag()) {
      final String newTblName = "ctasOverrideDefaultTableProperties";
      final String writeTargetFileSizeBytesValue = "1000";
      final String testWorkingPath = TestTools.getWorkingPath();
      final String parquetFiles = testWorkingPath + "/src/test/resources/iceberg/orders";
      final String ctasQuery =
          String.format(
              "CREATE TABLE %s.%s TBLPROPERTIES ('%s' = '%s') AS SELECT * from dfs.\""
                  + parquetFiles
                  + "\" limit 1",
              TEMP_SCHEMA_HADOOP,
              newTblName,
              WRITE_TARGET_FILE_SIZE_BYTES,
              writeTargetFileSizeBytesValue);
      final String showTablePropertiesQuery =
          String.format("SHOW TBLPROPERTIES %s.%s ", TEMP_SCHEMA_HADOOP, newTblName);

      try {
        test(ctasQuery);
        List<String> expectedResult =
            Stream.of("format-version", WRITE_TARGET_FILE_SIZE_BYTES, writeTargetFileSizeBytesValue)
                .collect(Collectors.toList());
        validateTablePropertiesViaShowTblproperties(showTablePropertiesQuery, expectedResult);
      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), newTblName));
      }
    }
  }

  @Test
  public void tablePropertiesOverrideParquetPageSize() throws Exception {
    String tblName = "tablePropertiesOverrideParquetPageSize";
    try {
      test(
          "CREATE TABLE %s.%s (id int) TBLPROPERTIES (" + "'write.parquet.page-size-bytes' = '0')",
          TEMP_SCHEMA, tblName);
      String metadataJsonString = getMetadataJsonString(tblName);
      validateTablePropertiesFromMetadataJson(
          metadataJsonString, "write.parquet.page-size-bytes", "0");

      List<String> expectedResult = List.of("write.parquet.page-size-bytes", "0");
      final String showTablePropertiesQuery =
          String.format("SHOW TBLPROPERTIES %s.%s ", TEMP_SCHEMA, tblName);
      validateTablePropertiesViaShowTblproperties(showTablePropertiesQuery, expectedResult);
      UserRemoteException e =
          Assert.assertThrows(
              UserRemoteException.class,
              () -> test("INSERT INTO %s.%s VALUES(1)", TEMP_SCHEMA, tblName));
      Assert.assertEquals(
          "IllegalArgumentException: Invalid page size (negative): 0", e.getOriginalMessage());
    } finally {
      FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tblName));
    }
  }

  @Test
  public void tablePropertiesPrecedenceOverSupportOptions() throws Exception {
    String tblName = "tablePropertiesPrecedenceOverSupportOptions";
    try (AutoCloseable ignored1 = withOption(PARQUET_WRITER_COMPRESSION_TYPE_VALIDATOR, "zstd");
        AutoCloseable ignored2 = withOption(PARQUET_WRITER_COMPRESSION_ZSTD_LEVEL_VALIDATOR, 10)) {

      test(
          "CREATE TABLE %s.%s (id int) TBLPROPERTIES ("
              + "'write.parquet.compression-codec' = 'gzip', "
              + "'write.parquet.compression-level' = '-1')",
          TEMP_SCHEMA, tblName);
      String metadataJsonString = getMetadataJsonString(tblName);
      validateTablePropertiesFromMetadataJson(
          metadataJsonString, "write.parquet.compression-codec", "gzip");
      validateTablePropertiesFromMetadataJson(
          metadataJsonString, "write.parquet.compression-level", "-1");

      List<String> expectedResult =
          List.of(
              "write.parquet.compression-codec", "gzip",
              "write.parquet.compression-level", "-1");
      final String showTablePropertiesQuery =
          String.format("SHOW TBLPROPERTIES %s.%s ", TEMP_SCHEMA, tblName);
      validateTablePropertiesViaShowTblproperties(showTablePropertiesQuery, expectedResult);
    } finally {
      FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tblName));
    }
  }

  @Test
  public void tablePropertiesOverrideDefaultWriteFormat() throws Exception {
    String tblName = "tablePropertiesOverrideDefaultWriteFormat";
    try {
      test(
          "CREATE TABLE %s.%s (id int) TBLPROPERTIES (" + "'write.format.default' = 'avro')",
          TEMP_SCHEMA, tblName);
      String metadataJsonString = getMetadataJsonString(tblName);
      validateTablePropertiesFromMetadataJson(metadataJsonString, "write.format.default", "avro");

      List<String> expectedResult = List.of("write.format.default", "avro");
      final String showTablePropertiesQuery =
          String.format("SHOW TBLPROPERTIES %s.%s ", TEMP_SCHEMA, tblName);
      validateTablePropertiesViaShowTblproperties(showTablePropertiesQuery, expectedResult);
      UserRemoteException e =
          Assert.assertThrows(
              UserRemoteException.class,
              () -> test("insert into %s.%s values(1)", TEMP_SCHEMA, tblName));
      Assert.assertEquals(
          "Unsupported file format for 'writer.default.format': avro. Dremio only supports parquet format.",
          e.getOriginalMessage());
    } finally {
      FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tblName));
    }
  }

  @Test
  public void tablePropertiesInsensitiveCase() throws Exception {
    int i = 0;
    for (String input : new String[] {"Parquet", "PARQUET", "parquet", "pArQuEt"}) {
      String tblName = "tablePropertiesInsensitiveCase" + i;
      try {
        test(
            "CREATE TABLE %s.%s (id int) TBLPROPERTIES ('write.format.default' = '%s')",
            TEMP_SCHEMA, tblName, input);
      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tblName));
        i++;
      }
    }
  }

  @Test
  public void tablePropertiesOverrideParquetRowLimit() throws Exception {
    String tblName = "tablePropertiesOverrideParquetDictSize";
    try {
      test(
          "CREATE TABLE %s.%s (id int) TBLPROPERTIES (" + "'write.parquet.page-row-limit' = '0')",
          TEMP_SCHEMA, tblName);
      String metadataJsonString = getMetadataJsonString(tblName);
      validateTablePropertiesFromMetadataJson(
          metadataJsonString, "write.parquet.page-row-limit", "0");

      List<String> expectedResult = List.of("write.parquet.page-row-limit", "0");
      final String showTablePropertiesQuery =
          String.format("SHOW TBLPROPERTIES %s.%s ", TEMP_SCHEMA, tblName);
      validateTablePropertiesViaShowTblproperties(showTablePropertiesQuery, expectedResult);
      UserRemoteException e =
          Assert.assertThrows(
              UserRemoteException.class,
              () -> test("insert into %s.%s values(1)", TEMP_SCHEMA, tblName));
      Assert.assertEquals(
          "IllegalArgumentException: Invalid row count limit for pages: 0", e.getOriginalMessage());
    } finally {
      FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tblName));
    }
  }

  @Test
  public void tablePropertiesOverrideParquetDictSize() throws Exception {
    String tblName = "tablePropertiesOverrideParquetDictSize";
    try {
      test(
          "CREATE TABLE %s.%s (id int) TBLPROPERTIES (" + "'write.parquet.dict-size-bytes' = '0')",
          TEMP_SCHEMA, tblName);
      String metadataJsonString = getMetadataJsonString(tblName);
      validateTablePropertiesFromMetadataJson(
          metadataJsonString, "write.parquet.dict-size-bytes", "0");

      List<String> expectedResult = List.of("write.parquet.dict-size-bytes", "0");
      final String showTablePropertiesQuery =
          String.format("SHOW TBLPROPERTIES %s.%s ", TEMP_SCHEMA, tblName);
      validateTablePropertiesViaShowTblproperties(showTablePropertiesQuery, expectedResult);
      UserRemoteException e =
          Assert.assertThrows(
              UserRemoteException.class,
              () -> test("insert into %s.%s values(1)", TEMP_SCHEMA, tblName));
      Assert.assertEquals(
          "IllegalArgumentException: Invalid dictionary page size (negative): 0",
          e.getOriginalMessage());
    } finally {
      FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tblName));
    }
  }

  @Test
  public void ctasWithMultiTableProperties() throws Exception {
    try (AutoCloseable ignored = enableIcebergTablePropertiesSupportFlag()) {
      final String newTblName = "ctasWithMultiTableProperties";
      final String testWorkingPath = TestTools.getWorkingPath();
      final String parquetFiles = testWorkingPath + "/src/test/resources/iceberg/orders";
      final String ctasQuery =
          String.format(
              "CREATE TABLE %s.%s TBLPROPERTIES ('property_name' = 'property_value', 'property_name1' = 'property_value1') "
                  + " AS SELECT * from dfs.\""
                  + parquetFiles
                  + "\" limit 1",
              TEMP_SCHEMA_HADOOP,
              newTblName);
      final String showTablePropertiesQuery =
          String.format("SHOW TBLPROPERTIES %s.%s ", TEMP_SCHEMA_HADOOP, newTblName);

      try {
        test(ctasQuery);
        String metadataJsonString = getMetadataJsonString(newTblName);
        validateTablePropertiesFromMetadataJson(
            metadataJsonString, "property_name", "property_value");
        validateTablePropertiesFromMetadataJson(
            metadataJsonString, "property_name1", "property_value1");
        List<String> expectedResult =
            Stream.of("property_name", "property_value", "property_name1", "property_value1")
                .collect(Collectors.toList());
        validateTablePropertiesViaShowTblproperties(showTablePropertiesQuery, expectedResult);
      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), newTblName));
      }
    }
  }

  @Test
  public void createTableWithMultiTableProperties() throws Exception {
    try (AutoCloseable ignored = enableIcebergTablePropertiesSupportFlag()) {
      final String newTblName = "createTableWithMultiTableProperties";
      final String ctasQuery =
          String.format(
              "CREATE TABLE %s.%s (id int, name varchar, distance Decimal(38, 3)) TBLPROPERTIES ('property_name' = 'property_value','property_name1' = 'property_value1')",
              TEMP_SCHEMA, newTblName);
      final String showTablePropertiesQuery =
          String.format("SHOW TBLPROPERTIES %s.%s ", TEMP_SCHEMA, newTblName);

      try {
        test(ctasQuery);
        String metadataJsonString = getMetadataJsonString(newTblName);
        validateTablePropertiesFromMetadataJson(
            metadataJsonString, "property_name", "property_value");
        validateTablePropertiesFromMetadataJson(
            metadataJsonString, "property_name1", "property_value1");
        List<String> expectedResult =
            Stream.of("property_name", "property_value", "property_name1", "property_value1")
                .collect(Collectors.toList());
        validateTablePropertiesViaShowTblproperties(showTablePropertiesQuery, expectedResult);
      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), newTblName));
      }
    }
  }

  @Test
  public void createTableWithTableProperties() throws Exception {
    try (AutoCloseable ignored = enableIcebergTablePropertiesSupportFlag()) {
      final String newTblName = "createTableWithTableProperties";
      final String ctasQuery =
          String.format(
              "CREATE TABLE %s.%s (id int, name varchar, distance Decimal(38, 3)) TBLPROPERTIES ('property_name' = 'property_value')",
              TEMP_SCHEMA, newTblName);
      final String showTablePropertiesQuery =
          String.format("SHOW TBLPROPERTIES %s.%s ", TEMP_SCHEMA, newTblName);

      try {
        test(ctasQuery);
        String metadataJsonString = getMetadataJsonString(newTblName);
        validateTablePropertiesFromMetadataJson(
            metadataJsonString, "property_name", "property_value");
        List<String> expectedResult =
            Stream.of("property_name", "property_value").collect(Collectors.toList());
        validateTablePropertiesViaShowTblproperties(showTablePropertiesQuery, expectedResult);
      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), newTblName));
      }
    }
  }

  @Test
  public void createTableWithTablePropertiesSetUnset() throws Exception {
    try (AutoCloseable ignored = enableIcebergTablePropertiesSupportFlag()) {
      final String newTblName = "createTableWithTablePropertiesSetUnset";
      final String ctasQuery =
          String.format(
              "CREATE TABLE %s.%s (id int, name varchar, distance Decimal(38, 3)) TBLPROPERTIES ('property_name' = 'property_value')",
              TEMP_SCHEMA, newTblName);
      final String showTablePropertiesQuery =
          String.format("SHOW TBLPROPERTIES %s.%s ", TEMP_SCHEMA, newTblName);
      final String setQuery =
          String.format(
              "ALTER TABLE %s.%s SET TBLPROPERTIES ('property_name' = 'new_property_value')",
              TEMP_SCHEMA, newTblName);
      final String unsetQuery =
          String.format(
              "ALTER TABLE %s.%s UNSET TBLPROPERTIES ('property_name')", TEMP_SCHEMA, newTblName);

      try {
        test(ctasQuery);
        String metadataJsonString = getMetadataJsonString(newTblName);
        validateTablePropertiesFromMetadataJson(
            metadataJsonString, "property_name", "property_value");
        List<String> expectedResult =
            Stream.of("property_name", "property_value").collect(Collectors.toList());
        validateTablePropertiesViaShowTblproperties(showTablePropertiesQuery, expectedResult);
        test(setQuery);
        List<String> expectedNewResult =
            Stream.of("property_name", "new_property_value").collect(Collectors.toList());
        validateTablePropertiesViaShowTblproperties(showTablePropertiesQuery, expectedNewResult);
        test(unsetQuery);
        validateTablePropertiesNotExistViaShowTblproperties(
            showTablePropertiesQuery, "property_name");
      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), newTblName));
      }
    }
  }

  @Test
  public void createTableWithoutTablePropertiesThenSetUnset() throws Exception {
    try (AutoCloseable ignored = enableIcebergTablePropertiesSupportFlag()) {
      final String newTblName = "createTableWithoutTablePropertiesThenSetUnset";
      final String ctasQuery =
          String.format(
              "CREATE TABLE %s.%s (id int, name varchar, distance Decimal(38, 3))",
              TEMP_SCHEMA, newTblName);
      final String showTablePropertiesQuery =
          String.format("SHOW TBLPROPERTIES %s.%s ", TEMP_SCHEMA, newTblName);
      final String setQuery =
          String.format(
              "ALTER TABLE %s.%s SET TBLPROPERTIES ('property_name' = 'new_property_value')",
              TEMP_SCHEMA, newTblName);
      final String unsetQuery =
          String.format(
              "ALTER TABLE %s.%s UNSET TBLPROPERTIES ('property_name')", TEMP_SCHEMA, newTblName);

      try {
        test(ctasQuery);
        validateTablePropertiesNotExistViaShowTblproperties(
            showTablePropertiesQuery, "property_name");
        test(setQuery);
        List<String> expectedNewResult =
            Stream.of("property_name", "new_property_value").collect(Collectors.toList());
        validateTablePropertiesViaShowTblproperties(showTablePropertiesQuery, expectedNewResult);
        test(unsetQuery);
        validateTablePropertiesNotExistViaShowTblproperties(
            showTablePropertiesQuery, "property_name");
      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), newTblName));
      }
    }
  }

  @Test
  public void createTableThenSetMultiplePropertiesUnset() throws Exception {
    try (AutoCloseable ignored = enableIcebergTablePropertiesSupportFlag()) {
      final String newTblName = "createTableThenSetMultiplePropertiesUnset";
      final String ctasQuery =
          String.format(
              "CREATE TABLE %s.%s (id int, name varchar, distance Decimal(38, 3)) TBLPROPERTIES ('property_name' = 'new_property_value', 'property_name1' = 'new_property_value1')",
              TEMP_SCHEMA, newTblName);
      final String showTablePropertiesQuery =
          String.format("SHOW TBLPROPERTIES %s.%s ", TEMP_SCHEMA, newTblName);
      final String setQuery =
          String.format(
              "ALTER TABLE %s.%s SET TBLPROPERTIES ('property_name' = 'new_property_value', 'property_name1' = 'new_property_value1')",
              TEMP_SCHEMA, newTblName);
      final String unsetQuery =
          String.format(
              "ALTER TABLE %s.%s UNSET TBLPROPERTIES ('property_name', 'property_name1')",
              TEMP_SCHEMA, newTblName);

      try {
        test(ctasQuery);
        List<String> expectedResult =
            Stream.of("property_name", "property_value", "property_name1", "property_value1")
                .collect(Collectors.toList());
        validateTablePropertiesViaShowTblproperties(showTablePropertiesQuery, expectedResult);
        test(setQuery);
        List<String> expectedNewResult =
            Stream.of(
                    "property_name", "new_property_value", "property_name1", "new_property_value1")
                .collect(Collectors.toList());
        validateTablePropertiesViaShowTblproperties(showTablePropertiesQuery, expectedNewResult);
        test(unsetQuery);
        validateTablePropertiesNotExistViaShowTblproperties(
            showTablePropertiesQuery, "property_name");
        validateTablePropertiesNotExistViaShowTblproperties(
            showTablePropertiesQuery, "property_name1");
      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), newTblName));
      }
    }
  }

  @Test
  public void createTableThenUnsetNonExistingProperty() throws Exception {
    try (AutoCloseable ignored = enableIcebergTablePropertiesSupportFlag()) {
      final String newTblName = "createTableThenUnsetNonExistingProperty";
      final String ctasQuery =
          String.format(
              "CREATE TABLE %s.%s (id int, name varchar, distance Decimal(38, 3)) TBLPROPERTIES ('property_name' = 'property_value')",
              TEMP_SCHEMA, newTblName);
      final String showTablePropertiesQuery =
          String.format("SHOW TBLPROPERTIES %s.%s ", TEMP_SCHEMA, newTblName);
      final String unsetQuery =
          String.format(
              "ALTER TABLE %s.%s UNSET TBLPROPERTIES ('property_name1')", TEMP_SCHEMA, newTblName);

      try {
        test(ctasQuery);
        String metadataJsonString = getMetadataJsonString(newTblName);
        validateTablePropertiesFromMetadataJson(
            metadataJsonString, "property_name", "property_value");
        List<String> expectedResult =
            Stream.of("property_name", "property_value").collect(Collectors.toList());
        validateTablePropertiesViaShowTblproperties(showTablePropertiesQuery, expectedResult);
        test(unsetQuery);
        validateTablePropertiesViaShowTblproperties(showTablePropertiesQuery, expectedResult);
      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), newTblName));
      }
    }
  }

  @Test
  public void alterTableFormatVersionThrowsException() throws Exception {
    try (AutoCloseable ignored = enableIcebergTablePropertiesSupportFlag()) {
      final String tableName = "alterTableFormatVersionThrowsException";
      final String createQuery =
          String.format("CREATE TABLE %s.%s (id INT)", TEMP_SCHEMA, tableName);
      final String showTablePropertiesQuery =
          String.format("SHOW TBLPROPERTIES %s.%s ", TEMP_SCHEMA, tableName);
      final String alterFormatVersionTemplate =
          "ALTER TABLE %s.%s SET TBLPROPERTIES ('format-version'='%s')";

      final Map<String, String> invalidAlterTableVersions =
          ImmutableMap.of(
              "0",
              "Cannot downgrade v2 table to v0",
              "1",
              "Cannot downgrade v2 table to v1",
              "4",
              "Cannot upgrade table to unsupported format version: v4 (supported: v3)",
              "",
              "input string: \"\"",
              "foo",
              "input string: \"foo\"");

      try {
        test(createQuery);
        List<String> expectedResult = Stream.of("format-version", "2").collect(Collectors.toList());
        validateTablePropertiesViaShowTblproperties(showTablePropertiesQuery, expectedResult);

        for (final Map.Entry<String, String> ent : invalidAlterTableVersions.entrySet()) {
          final String statement =
              String.format(alterFormatVersionTemplate, TEMP_SCHEMA, tableName, ent.getKey());
          assertThatThrownBy(() -> test(statement))
              .as(statement)
              .hasMessageContaining(ent.getValue());
        }

        validateTablePropertiesViaShowTblproperties(showTablePropertiesQuery, expectedResult);
      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tableName));
      }
    }
  }

  @Test
  public void alterTableFormatVersionFrom1To2() throws Exception {
    try (AutoCloseable ignored = enableIcebergTablePropertiesSupportFlag()) {
      final String tableName = "alterTableFormatVersionFrom1To2";
      final String createQuery =
          String.format(
              "CREATE TABLE %s.%s (id INT) TBLPROPERTIES ('format-version'='1')",
              TEMP_SCHEMA, tableName);
      final String showTablePropertiesQuery =
          String.format("SHOW TBLPROPERTIES %s.%s ", TEMP_SCHEMA, tableName);
      final String alterTableStatement =
          String.format(
              "ALTER TABLE %s.%s SET TBLPROPERTIES ('format-version'='2')", TEMP_SCHEMA, tableName);

      try {
        test(createQuery);
        List<String> expectedResult = Stream.of("format-version", "1").collect(Collectors.toList());
        validateTablePropertiesViaShowTblproperties(showTablePropertiesQuery, expectedResult);
        test(alterTableStatement);
        expectedResult = Stream.of("format-version", "2").collect(Collectors.toList());
        validateTablePropertiesViaShowTblproperties(showTablePropertiesQuery, expectedResult);
      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tableName));
      }
    }
  }

  @Test
  public void unsetTableFormatVersionThrowsException() throws Exception {
    try (AutoCloseable ignored = enableIcebergTablePropertiesSupportFlag()) {
      final String tableName = "unsetTableFormatVersionThrowsException";
      final String createQuery =
          String.format("CREATE TABLE %s.%s (id INT)", TEMP_SCHEMA, tableName);
      final String showTablePropertiesQuery =
          String.format("SHOW TBLPROPERTIES %s.%s ", TEMP_SCHEMA, tableName);
      final String unsetStatement =
          String.format(
              "ALTER TABLE %s.%s UNSET TBLPROPERTIES ('format-version')", TEMP_SCHEMA, tableName);
      try {
        test(createQuery);
        List<String> expectedResult = Stream.of("format-version", "2").collect(Collectors.toList());
        validateTablePropertiesViaShowTblproperties(showTablePropertiesQuery, expectedResult);

        assertThatThrownBy(() -> test(unsetStatement))
            .as(unsetStatement)
            .hasMessageContaining("Cannot unset Iceberg table property: format-version");

        validateTablePropertiesViaShowTblproperties(showTablePropertiesQuery, expectedResult);
      } finally {
        FileUtils.deleteQuietly(new File(getDfsTestTmpSchemaLocation(), tableName));
      }
    }
  }

  private void validateTablePropertiesFromMetadataJson(
      String metadataJson, String propertyName, String propertyValue) {
    Preconditions.checkNotNull(propertyName);
    JsonElement metadataJsonElement = new JsonParser().parse(metadataJson);
    JsonObject metadataJsonObject = metadataJsonElement.getAsJsonObject();

    JsonObject propertiesJson = metadataJsonObject.getAsJsonObject("properties");
    Assert.assertTrue(
        String.format("Property %s not found in %s", propertyName, metadataJsonObject),
        propertiesJson.has(propertyName));
    Assert.assertEquals(propertyValue, propertiesJson.get(propertyName).getAsString());
  }

  private void validateTablePropertiesViaShowTblproperties(
      String showTablePropertiesQuery, List<String> expectedResults) throws Exception {
    List<QueryDataBatch> queryDataBatches =
        BaseTestQuery.testRunAndReturn(UserBitShared.QueryType.SQL, showTablePropertiesQuery);
    String resultString = getResultString(queryDataBatches, ",", false);
    Assert.assertNotNull(resultString);
    for (String expectedResult : expectedResults) {
      Assertions.assertThat(resultString).contains(expectedResult);
    }
  }

  private void validateTablePropertiesNotExistViaShowTblproperties(
      String showTablePropertiesQuery, String propertyString) throws Exception {
    List<QueryDataBatch> queryDataBatches =
        BaseTestQuery.testRunAndReturn(UserBitShared.QueryType.SQL, showTablePropertiesQuery);
    String resultString = getResultString(queryDataBatches, ",", false);
    Assert.assertNotNull(resultString);
    Assert.assertTrue(
        resultString == null || resultString.isEmpty() || !resultString.contains(propertyString));
  }
}
