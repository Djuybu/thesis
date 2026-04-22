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
package com.dremio.exec.util.iceberg;

import static com.dremio.exec.store.iceberg.IcebergUtils.LATEST_SUPPORTED_ICEBERG_FORMAT_VERSION;

import com.dremio.BaseTestQuery;
import com.dremio.exec.store.iceberg.IcebergUtils;
import org.assertj.core.api.Assertions;
import org.junit.Test;

/**
 * Test class to verify that Dremio fails gracefully when an Iceberg table is set to an unsupported
 * format version. Currently, version 3 is the unsupported version. In the future, we may either
 * omit this test class, or bump the unsupported version to v4.
 */
public class TestFailureOnFormatVersion extends BaseTestQuery {

  /**
   * Test to verify that Dremio fails gracefully when an Iceberg table is set to an unsupported
   * version. We will build 2 tables, one with v2 and one with v3. Most of the tests will use v3.
   * Every sql command that we intend to test will be focused around the behavior of acknowledging
   * v3 iceberg table. If the sql command intends to read or write data from/to the v3 table, we
   * expect an error message to be thrown indicating that the format version is unsupported.
   */
  @Test
  public void testFailureOnIcebergTableSetToV3() throws Exception {

    // create your 'soon-to-be' v3 table. Note, the table is v2 upon creation.
    // table name for our iceberg table which will eventually be set to v3
    String icebergV3Table = "icebergV3Table";
    String createSql = String.format("CREATE TABLE %s.%s (id INT)", TEMP_SCHEMA, icebergV3Table);

    // create v2 table.
    // table name for our iceberg table which will remain on v2
    String getIcebergV2Table = "icebergV2TableDummy";
    String createIcebergV2Table =
        String.format("CREATE TABLE %s.%s (id INT)", TEMP_SCHEMA, getIcebergV2Table);

    // insert data into 'soon-to-be' v3 table
    String insertDataSql =
        String.format("INSERT INTO %s.%s VALUES (1)", TEMP_SCHEMA, icebergV3Table);

    // read 'soon-to-be' v3 table. this should work since the table is still v2
    String selectSql = String.format("SELECT * FROM %s.%s", TEMP_SCHEMA, icebergV3Table);

    // set the format-version 1 version higher than the Dremio's current supported version.
    String alterPropertiesToIcebergV3 =
        String.format(
            "ALTER TABLE %s.%s SET TBLPROPERTIES ('format-version' = '%s')",
            TEMP_SCHEMA, icebergV3Table, LATEST_SUPPORTED_ICEBERG_FORMAT_VERSION + 1);

    // create a new table from the v3 table as source. This should fail since the v3 table is not
    // supported
    String ctasSql =
        String.format(
            "CREATE TABLE %s.%s AS (SELECT * FROM %s.%s)",
            TEMP_SCHEMA, "anotherRandomTable", TEMP_SCHEMA, icebergV3Table);

    // delete data from the v3 table. This should fail since the v3 table is not supported
    String deleteSql = String.format("DELETE FROM %s.%s;", TEMP_SCHEMA, icebergV3Table);

    // update data in the v3 table. This should fail since the v3 table is not supported
    String updateSql = String.format("UPDATE %s.%s SET id = 2;", TEMP_SCHEMA, icebergV3Table);

    // optimize the v3 table. This should fail since the v3 table is not supported
    String optimizeSql = String.format("OPTIMIZE TABLE %s.%s", TEMP_SCHEMA, icebergV3Table);

    // vacuum the v3 table. This should fail since the v3 table is not supported
    String vacuumTable =
        String.format(
            "VACUUM TABLE %s.%s EXPIRE SNAPSHOTS RETAIN_LAST 1;", TEMP_SCHEMA, icebergV3Table);

    // add a column to the v3 table. This should work since. This is a metadata-only operation and
    // should not be affected by the format version
    String alterAddColSql =
        String.format("ALTER TABLE %s.%s ADD COLUMNS (col INT);", TEMP_SCHEMA, icebergV3Table);

    // show the table properties of the v3 table. This should work since this is a metadata-only
    // operation
    String showTblProps = String.format("SHOW TBLPROPERTIES %s.%s", TEMP_SCHEMA, icebergV3Table);

    // describe the v3 table. This should work since this is a metadata-only operation
    String describeTable = String.format("DESCRIBE TABLE %s.%s", TEMP_SCHEMA, icebergV3Table);

    // drop the v3 table. This should work since this is a metadata-only operation
    String dropTable = String.format("DROP TABLE %s.%s", TEMP_SCHEMA, icebergV3Table);

    // executing all the sql commands below....
    runSQL(createSql);
    runSQL(createIcebergV2Table);
    runSQL(insertDataSql);
    runSQL(selectSql);
    runSQL(alterPropertiesToIcebergV3);

    Assertions.assertThatThrownBy(() -> runSQL(insertDataSql))
        .hasMessageContaining(IcebergUtils.UNSUPPORTED_ICEBERG_FORMAT_VERSION_MSG, icebergV3Table);
    Assertions.assertThatThrownBy(() -> runSQL(selectSql))
        .hasMessageContaining(IcebergUtils.UNSUPPORTED_ICEBERG_FORMAT_VERSION_MSG, icebergV3Table);
    Assertions.assertThatThrownBy(() -> runSQL(ctasSql))
        .hasMessageContaining(IcebergUtils.UNSUPPORTED_ICEBERG_FORMAT_VERSION_MSG, icebergV3Table);
    Assertions.assertThatThrownBy(() -> runSQL(deleteSql))
        .hasMessageContaining(IcebergUtils.UNSUPPORTED_ICEBERG_FORMAT_VERSION_MSG, icebergV3Table);
    Assertions.assertThatThrownBy(() -> runSQL(updateSql))
        .hasMessageContaining(IcebergUtils.UNSUPPORTED_ICEBERG_FORMAT_VERSION_MSG, icebergV3Table);
    Assertions.assertThatThrownBy(() -> runSQL(optimizeSql))
        .hasMessageContaining(IcebergUtils.UNSUPPORTED_ICEBERG_FORMAT_VERSION_MSG, icebergV3Table);
    Assertions.assertThatThrownBy(() -> runSQL(vacuumTable))
        .hasMessageContaining(IcebergUtils.UNSUPPORTED_ICEBERG_FORMAT_VERSION_MSG, icebergV3Table);

    runSQL(showTblProps);
    runSQL(alterAddColSql);
    runSQL(describeTable);
    runSQL(dropTable);
  }
}
