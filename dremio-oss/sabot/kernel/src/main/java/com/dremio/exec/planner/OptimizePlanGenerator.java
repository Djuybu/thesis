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

import static com.dremio.exec.store.SystemSchemas.FILE_SIZE;
import static com.dremio.exec.store.SystemSchemas.ICEBERG_POS_DELETE_FILE_SCHEMA;
import static com.dremio.exec.store.SystemSchemas.PARTITION_SPEC_ID;
import static com.dremio.exec.store.SystemSchemas.POS;
import static com.dremio.exec.store.iceberg.IcebergUtils.getCurrentPartitionSpec;
import static com.dremio.exec.store.iceberg.IcebergUtils.hasEqualityDeletes;

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.ops.OptimizerRulesContext;
import com.dremio.exec.ops.SnapshotDiffContext;
import com.dremio.exec.physical.config.ImmutableManifestScanFilters;
import com.dremio.exec.planner.common.MoreRelOptUtil;
import com.dremio.exec.planner.logical.CreateTableEntry;
import com.dremio.exec.planner.logical.partition.PruneFilterCondition;
import com.dremio.exec.planner.physical.DistributionTrait;
import com.dremio.exec.planner.physical.FilterPrel;
import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.physical.PrelUtil;
import com.dremio.exec.planner.physical.ValuesPrel;
import com.dremio.exec.planner.sql.CalciteArrowHelper;
import com.dremio.exec.planner.sql.handlers.query.OptimizeOptions;
import com.dremio.exec.store.RecordWriter;
import com.dremio.exec.store.TableMetadata;
import com.dremio.exec.store.iceberg.IcebergScanPlanBuilder;
import com.dremio.exec.util.LongRange;
import com.dremio.service.namespace.dataset.proto.ScanStats;
import com.google.common.collect.ImmutableList;
import java.util.HashSet;
import java.util.Set;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.InvalidRelException;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.rex.RexVisitorImpl;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.util.Pair;
import org.apache.iceberg.PartitionSpec;

/***
 * Expand plans for OPTIMIZE TABLE
 */
public class OptimizePlanGenerator extends OptimizePlanGeneratorBase {
  private final OptimizeOptions optimizeOptions;
  private final Integer icebergCurrentPartitionSpecId;

  public OptimizePlanGenerator(
      RelOptTable table,
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelNode input,
      TableMetadata tableMetadata,
      CreateTableEntry createTableEntry,
      OptimizerRulesContext context,
      OptimizeOptions optimizeOptions,
      PruneFilterCondition partitionFilter) {
    super(table, cluster, traitSet, input, tableMetadata, createTableEntry, context);
    PartitionSpec currentPartitionSpec =
        getCurrentPartitionSpec(tableMetadata.getDatasetConfig().getPhysicalDataset());
    validatePruneCondition(partitionFilter);
    if (!isPartitionExpressionRequired(partitionFilter, currentPartitionSpec)) {
      partitionFilter = new PruneFilterCondition(partitionFilter.getPartitionRange(), null, null);
    }
    this.icebergCurrentPartitionSpecId =
        currentPartitionSpec != null ? currentPartitionSpec.specId() : 0;
    int minSpecId = icebergCurrentPartitionSpecId;
    /*
     * In case of filter, it should not use all the data files for compaction.
     * It filtered out and applies the target file size range.
     * If filter is not there, it compacts all the old data files irrespective of the target file size.
     * */
    if (partitionFilter != null
        && (partitionFilter.getPartitionRange() != null
            || partitionFilter.getPartitionExpression() != null)) {
      minSpecId = 0;
    }
    ScanStats deleteStats =
        tableMetadata
            .getDatasetConfig()
            .getPhysicalDataset()
            .getIcebergMetadata()
            .getDeleteManifestStats();
    ImmutableManifestScanFilters.Builder manifestScanFiltersBuilder =
        (deleteStats != null && deleteStats.getRecordCount() > 0)
            ? new ImmutableManifestScanFilters.Builder()
            : new ImmutableManifestScanFilters.Builder()
                .setSkipDataFileSizeRange(
                    new LongRange(
                        optimizeOptions.getMinFileSizeBytes(),
                        optimizeOptions.getMaxFileSizeBytes()))
                .setMinPartitionSpecId(minSpecId);
    this.planBuilder =
        new IcebergScanPlanBuilder(
            cluster,
            traitSet,
            table,
            tableMetadata,
            null,
            context,
            manifestScanFiltersBuilder.build(),
            partitionFilter);
    this.optimizeOptions = optimizeOptions;
  }

