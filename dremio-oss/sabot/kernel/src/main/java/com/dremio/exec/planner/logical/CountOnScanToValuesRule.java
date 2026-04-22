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
import static com.dremio.service.namespace.DatasetHelper.isConvertedIcebergDataset;
import static com.dremio.service.namespace.DatasetHelper.isIcebergDataset;

import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.planner.common.ScanRelBase;
import com.dremio.exec.planner.logical.drel.TableMetadataScan;
import com.dremio.exec.store.TableMetadata;
import com.dremio.exec.store.dfs.FilterableScan;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.dremio.service.namespace.dataset.proto.IcebergMetadata;
import com.dremio.service.namespace.dataset.proto.ReadDefinition;
import com.dremio.service.namespace.dataset.proto.ScanStats;
import com.google.common.base.Preconditions;
import java.util.List;
import java.util.Optional;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.tools.RelBuilder;

/**
 * We can optimize queries like " select count(*) as mycount from table " or " select count(
 * not-nullable-expr) as mycount from table " by reading count from table metadata instead of doing
 * a full data scan. This rule looks for this kind of patterns in the plan and adds a table metadata
 * scan to read the record count from table metadata.
 *
 * <p>Currently, only the native iceberg dataset is supported.
 */
public class CountOnScanToValuesRule extends RelRule<CountOnScanToValuesRule.Config> {

  public CountOnScanToValuesRule(Config config) {
    super(config);
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    RelBuilder relBuilder = call.builder();
    Aggregate agg = call.rel(0);
    ScanRelBase scan = call.rel(1);

    Preconditions.checkArgument(agg.getRowType().getFieldCount() == 1);
    RelDataTypeField countStarField = agg.getRowType().getFieldList().get(0);

    RelDataTypeFactory typeFactory = relBuilder.getTypeFactory();
    RelDataTypeFactory.Builder typeBuilder = new RelDataTypeFactory.Builder(typeFactory);
    String columnName = countStarField.getName() + "_str";
    typeBuilder.add(columnName, countStarField.getType());

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
                    List.of(SchemaPath.getSimplePath(columnName)),
                    scan.getHints(),
                    agg.getAggCallList()))
            // Force project to match the row type of the output with the input.
            .project(List.of(relBuilder.field(0)), List.of(countStarField.getName()), true)
            .build();
    call.transformTo(aggregatesPushed);
  }

  /** Rule configuration. */
  public interface Config extends RelRule.Config {
    Config AGG_ON_SCAN =
        EMPTY
            .withDescription(CountOnScanToValuesRule.class.getSimpleName())
            .withRelBuilderFactory(DremioRelFactories.LOGICAL_BUILDER)
            .withOperandSupplier(
                b0 ->
                    b0.operand(Aggregate.class)
                        .predicate(Config::aggregateMatches)
                        .oneInput(
                            b2 ->
                                b2.operand(ScanRelBase.class)
                                    .predicate(Config::scanMatches)
                                    .noInputs()))
            .as(Config.class);

    static boolean aggregateMatches(Aggregate aggregate) {
      // Only apply the rule when :
      //    1) No GroupBY key,
      //    2) only one agg function (Check if it's count(*) below).
      //    3) No distinct agg call.
      if (!(aggregate.getGroupCount() == 0
          && aggregate.getAggCallList().size() == 1
          && !aggregate.containsDistinctCall())) {
        return false;
      }

      AggregateCall aggCall = aggregate.getAggCallList().get(0);

      //  count(*)  == >  empty arg  ==>  rowCount
      //  count(Not-null-input) ==> rowCount
      return aggCall.getAggregation().getName().equals("COUNT")
          && (aggCall.getArgList().isEmpty()
              || (aggCall.getArgList().size() == 1
                  && !aggregate
                      .getInput()
                      .getRowType()
                      .getFieldList()
                      .get(aggCall.getArgList().get(0))
                      .getType()
                      .isNullable()));
    }

    static boolean scanMatches(ScanRelBase scan) {
      // Skip if we are already doing a metadata scan.
      if ((scan instanceof TableMetadataScan)) {
        return false;
      }

      // Check to avoid NPEs.
      if (Optional.ofNullable(scan.getTableMetadata())
          .map(TableMetadata::getReadDefinition)
          .map(ReadDefinition::getScanStats)
          .map(ScanStats::getRecordCount)
          .isEmpty()) {
        return false;
      }

      final DatasetConfig datasetConfig = scan.getTableMetadata().getDatasetConfig();

      // we only want to support this for native iceberg.
      // Check out ConvertCountToDirectScan for unlimited splits.
      if (isConvertedIcebergDataset(datasetConfig) || !isIcebergDataset(datasetConfig)) {
        return false;
      }

      // Apply only if there is no filter pushed into the scan.
      if (scan instanceof FilterableScan) {
        if (((FilterableScan) scan).hasFilter()) {
          return false;
        }
      }

      // This optimization cannot apply if there are position or equality
      // delete files present in this dataset. A full data scan would be
      // necessary since there is no way to accurately determine the record
      // count based on the manifest data.
      return Optional.ofNullable(datasetConfig.getPhysicalDataset().getIcebergMetadata())
          .map(IcebergMetadata::getDeleteStats)
          .map(ScanStats::getRecordCount)
          .filter(recordCount -> recordCount > 0)
          .isEmpty();
    }

    @Override
    default CountOnScanToValuesRule toRule() {
      return new CountOnScanToValuesRule(this);
    }
  }
}
