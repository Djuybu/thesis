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
package com.dremio.exec.catalog.lineage;

import static com.dremio.common.UserConstants.SYSTEM_USERNAME;
import static com.dremio.exec.catalog.lineage.SqlLineageExtractor.extractLineage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dremio.BaseTestQueryJunit5;
import com.dremio.config.DremioConfig;
import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.proto.UserProtos;
import com.dremio.exec.server.SabotQueryContext;
import com.dremio.exec.server.options.SessionOptionManagerImpl;
import com.dremio.sabot.rpc.user.UserSession;
import com.dremio.service.namespace.dataset.proto.Origin;
import com.dremio.test.TemporarySystemProperties;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.calcite.runtime.CalciteContextException;
import org.apache.calcite.sql.parser.SqlParseException;
import org.junit.Rule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class TestSqlLineageExtractor extends BaseTestQueryJunit5 {
  @Rule public TemporarySystemProperties properties = new TemporarySystemProperties();

  private static QueryContext basicSystemQueryContext;
  private static QueryContext systemQueryContextWithContext;

  @BeforeAll
  public static void setupDefaultTestCluster() throws Exception {
    BaseTestQueryJunit5.setupDefaultTestCluster();

    basicSystemQueryContext = newQueryContext(getSabotContext(), SYSTEM_USERNAME);
    systemQueryContextWithContext =
        newQueryContext(getSabotContext(), SYSTEM_USERNAME, ImmutableList.of("cp"));
  }

  @Test
  public void testSelectStar() throws SqlParseException {
    String sql = "select * from cp.\"tpch/region.parquet\"";
    testExtractLineageForSelectStar(basicSystemQueryContext, sql);
  }

  @Test
  public void testSelectStarWithContext() throws SqlParseException {
    String sql = "select * from \"tpch/region.parquet\"";
    testExtractLineageForSelectStar(systemQueryContextWithContext, sql);
  }

  private void testExtractLineageForSelectStar(QueryContext queryContext, String sql)
      throws SqlParseException {
    TableLineage lineage = extractLineage(queryContext, sql);

    assertEquals(3, lineage.getFields().getFieldCount());
    validateField(lineage, 0, "r_regionkey", "INTEGER");
    validateField(lineage, 1, "r_name", "VARCHAR");
    validateField(lineage, 2, "r_comment", "VARCHAR");

    assertEquals(1, lineage.getUpstreams().size());
    validateUpstream(lineage, 0, Arrays.asList("cp", "tpch/region.parquet"));

    assertEquals(lineage.getFields().getFieldCount(), lineage.getFieldUpstreams().size());
    validateFieldUpstream(
        lineage,
        0,
        "r_regionkey",
        Arrays.asList(
            new Origin()
                .setTableList(Arrays.asList("cp", "tpch/region.parquet"))
                .setColumnName("r_regionkey")
                .setDerived(false)));
    validateFieldUpstream(
        lineage,
        1,
        "r_name",
        Arrays.asList(
            new Origin()
                .setTableList(Arrays.asList("cp", "tpch/region.parquet"))
                .setColumnName("r_name")
                .setDerived(false)));
    validateFieldUpstream(
        lineage,
        2,
        "r_comment",
        Arrays.asList(
            new Origin()
                .setTableList(Arrays.asList("cp", "tpch/region.parquet"))
                .setColumnName("r_comment")
                .setDerived(false)));
  }

  @Test
  public void testJoin() throws SqlParseException {
    String sql =
        "select r1.r_name as name, r2.r_comment as comment "
            + "from  cp.\"tpch/region.parquet\" r1 join cp.\"tpch/region.parquet\" r2 "
            + "on r1.r_regionkey = r2.r_regionkey";
    testExtractLineageForJoin(basicSystemQueryContext, sql);
  }

  @Test
  public void testJoinWithContext() throws SqlParseException {
    String sql =
        "select r1.r_name as name, r2.r_comment as comment "
            + "from  \"tpch/region.parquet\" r1 join \"tpch/region.parquet\" r2 "
            + "on r1.r_regionkey = r2.r_regionkey";
    testExtractLineageForJoin(systemQueryContextWithContext, sql);
  }

  private void testExtractLineageForJoin(QueryContext queryContext, String sql)
      throws SqlParseException {
    TableLineage lineage = extractLineage(queryContext, sql);

    assertEquals(2, lineage.getFields().getFieldCount());
    validateField(lineage, 0, "name", "VARCHAR");
    validateField(lineage, 1, "comment", "VARCHAR");

    assertEquals(1, lineage.getUpstreams().size());
    validateUpstream(lineage, 0, Arrays.asList("cp", "tpch/region.parquet"));

    assertEquals(lineage.getFields().getFieldCount(), lineage.getFieldUpstreams().size());
    validateFieldUpstream(
        lineage,
        0,
        "name",
        Arrays.asList(
            new Origin()
                .setTableList(Arrays.asList("cp", "tpch/region.parquet"))
                .setColumnName("r_name")
                .setDerived(false)));
    validateFieldUpstream(
        lineage,
        1,
        "comment",
        Arrays.asList(
            new Origin()
                .setTableList(Arrays.asList("cp", "tpch/region.parquet"))
                .setColumnName("r_comment")
                .setDerived(false)));
  }

  @Test
  public void testSelectFromView() throws Exception {
    properties.set(DremioConfig.LEGACY_STORE_VIEWS_ENABLED, "true");
    runSQL("create view dfs_test.region_view as select * from cp.\"tpch/region.parquet\"");

    String sql =
        "select r1.r_name as name, r2.r_comment as comment "
            + "from cp.\"tpch/region.parquet\" r1 join dfs_test.region_view r2 "
            + "on r1.r_regionkey = r2.r_regionkey";
    TableLineage lineage = extractLineage(basicSystemQueryContext, sql);

    assertEquals(2, lineage.getFields().getFieldCount());
    validateField(lineage, 0, "name", "VARCHAR");
    validateField(lineage, 1, "comment", "VARCHAR");

    assertEquals(2, lineage.getUpstreams().size());
    validateUpstream(lineage, 0, Arrays.asList("cp", "tpch/region.parquet"));
    validateUpstream(lineage, 1, Arrays.asList("dfs_test", "region_view"));

    assertEquals(lineage.getFields().getFieldCount(), lineage.getFieldUpstreams().size());
    validateFieldUpstream(
        lineage,
        0,
        "name",
        Arrays.asList(
            new Origin()
                .setTableList(Arrays.asList("cp", "tpch/region.parquet"))
                .setColumnName("r_name")
                .setDerived(false)));
    validateFieldUpstream(
        lineage,
        1,
        "comment",
        Arrays.asList(
            new Origin()
                .setTableList(Arrays.asList("dfs_test", "region_view"))
                .setColumnName("r_comment")
                .setDerived(false)));
  }

  @Test
  public void testTableNotFound() {
    String sql = "select * from  cp.\"table_not_exist\"";

    CalciteContextException thrown =
        assertThrows(
            CalciteContextException.class,
            () -> extractLineage(basicSystemQueryContext, sql),
            "Expected extractLineage() to throw, but it didn't");

    assertTrue(thrown.getMessage().contains("Object 'table_not_exist' not found"));
  }

  @Test
  public void testExistingTableNotFoundDueToContext() {
    String sql = "select * from  \"tpch/region.parquet\"";

    CalciteContextException thrown =
        assertThrows(
            CalciteContextException.class,
            () -> extractLineage(basicSystemQueryContext, sql),
            "Expected extractLineage() to throw, but it didn't");

    assertTrue(thrown.getMessage().contains("Object 'tpch/region.parquet' not found"));
  }

  @Test
  public void testFieldNotFound() {
    String sql = "select filed_not_found as name from  cp.\"tpch/region.parquet\"";

    CalciteContextException thrown =
        assertThrows(
            CalciteContextException.class,
            () -> extractLineage(basicSystemQueryContext, sql),
            "Expected extractLineage() to throw, but it didn't");

    assertTrue(thrown.getMessage().contains("Column 'filed_not_found' not found in any table"));
  }

  private void validateField(TableLineage lineage, int i, String name, String type) {
    assertEquals(name, lineage.getFields().getFieldList().get(i).getName());
    assertThat(lineage.getFields().getFieldList().get(i).getType()).asString().startsWith(type);
  }

  private void validateUpstream(TableLineage lineage, int i, List<String> table) {
    assertEquals(1, lineage.getUpstreams().get(i).getLevel());
    assertEquals(table, lineage.getUpstreams().get(i).getDatasetPathList());
  }

  private void validateFieldUpstream(
      TableLineage lineage, int i, String name, List<Origin> fieldUpstreams) {
    assertEquals(name, lineage.getFieldUpstreams().get(i).getName());
    assertEquals(fieldUpstreams, lineage.getFieldUpstreams().get(i).getOriginsList());
  }

  private static QueryContext newQueryContext(
      SabotQueryContext sabotQueryContext, String username, List<String> context) {
    UserBitShared.QueryId queryId = UserBitShared.QueryId.newBuilder().build();
    UserSession session =
        UserSession.Builder.newBuilder()
            .withSessionOptionManager(
                new SessionOptionManagerImpl(sabotQueryContext.getOptionValidatorListing()),
                sabotQueryContext.getOptionManager())
            .withCredentials(
                UserBitShared.UserCredentials.newBuilder().setUserName(username).build())
            .withUserProperties(UserProtos.UserProperties.getDefaultInstance())
            .withDefaultSchema(context)
            .withSourceVersionMapping(Collections.emptyMap())
            .build();
    return sabotQueryContext
        .getQueryContextCreator()
        .createNewQueryContext(
            session, queryId, null, Long.MAX_VALUE, Predicates.alwaysTrue(), null, null);
  }

  private static QueryContext newQueryContext(
      SabotQueryContext sabotQueryContext, String username) {
    return newQueryContext(sabotQueryContext, username, Collections.emptyList());
  }
}