  /**
   * Optimize is only supported on partition columns. It validates if pruneFilterCondition contains
   * any non-partition columns and throws userException. else it returns a list of partition columns
   * from pruneFilterCondition.
   */
  private void validatePruneCondition(PruneFilterCondition pruneFilterCondition) {
    if (pruneFilterCondition != null && pruneFilterCondition.getNonPartitionRange() != null) {
      pruneFilterCondition
          .getNonPartitionRange()
          .accept(
              new RexVisitorImpl<Void>(true) {
                @Override
                public Void visitInputRef(RexInputRef inputRef) {
                  throw UserException.unsupportedError()
                      .message(
                          String.format(
                              "OPTIMIZE command is only supported on the partition columns - %s",
                              tableMetadata.getReadDefinition().getPartitionColumnsList()))
                      .buildSilently();
                }
              });
    }
  }

  /**
   * Use all the applicable data files in the case filter is on transformed partition expression.
   */
  private Boolean isPartitionExpressionRequired(
      PruneFilterCondition pruneFilterCondition, PartitionSpec partitionSpec) {
    Set<Integer> expressionSourceIds = new HashSet<>();
    if (pruneFilterCondition != null && pruneFilterCondition.getPartitionExpression() != null) {
      pruneFilterCondition
          .getPartitionExpression()
          .accept(
              new RexVisitorImpl<Void>(true) {
                @Override
                public Void visitInputRef(RexInputRef inputRef) {
                  expressionSourceIds.add(inputRef.getIndex() + 1);
                  return null;
                }
              });
    }
    for (Integer id : expressionSourceIds) {
      if (!partitionSpec.identitySourceIds().contains(id)) {
        return false;
      }
    }
    return true;
  }

  /*
   *
                               ┌──────────────────────┐
                               │IcebergWriterCommitter│
                               └─────────▲────────────┘
                               ┌─────────┴────────────┐
                               │      Union           │
                               └─────────▲────────────┘
                ┌────────────────────────┴────────────────────┐
   ┌────────────┴───────────────────┐             ┌───────────┴────────────┐
   │       TableFunctionPrel        │             │    WriterPrel          │
   │ (DELETED_DATA_FILES_METADATA)  │             │                        │
   └────────────▲───────────────────┘             └──────────▲─────────────┘
                │                                 ┌──────────┴─────────────┐
                │                                 │ TableFunctionPrel      │
                │                                 │ (DATA_FILE_SCAN)       │
                │                                 └──────────▲─────────────┘
  ┌─────────────┴──────────────────┐              ┌──────────┴─────────────┐
  │  IcebergManifestScanPrel       │              │ IcebergManifestScanPrel│
  │    (With Predicates)           │              │  (With Predicates)     │
  └──────────────▲─────────────────┘              └───────────▲────────────┘
  ┌──────────────┴─────────────────┐              ┌───────────┴────────────┐
  │   IcebergManifestListPrel      │              │IcebergManifestListPrel │
  └────────────────────────────────┘              └────────────────────────┘
   * */
  @Override
  public Prel getPlan() {
    try {
      // Optimize manifests only
      if (optimizeOptions.isOptimizeManifestsOnly()) {
        return getOptimizeManifestsOnlyPlan();
      }

      Prel rewritePlan =
          planBuilder.hasDeleteFiles()
              ? deleteAwareOptimizePlan(
                  planBuilder.buildDataScanWithSplitGen(
                      planBuilder.buildDataAndDeleteFileJoinAndAggregate(
                          buildRemoveSideDataFilePlan(), buildRemoveSideDeleteFilePlan())),
                  deleteDataFilePlan(planBuilder, SnapshotDiffContext.NO_SNAPSHOT_DIFF),
                  removeDeleteFilePlan())
              : getDataWriterPlan(
                  planBuilder.build(),
                  deleteDataFilePlan(planBuilder, SnapshotDiffContext.NO_SNAPSHOT_DIFF));
      if (optimizeOptions.isOptimizeManifestFiles()) { // Optimize data files as well as manifests
        rewritePlan = getOptimizeManifestTableFunctionPrel(rewritePlan, RecordWriter.SCHEMA);
      }
      return getOutputSummaryPlan(rewritePlan);
    } catch (InvalidRelException e) {
      throw new RuntimeException(e);
    }
  }

