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
package com.dremio.exec.planner;

import static com.dremio.exec.planner.physical.PlannerSettings.ENABLE_OUTPUT_LIMITS;
import static com.dremio.exec.planner.physical.PlannerSettings.OUTPUT_LIMIT_SIZE;
import static com.dremio.exec.planner.physical.PlannerSettings.QUERY_RESULTS_STORE_TABLE;
import static com.dremio.exec.planner.physical.PlannerSettings.USE_QUERY_RESULT_WRITER_REL;

import com.dremio.common.utils.protos.QueryIdHelper;
import com.dremio.exec.catalog.Catalog;
import com.dremio.exec.catalog.CatalogUser;
import com.dremio.exec.catalog.CreateTableOptions;
import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.physical.base.WriterOptions;
import com.dremio.exec.planner.logical.CreateTableEntry;
import com.dremio.exec.planner.logical.Rel;
import com.dremio.exec.planner.logical.WriterRel;
import com.dremio.exec.planner.logical.drel.LogicalResultWriterRel;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.planner.sql.handlers.SqlHandlerConfig;
import com.dremio.exec.store.easy.arrow.ArrowFormatPlugin;
import com.dremio.options.OptionManager;
import com.dremio.options.OptionResolver;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.users.SystemUser;
import com.google.common.collect.ImmutableMap;
import java.util.List;
import java.util.Map;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.commons.lang3.text.StrTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ResultWriterUtils {
  // Query results are stored in arrow format. If need arises, we can change this to a
  // configuration option.
  public static final Map<String, Object> STORAGE_OPTIONS =
      ImmutableMap.<String, Object>of("type", ArrowFormatPlugin.ARROW_DEFAULT_NAME);
  public static final CreateTableOptions CREATE_TABLE_OPTIONS =
      CreateTableOptions.builder().setIsJobsResultsTable(true).build();
  private static final Logger LOGGER = LoggerFactory.getLogger(ResultWriterUtils.class);

  private ResultWriterUtils() {}

  public static CreateTableEntry buildCreateTableEntryForResults(
      SqlHandlerConfig sqlHandlerConfig) {
    OptionResolver optionResolver = sqlHandlerConfig.getConverter().getOptionResolver();
    QueryContext queryContext = sqlHandlerConfig.getContext();

    Catalog catalog =
        queryContext.getCatalog().resolveCatalog(CatalogUser.from(SystemUser.SYSTEM_USERNAME));
    NamespaceKey path = generateStoreTablePath(sqlHandlerConfig);
    return catalog.createNewTable(
        path, null, buildWriterOptions(optionResolver), STORAGE_OPTIONS, CREATE_TABLE_OPTIONS);
  }

  /**
   * When enabled, add a writer rel on top of the given rel to catch the output and write to
   * configured store table.
   *
   * @param inputRel
   * @return
   */
  public static Rel storeQueryResultsIfNeeded(SqlHandlerConfig sqlHandlerConfig, Rel inputRel) {
    QueryContext queryContext = sqlHandlerConfig.getContext();
    final OptionManager options = queryContext.getOptions();

    final PlannerSettings.StoreQueryResultsPolicy storeQueryResultsPolicy =
        queryContext.getPlannerSettings().storeQueryResultsPolicy();

    switch (storeQueryResultsPolicy) {
      case NO:
        return inputRel;
      case DIRECT_PATH:
      case PATH_AND_ATTEMPT_ID:
        // supported cases
        break;
      default:
        LOGGER.warn(
            "Unknown query result store policy {}. Query results won't be saved",
            storeQueryResultsPolicy);
        return inputRel;
    }

    if (options.getOption(USE_QUERY_RESULT_WRITER_REL)) {
      return new LogicalResultWriterRel(
          inputRel.getCluster(), inputRel.getCluster().traitSet().plus(Rel.LOGICAL), inputRel);
    } else {
      return new WriterRel(
          inputRel.getCluster(),
          inputRel.getCluster().traitSet().plus(Rel.LOGICAL),
          inputRel,
          buildCreateTableEntryForResults(sqlHandlerConfig),
          inputRel.getRowType());
    }
  }

  public static NamespaceKey generateStoreTablePath(SqlHandlerConfig sqlHandlerConfig) {
    PlannerSettings plannerSettings = sqlHandlerConfig.getConverter().getSettings();
    PlannerSettings.StoreQueryResultsPolicy storeQueryResultsPolicy =
        plannerSettings.storeQueryResultsPolicy();

    SqlParser.Config config = sqlHandlerConfig.getConverter().getParserConfig();
    QueryContext context = sqlHandlerConfig.getContext();
    OptionManager options = sqlHandlerConfig.getContext().getOptions();

    final String storeTablePath =
        options.getOption(QUERY_RESULTS_STORE_TABLE.getOptionName()).getStringVal();
    final List<String> storeTable =
        new StrTokenizer(storeTablePath, '.', config.quoting().string.charAt(0))
            .setIgnoreEmptyTokens(true)
            .getTokenList();

    if (storeQueryResultsPolicy == PlannerSettings.StoreQueryResultsPolicy.PATH_AND_ATTEMPT_ID) {
      // QueryId is same as attempt id. Using its string form for the table name
      storeTable.add(QueryIdHelper.getQueryId(context.getQueryId()));
    }
    return new NamespaceKey(storeTable);
  }

  public static WriterOptions buildWriterOptions(OptionResolver options) {
    if (options.getOption(ENABLE_OUTPUT_LIMITS)) {
      return WriterOptions.DEFAULT
          .withOutputLimitEnabled(options.getOption(ENABLE_OUTPUT_LIMITS))
          .withOutputLimitSize(options.getOption(OUTPUT_LIMIT_SIZE));
    } else {
      return WriterOptions.DEFAULT;
    }
  }
}
