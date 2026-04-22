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
package com.dremio.exec.planner.logical;

import static com.dremio.exec.planner.common.MoreRelOptUtil.extendTableMetadataSchema;

import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.planner.common.ScanRelBase;
import com.dremio.exec.planner.logical.drel.TableMetadataScan;
import com.dremio.exec.planner.rules.DremioOptimizationRelRule;
import com.dremio.exec.store.TableMetadata;
import com.dremio.exec.store.dfs.FilterableScan;
import com.dremio.service.namespace.DatasetHelper;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.IcebergMetadata;
import com.dremio.service.namespace.dataset.proto.PhysicalDataset;
import com.dremio.service.namespace.dataset.proto.ScanStats;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.rules.TransformationRule;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.tools.RelBuilder;

/**
 * Tables such as Iceberg tables maintain certain column level metadata. It is possible to take
 * advantage of this metadata to skip data file scanning completely, under certain circumstances.
 *
 * <p>This rule pushes certain aggregate functions that can read from the column metadata, such as
 * min/max, into a scan. An aggregate can only be pushed if there are no group by columns and all
 * aggregate functions can be pushed into the scan, otherwise a full data scan is necessary.
 */
public class PushAggregateIntoScanRule
    extends DremioOptimizationRelRule<PushAggregateIntoScanRule.Config>
    implements TransformationRule {

  public static final RelOptRule INSTANCE = Config.DEFAULT.toRule();

  private PushAggregateIntoScanRule(Config config) {
    super(config);
  }

  @Override
  public boolean matches(RelOptRuleCall call) {
    Aggregate aggregate = call.rel(0);
    ScanRelBase scan = call.rel(1);
    // The projected columns need to match the columns referenced in the aggregate. If this is not
    // the case, then there are scan filters that require a full data scan and this optimization
    // cannot be applied.
    return scan.getProjectedSchema().getFieldCount() == RelOptUtil.getAllFields(aggregate).size();
  }

  @Override
  public void doOnMatch(RelOptRuleCall call) {
    RelBuilder relBuilder = call.builder();
    Aggregate aggregate = call.rel(0);
    ScanRelBase scan = call.rel(1);

    List<AggregateCall> aggregateCalls = aggregate.getAggCallList();
    List<AggregateCall> newAggregateCalls = new ArrayList<>(aggregateCalls.size());
    List<RelDataTypeField> scanColumns = scan.getRowType().getFieldList();
    List<SchemaPath> metadataColumns = new ArrayList<>(aggregateCalls.size());
    RelDataTypeFactory typeFactory = relBuilder.getTypeFactory();
    RelDataTypeFactory.Builder typeBuilder = new RelDataTypeFactory.Builder(typeFactory);

    // Project new metadata columns that will be understood by the execution
    String columnName;
    for (int i = 0; i < aggregateCalls.size(); i++) {
      AggregateCall aggCall = aggregateCalls.get(i);
      SqlKind func = aggCall.getAggregation().getKind();
      switch (func) {
        case MIN:
          columnName = scanColumns.get(aggCall.getArgList().get(0)).getName() + "_min";
          break;
        case MAX:
          columnName = scanColumns.get(aggCall.getArgList().get(0)).getName() + "_max";
          break;
        default:
          throw new UnsupportedOperationException(
              "Rule does not support aggregate function type " + func);
      }

      // New agg call will simply aggregate on top of the new scan column
      newAggregateCalls.add(aggCall.copy(ImmutableList.of(i)));
      metadataColumns.add(SchemaPath.getSimplePath(columnName));
      typeBuilder.add(columnName, aggCall.getType());
    }

    RelNode aggregatesPushed =
        relBuilder
            // Create a new scan that reads from metadata
            .push(
                new TableMetadataScan(
                    scan.getCluster(),
                    scan.getTraitSet(),
                    scan.getTable(),
                    scan.getPluginId(),
                    extendTableMetadataSchema(typeBuilder.build(), scan),
                    metadataColumns,
                    scan.getHints(),
                    newAggregateCalls))
            // Aggregate on top of the new output columns
            .aggregate(relBuilder.groupKey(), newAggregateCalls)
            .build();
    call.transformTo(aggregatesPushed);
  }

  public interface Config extends RelRule.Config {

    Config DEFAULT =
        RelRule.Config.EMPTY
            .withDescription(PushAggregateIntoScanRule.class.getSimpleName())
            .withRelBuilderFactory(DremioRelFactories.LOGICAL_BUILDER)
            .withOperandSupplier(
                op1 ->
                    op1.operand(Aggregate.class)
                        .predicate(Config::aggregateMatches)
                        .oneInput(
                            op2 ->
                                op2.operand(ScanRelBase.class)
                                    .predicate(Config::scanMatches)
                                    .noInputs()))
            .as(PushAggregateIntoScanRule.Config.class);

    static boolean aggregateMatches(Aggregate aggregate) {
      return Aggregate.isSimple(aggregate)
          && aggregate.getGroupCount() == 0
          && aggregate.getAggCallList().stream()
              .allMatch(
                  aggCall -> {
                    if (aggCall.hasFilter()) {
                      return false;
                    }
                    SqlKind func = aggCall.getAggregation().getKind();
                    return func == SqlKind.MIN || func == SqlKind.MAX;
                  });
    }

    static boolean scanMatches(ScanRelBase scan) {
      // Skip if we are already doing a metadata scan. We identify and apply all operations together
      // at once. We do not support pushing operations iteratively.
      if ((scan instanceof TableMetadataScan)) {
        return false;
      }

      // TODO Find a better way to abstract away table format details
      boolean isIceberg =
          Optional.of(scan)
              .map(ScanRelBase::getTableMetadata)
              .map(TableMetadata::getDatasetConfig)
              .map(DatasetHelper::isIcebergDataset)
              .orElse(false);
      if (!isIceberg) {
        return false;
      }

      // Apply only if there is no filter pushed into the scan.
      if (scan instanceof FilterableScan) {
        if (((FilterableScan) scan).hasFilter()) {
          return false;
        }
      }

      // We cannot convert into a pure metadata operation if there are delete files present in this
      // dataset. The reason being that it is not possible to know the nature of the deleted records
      // without a full data scan.
      return Optional.of(scan)
          .map(ScanRelBase::getTableMetadata)
          .map(TableMetadata::getDatasetConfig)
          .map(DatasetConfig::getPhysicalDataset)
          .map(PhysicalDataset::getIcebergMetadata)
          .map(IcebergMetadata::getDeleteStats)
          .map(ScanStats::getRecordCount)
          .filter(recordCount -> recordCount > 0)
          .isEmpty();
    }

    @Override
    default RelOptRule toRule() {
      return new PushAggregateIntoScanRule(this);
    }
  }
}