  private Prel getOptimizeManifestsOnlyPlan() {
    RelTraitSet manifestTraitSet = traitSet.plus(DistributionTrait.SINGLETON).plus(Prel.PHYSICAL);

    // OptimizeManifestTableFunction forwards the input to the next operator.
    // Hence, the values provided here will be supplied to the output in happy case.
    RelDataType rowType = OptimizeOutputSchema.getRelDataType(cluster.getTypeFactory(), true);
    RexBuilder rexBuilder = DremioRexBuilder.INSTANCE;
    ImmutableList<ImmutableList<RexLiteral>> tuples =
        ImmutableList.of(ImmutableList.of(rexBuilder.makeLiteral("Optimize table successful")));
    ValuesPrel valuesPrel = new ValuesPrel(cluster, manifestTraitSet, rowType, tuples);

    boolean isValueCastEnabled = PrelUtil.getSettings(cluster).isValueCastEnabled();
    return getOptimizeManifestTableFunctionPrel(
        valuesPrel, CalciteArrowHelper.fromCalciteRowTypeJson(rowType, isValueCastEnabled));
  }

  /**
   * @param input Manifest Scan (DATA), joined with Delete file reads - Files that have deletes
   *     linked to them will have a non-null POS column.
   * @return filter input for data files that need to be rewritten by applying the following
   *     conditions
   *     <ul>
   *       <li>File size not in ideal range
   *       <li>Partition spec not matching current partition
   *       <li>Has delete file(s) attached
   *     </ul>
   */
  @Override
  protected RelNode subOptimalDataFilesFilter(RelNode input) {
    RexBuilder rexBuilder = cluster.getRexBuilder();

    Pair<Integer, RelDataTypeField> dataFileSizeCol =
        MoreRelOptUtil.findFieldWithIndex(input.getRowType().getFieldList(), FILE_SIZE);
    Pair<Integer, RelDataTypeField> dataPartitionSpecIdCol =
        MoreRelOptUtil.findFieldWithIndex(input.getRowType().getFieldList(), PARTITION_SPEC_ID);
    Pair<Integer, RelDataTypeField> deleteDataFilePosCol =
        MoreRelOptUtil.findFieldWithIndex(input.getRowType().getFieldList(), POS);

    RexNode posCondition =
        rexBuilder.makeCall(
            SqlStdOperatorTable.IS_NOT_NULL,
            rexBuilder.makeInputRef(
                deleteDataFilePosCol.right.getType(), deleteDataFilePosCol.left));
    RexNode partitionSpecIdCondition =
        rexBuilder.makeCall(
            SqlStdOperatorTable.NOT_EQUALS,
            rexBuilder.makeInputRef(
                dataPartitionSpecIdCol.right.getType(), dataPartitionSpecIdCol.left),
            rexBuilder.makeLiteral(
                icebergCurrentPartitionSpecId, dataPartitionSpecIdCol.right.getType()));
    RexNode minFileSizeCondition =
        rexBuilder.makeCall(
            SqlStdOperatorTable.LESS_THAN,
            rexBuilder.makeInputRef(dataFileSizeCol.right.getType(), dataFileSizeCol.left),
            rexBuilder.makeLiteral(
                optimizeOptions.getMinFileSizeBytes(), dataFileSizeCol.right.getType()));
    RexNode maxFileSizeCondition =
        rexBuilder.makeCall(
            SqlStdOperatorTable.GREATER_THAN,
            rexBuilder.makeInputRef(dataFileSizeCol.right.getType(), dataFileSizeCol.left),
            rexBuilder.makeLiteral(
                optimizeOptions.getMaxFileSizeBytes(), dataFileSizeCol.right.getType()));

    RexNode filterCondition =
        rexBuilder.makeCall(
            SqlStdOperatorTable.OR,
            ImmutableList.of(
                posCondition,
                partitionSpecIdCondition,
                minFileSizeCondition,
                maxFileSizeCondition));
    return new FilterPrel(
        cluster, input.getTraitSet(), input, RexUtil.flatten(rexBuilder, filterCondition));
  }

  /** [*A*] from {@link #deleteAwareOptimizePlan} */
  @Override
  protected RelNode buildRemoveSideDataFilePlan() {
    if (hasEqualityDeletes(tableMetadata)) {
      // for equality deletes
      return buildRemoveSideDataFilePlanForEqualityDeletes();
    } else {
      // for positional deletes
      return subOptimalDataFilesFilter(
          planBuilder.buildDataManifestScanWithDeleteJoin(
              aggregateDeleteFiles(
                  planBuilder.buildDeleteFileScan(context, ICEBERG_POS_DELETE_FILE_SCHEMA))));
    }
  }
}
