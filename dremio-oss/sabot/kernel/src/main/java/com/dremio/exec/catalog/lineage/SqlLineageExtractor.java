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

import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.catalog.model.dataset.TableVersionContext;
import com.dremio.exec.catalog.DremioTable;
import com.dremio.exec.ops.PlannerCatalogImpl;
import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.planner.acceleration.ExpansionNode;
import com.dremio.exec.planner.common.ScanRelBase;
import com.dremio.exec.planner.logical.UnexpandedViewTable;
import com.dremio.exec.planner.logical.ViewTable;
import com.dremio.exec.planner.sql.ParserConfig;
import com.dremio.exec.planner.sql.SqlConverter;
import com.dremio.exec.planner.sql.SqlValidatorAndToRelContext;
import com.dremio.exec.planner.sql.ViewExpander;
import com.dremio.exec.tablefunctions.ExternalQueryRelBase;
import com.dremio.exec.util.QueryVersionUtils;
import com.dremio.options.OptionResolver;
import com.dremio.service.namespace.dataset.proto.DatasetType;
import com.dremio.service.namespace.dataset.proto.FieldOrigin;
import com.dremio.service.namespace.dataset.proto.ParentDataset;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.util.List;
import java.util.Set;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelShuttleImpl;
import org.apache.calcite.rel.core.TableScan;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;

public final class SqlLineageExtractor {

  private SqlLineageExtractor() {}

  public static TableLineage extractLineage(QueryContext queryContext, String sql)
      throws SqlParseException {
    SqlNode sqlNode = parseSql(sql);
    SqlConverter convertor = QueryVersionUtils.getNewConverter(queryContext);

    PlannerCatalogWithoutViewExpansion plannerCatalog =
        new PlannerCatalogWithoutViewExpansion(
            queryContext, convertor.getViewExpander(), convertor.getOptionResolver());

    SqlValidatorAndToRelContext.Builder builder =
        new SqlValidatorAndToRelContext.Builder(
            convertor.getUserQuerySqlValidatorAndToRelContextBuilderFactory().builder(),
            plannerCatalog);

    SqlValidatorAndToRelContext context = builder.build();

    RelNode relNode = context.validateAndConvertForExpression(sqlNode);

    ImmutableTableLineage.Builder lineageBuilder = new ImmutableTableLineage.Builder();

    return lineageBuilder
        .setFields(getFields(relNode))
        .addAllUpstreams(getUpstreams(queryContext, relNode))
        .addAllFieldUpstreams(getFieldUpstreams(relNode))
        .build();
  }

  private static RelDataType getFields(RelNode relNode) {
    return relNode.getRowType();
  }

  private static List<ParentDataset> getUpstreams(QueryContext queryContext, RelNode relNode) {
    Set<CatalogEntityKey> added = Sets.newHashSet();
    List<ParentDataset> parents = Lists.newArrayList();

    relNode.accept(
        new RelShuttleImpl() {
          @Override
          public RelNode visit(RelNode other) {
            List<String> path = null;
            TableVersionContext versionContext = null;
            if (other instanceof ExpansionNode) {
              path = ((ExpansionNode) other).getPath().getPathComponents();
              versionContext = ((ExpansionNode) other).getVersionContext();
            } else if (other instanceof ExternalQueryRelBase) {
              path = ((ExternalQueryRelBase) other).getPath().getPathComponents();
            }

            if (path != null) {
              addParent(path, versionContext);
              return other;
            }

            return super.visit(other);
          }

          @Override
          public RelNode visit(TableScan scan) {
            TableVersionContext versionContext =
                (scan instanceof ScanRelBase)
                    ? ((ScanRelBase) scan).getTableMetadata().getVersionContext()
                    : null;
            addParent(scan.getTable().getQualifiedName(), versionContext);

            return scan;
          }

          private void addParent(List<String> path, TableVersionContext versionContext) {
            CatalogEntityKey catalogEntityKey =
                CatalogEntityKey.newBuilder()
                    .keyComponents(path)
                    .tableVersionContext(versionContext)
                    .build();

            if (added.add(catalogEntityKey)) {
              DatasetType datasetType =
                  queryContext
                      .getCatalogService()
                      .getSystemUserCatalog()
                      .getDatasetType(catalogEntityKey);

              ParentDataset parent =
                  new ParentDataset()
                      .setDatasetPathList(path)
                      .setVersionContext(versionContext == null ? null : versionContext.serialize())
                      .setType(datasetType)
                      .setLevel(1);

              parents.add(parent);
            }
          }
        });

    return parents;
  }

  private static List<FieldOrigin> getFieldUpstreams(RelNode relNode) {
    return FieldOriginExtractor.getFieldOrigins(relNode, relNode.getRowType());
  }

  private static SqlNode parseSql(String sql) throws SqlParseException {
    ParserConfig parserConfig = new ParserConfig(Quoting.DOUBLE_QUOTE, 1000, true);
    SqlParser parser = SqlParser.create(sql, parserConfig);
    return parser.parseStmt();
  }

  private static class PlannerCatalogWithoutViewExpansion extends PlannerCatalogImpl {
    public PlannerCatalogWithoutViewExpansion(
        QueryContext context, ViewExpander viewExpander, OptionResolver optionResolver) {
      super(context, viewExpander, optionResolver);
    }

    @Override
    protected DremioTable convertView(DremioTable table) {
      if (!(table instanceof ViewTable)) {
        return table;
      }

      return new UnexpandedViewTable((ViewTable) table);
    }
  }
}
