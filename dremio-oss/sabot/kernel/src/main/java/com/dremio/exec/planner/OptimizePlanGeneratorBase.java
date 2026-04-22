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

import static com.dremio.exec.planner.OptimizeOutputSchema.NEW_DATA_FILES_COUNT;
import static com.dremio.exec.planner.OptimizeOutputSchema.REWRITTEN_DATA_FILE_COUNT;
import static com.dremio.exec.planner.OptimizeOutputSchema.REWRITTEN_DELETE_FILE_COUNT;
import static com.dremio.exec.store.RecordWriter.OPERATION_TYPE_COLUMN;
import static com.dremio.exec.store.RecordWriter.RECORDS_COLUMN;
import static com.dremio.exec.store.SystemSchemas.DATAFILE_PATH;
import static com.dremio.exec.store.SystemSchemas.DELETE_FILE_PATH;
import static com.dremio.exec.store.SystemSchemas.ICEBERG_METADATA;
import static com.dremio.exec.store.SystemSchemas.ICEBERG_POS_DELETE_FILE_SCHEMA;
import static com.dremio.exec.store.SystemSchemas.IMPLICIT_SEQUENCE_NUMBER;
import static com.dremio.exec.store.SystemSchemas.POS;
import static com.dremio.exec.store.iceberg.IcebergUtils.hasEqualityDeletes;
import static org.apache.calcite.sql.fun.SqlStdOperatorTable.CASE;
import static org.apache.calcite.sql.fun.SqlStdOperatorTable.EQUALS;
import static org.apache.calcite.sql.fun.SqlStdOperatorTable.SUM;
import static org.apache.calcite.sql.type.SqlTypeName.BIGINT;
import static org.apache.calcite.sql.type.SqlTypeName.INTEGER;

import com.dremio.exec.ops.OptimizerRulesContext;
import com.dremio.exec.physical.config.TableFunctionConfig;
import com.dremio.exec.physical.config.TableFunctionContext;
import com.dremio.exec.planner.common.MoreRelOptUtil;
import com.dremio.exec.planner.logical.CreateTableEntry;
import com.dremio.exec.planner.physical.DistributionTrait;
import com.dremio.exec.planner.physical.HashAggPrel;
import com.dremio.exec.planner.physical.PlannerSettings;
import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.physical.PrelUtil;
import com.dremio.exec.planner.physical.ProjectPrel;
import com.dremio.exec.planner.physical.StreamAggPrel;
import com.dremio.exec.planner.physical.TableFunctionPrel;
import com.dremio.exec.planner.physical.TableFunctionUtil;
import com.dremio.exec.planner.physical.UnionAllPrel;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.OperationType;
import com.dremio.exec.store.RecordWriter;
import com.dremio.exec.store.TableMetadata;
import com.dremio.exec.store.iceberg.IcebergScanPlanBuilder;
import com.dremio.exec.store.iceberg.ManifestContentType;
import com.dremio.exec.store.iceberg.OptimizeManifestsTableFunctionContext;
import com.dremio.exec.store.iceberg.model.ImmutableManifestScanOptions;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.InvalidRelException;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.calcite.util.Pair;

/***
 * Base class for Optimize command plan
 * Core steps of an abstract optimize command:
 * 1. Select target data files to optimize
 * 2. rewrite selected data files
 *
 * OptimizePlanGenerator and OptimizeClusteringPlanGenerator are two subclasses. Their main difference is how to
 * select target data files:
 * a. OptimizePlanGenerator select target data files whose sizes are between [minFileSize, maxFileSize],
 *    and data files with delete files.
 * b. OptimizeClusteringPlanGenerator select overlapping data files which group into clusters.
 * The rewriting phase is also slightly different for OptimizePlanGenerator and OptimizeClusteringPlanGenerator.
 */
public abstract class OptimizePlanGeneratorBase extends TableManagementPlanGenerator {
  protected IcebergScanPlanBuilder planBuilder;

  public OptimizePlanGeneratorBase(
      RelOptTable table,
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelNode input,
      TableMetadata tableMetadata,
      CreateTableEntry createTableEntry,
      OptimizerRulesContext context) {
    super(table, cluster, traitSet, input, tableMetadata, createTableEntry, context);
  }

  /*
   * Plan for OPTIMIZE TABLE operation when table has positional delete files.
   *
   * The left side of the plan is used to mark which files need to be rewritten. It has 2 marked boxes for reuse:
   * Section *A*
   *   Left Branch of section *A* is used to scan DATA manifests and filter these data file objects based on input from
   *   Right Branch which scans DELETE manifests and reads positional delete files. This filtering of data files is done
   *   based on the following conditions:
   *   - File size not in ideal range
   *   - Partition Spec not current
   *   - Data file has Delete File attached to it
   *
   * Section *B*
   *   Used to read DELETE manifests and pass file objects to DELETED_FILES_METADATA table function
   *
   * The right-most branch of the plan is used to write new data files - It takes as input the plan from boxes A and B
   * to mark the input data that needs to be rewritten into ideally sized files.
   *
                                                                                ┌──────────────────────┐
                                                                                │IcebergWriterCommitter│
                                                                                └─────────▲────────────┘
                                                                                ┌─────────┴────────────┐
                                                                                │      Union           │
                                                                                └─────────▲────────────┘
                                          ┌───────────────────────────────────────────────┴──────────────────────────────────────┐
                                          │                                                                                      │
                                ┌─────────┴────────────┐                                                                         │
                                │      Union           │                                                                         │
                                └─────────▲────────────┘                                                                         │
                 ┌────────────────────────┴───────────────────────────────────────────────────────┐                              │
    ┌────────────┴───────────────────┐                                               ┌────────────┴──────────────┐    ┌──────────┴───────────┐
    │       TableFunctionPrel        │                                               │       TableFunctionPrel   │    │    WriterPrel        │
    │ (DELETED_FILES_METADATA)       │                                               │ (DELETED_FILES_METADATA)  │    └──────────▲───────────┘
    └────────────▲───────────────────┘                                               └────────────▲──────────────┘               │
  ┌──────────────│─────────────────────────────────────────────────────────────┐  ┌───────────────│───────────────┐   ┌──────────┴───────────┐
  │   ┌──────────┴─────────────┐                                           *A* │  │               │            *B*│   │ TableFunctionPrel    │
  │   │       Filter           │                                               │  │               │               │   │ (DATA_FILE_SCAN)     │
  │   └──────────▲─────────────┘                                               │  │               │               │   └──────────▲───────────┘
  │   ┌──────────┴─────────────┐                                               │  │               │               │              │
  │   │       HashJoin         │──────────────────────────────┐                │  │               │               │   ┌──────────┴───────────┐
  │   └──────────▲─────────────┘                              │                │  │               │               │   │ TableFunctionPrel    │
  │              │                                 ┌──────────┴─────────────┐  │  │               │               │   │(IcebergDeleteFileAgg)│
  │              │                                 │    HashAggPrel         │  │  │               │               │   └──────────▲───────────┘
  │              │                                 └──────────▲─────────────┘  │  │               │               │              │
  │              │                                 ┌──────────┴─────────────┐  │  │               │               │   ┌──────────┴───────────┐
  │              │                                 │ TableFunctionPrel      │  │  │               │               │   │       HashJoin       │
  │              │                                 │ (DATA_FILE_SCAN)       │  │  │               │               │   └──────────▲───────────┘
  │              │                                 └──────────▲─────────────┘  │  │               │               │              │
  │┌─────────────┴──────────────────┐              ┌──────────┴─────────────┐  │  │    ┌──────────┴─────────────┐ │              │
  ││  IcebergManifestScanPrel       │              │ IcebergManifestScanPrel│  │  │    │ IcebergManifestScanPrel│ │        ┌─────┴──────┐
  ││    DATA                        │              │  DELETE                │  │  │    │  DELETE                │ │  ┌─────┴────┐  ┌────┴─────┐
  │└──────────────▲─────────────────┘              └───────────▲────────────┘  │  │    └───────────▲────────────┘ │  │          │  │          │
  │┌──────────────┴─────────────────┐              ┌───────────┴────────────┐  │  │    ┌───────────┴────────────┐ │  │    *A*   │  │   *B*    │
  ││   IcebergManifestListPrel      │              │IcebergManifestListPrel │  │  │    │IcebergManifestListPrel │ │  │          │  │          │
  │└────────────────────────────────┘              └────────────────────────┘  │  │    └────────────────────────┘ │  └──────────┘  └──────────┘
  └────────────────────────────────────────────────────────────────────────────┘  └───────────────────────────────┘

   * Plan for OPTIMIZE TABLE operation when table has equality delete files.
   *
   * The overall structure is similar but Section *A* will be simpler
   * The purpose of Section *A* is still to find the DATA file list that needs to be updated. but unlike Positional deletes, equality delete file contains
   * no data file information, we will simply return all the data files. As the result, Section *A* will looks like:
                 ▲
  ┌──────────────│───────────────────┐
  │              │             *A*   │
  │┌─────────────┴──────────────────┐│
  ││  IcebergManifestScanPrel       ││
  ││    DATA                        ││
  │└──────────────▲─────────────────┘│
  │┌──────────────┴─────────────────┐│
  ││   IcebergManifestListPrel      ││
  │└────────────────────────────────┘│
  └──────────────────────────────────┘
   * */
  protected Prel deleteAwareOptimizePlan(
      RelNode dataScanPlan, RelNode dataFilesToRemove, RelNode deleteFilesToRemove)
      throws InvalidRelException {
    return getDataWriterPlan(
        dataScanPlan,
        manifestWriterPlan -> {
          try {
            return getMetadataWriterPlan(
                dataFilesToRemove, deleteFilesToRemove, manifestWriterPlan);
          } catch (InvalidRelException e) {
            throw new RuntimeException(e);
          }
        });
  }

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

  /***
   * filter input for data files that need to be rewritten by applying certain conditions
   */
  protected abstract RelNode subOptimalDataFilesFilter(RelNode input);

  /**
   * Scan the manifests to return the purged delete files. Plan same as {@link #deleteDataFilePlan}
   * with manifest scan operator reading DELETE manifests instead of DATA.
   */
  protected RelNode removeDeleteFilePlan() {
    RexBuilder rexBuilder = cluster.getRexBuilder();
    RelNode output = buildRemoveSideDeleteFilePlan();
    Pair<Integer, RelDataTypeField> outputFilePathCol =
        MoreRelOptUtil.findFieldWithIndex(output.getRowType().getFieldList(), DATAFILE_PATH);
    Pair<Integer, RelDataTypeField> outputIcebergMetadataCol =
        MoreRelOptUtil.findFieldWithIndex(output.getRowType().getFieldList(), ICEBERG_METADATA);

    final List<RexNode> outputExpressions =
        ImmutableList.of(
            rexBuilder.makeBigintLiteral(BigDecimal.ONE),
            rexBuilder.makeInputRef(outputFilePathCol.right.getType(), outputFilePathCol.left),
            rexBuilder.makeInputRef(
                outputIcebergMetadataCol.right.getType(), outputIcebergMetadataCol.left));

    RelDataType outputRowType =
        RexUtil.createStructType(
            rexBuilder.getTypeFactory(),
            outputExpressions,
            OptimizePlanGenerator.deleteFilesMetadataInputCols,
            SqlValidatorUtil.F_SUGGESTER);

    return ProjectPrel.create(
        output.getCluster(), output.getTraitSet(), output, outputExpressions, outputRowType);
  }

  protected Prel getMetadataWriterPlan(
      RelNode dataFileAggrPlan, RelNode deleteFileAggrPlan, RelNode manifestWriterPlan)
      throws InvalidRelException {
    // Insert a table function that'll pass the path through and set the OperationType
    TableFunctionPrel deletedDataFilesTableFunctionPrel =
        getDeleteFilesMetadataTableFunctionPrel(
            dataFileAggrPlan,
            getProjectedColumns(),
            TableFunctionUtil.getDeletedFilesMetadataTableFunctionContext(
                OperationType.DELETE_DATAFILE, RecordWriter.SCHEMA, getProjectedColumns(), true));
    TableFunctionPrel deletedDeleteFilesTableFunctionPrel =
        getDeleteFilesMetadataTableFunctionPrel(
            deleteFileAggrPlan,
            getProjectedColumns(),
            TableFunctionUtil.getDeletedFilesMetadataTableFunctionContext(
                OperationType.DELETE_DELETEFILE, RecordWriter.SCHEMA, getProjectedColumns(), true));

    PlannerSettings plannerSettings = PrelUtil.getPlannerSettings(cluster);

    RelNode deletedDataAndDeleteFilesTableFunction =
        new UnionAllPrel(
            cluster,
            deleteFileAggrPlan.getTraitSet(),
            ImmutableList.of(
                fixRoundRobinTraits(deletedDataFilesTableFunctionPrel, plannerSettings),
                fixRoundRobinTraits(deletedDeleteFilesTableFunctionPrel, plannerSettings)),
            true);

    final RelTraitSet traits = traitSet.plus(DistributionTrait.SINGLETON).plus(Prel.PHYSICAL);

    // Union the updating of the deleted data's metadata with the rest
    return getSingletonUnionPrel(
        plannerSettings, traits, manifestWriterPlan, deletedDataAndDeleteFilesTableFunction);
  }

  /**
   * @param deleteFileScan DataFileScan table function Prel created by scanning positional delete
   *     files
   * @return HashAggregate of input on File path with COUNT aggregation on delete positions
   */
  public static Prel aggregateDeleteFiles(RelNode deleteFileScan) {
    RelDataTypeField filePathField =
        Preconditions.checkNotNull(
            deleteFileScan.getRowType().getField(DELETE_FILE_PATH, false, false));
    RelDataTypeField implicitSequenceNumberField =
        Preconditions.checkNotNull(
            deleteFileScan.getRowType().getField(IMPLICIT_SEQUENCE_NUMBER, false, false));

    AggregateCall aggPosCount =
        AggregateCall.create(
            SqlStdOperatorTable.COUNT,
            false,
            false,
            Collections.emptyList(),
            -1,
            RelCollations.EMPTY,
            1,
            deleteFileScan,
            deleteFileScan.getCluster().getTypeFactory().createSqlType(SqlTypeName.BIGINT),
            POS);
    AggregateCall aggSeqNumberMax =
        AggregateCall.create(
            SqlStdOperatorTable.MAX,
            false,
            ImmutableList.of(implicitSequenceNumberField.getIndex()),
            -1,
            implicitSequenceNumberField.getType(),
            IMPLICIT_SEQUENCE_NUMBER);

    ImmutableBitSet groupSet = ImmutableBitSet.of(filePathField.getIndex());
    try {
      return HashAggPrel.create(
          deleteFileScan.getCluster(),
          deleteFileScan.getTraitSet(),
          deleteFileScan,
          groupSet,
          ImmutableList.of(aggPosCount, aggSeqNumberMax),
          null);
    } catch (InvalidRelException e) {
      throw new RuntimeException("Failed to create HashAggPrel during delete file scan.", e);
    }
  }

  /** [*B*] from {@link #deleteAwareOptimizePlan} */
  protected RelNode buildRemoveSideDeleteFilePlan() {
    return planBuilder.buildManifestRel(
        new ImmutableManifestScanOptions.Builder()
            .setIncludesSplitGen(false)
            .setIncludesIcebergMetadata(true)
            .setManifestContentType(ManifestContentType.DELETES)
            .build(),
        false);
  }

  protected RelNode buildRemoveSideDataFilePlanForEqualityDeletes() {
    return planBuilder.buildManifestRel(
        new ImmutableManifestScanOptions.Builder()
            .setIncludesSplitGen(false)
            .setIncludesIcebergMetadata(true)
            .setManifestContentType(ManifestContentType.DATA)
            .build(),
        false);
  }

  /**
   * rewritten_data_files_count=[CASE(=(OperationType, 0), 1, null)],
   * new_data_files_count=[CASE(=(OperationType, 1), 1, null)
   */
  protected Prel getOutputSummaryPlan(Prel writerPrel) throws InvalidRelException {
    // Initializations and literal for projected conditions.
    RexBuilder rexBuilder = cluster.getRexBuilder();
    RelOptCluster cluster = writerPrel.getCluster();
    RelTraitSet traitSet = writerPrel.getTraitSet();
    RelDataTypeFactory typeFactory = cluster.getTypeFactory();
    RelDataType nullableBigInt =
        typeFactory.createTypeWithNullability(typeFactory.createSqlType(BIGINT), true);

    Function<Integer, RexNode> makeLiteral =
        i -> rexBuilder.makeLiteral(i, typeFactory.createSqlType(INTEGER), false);
    RelDataTypeField opTypeField =
        writerPrel.getRowType().getField(OPERATION_TYPE_COLUMN, false, false);
    RexInputRef opTypeIn = rexBuilder.makeInputRef(opTypeField.getType(), opTypeField.getIndex());
    RelDataTypeField recordsField = writerPrel.getRowType().getField(RECORDS_COLUMN, false, false);
    RexNode recordsIn =
        rexBuilder.makeCast(
            nullableBigInt,
            rexBuilder.makeInputRef(recordsField.getType(), recordsField.getIndex()));

    // Projected conditions
    RexNode deletedFileOp =
        rexBuilder.makeCall(
            EQUALS, opTypeIn, makeLiteral.apply(OperationType.DELETE_DATAFILE.value));
    RexNode removedDeleteFileOp =
        rexBuilder.makeCall(
            EQUALS, opTypeIn, makeLiteral.apply(OperationType.DELETE_DELETEFILE.value));
    RexNode newFileOp =
        rexBuilder.makeCall(EQUALS, opTypeIn, makeLiteral.apply(OperationType.ADD_DATAFILE.value));
    RexNode flagRewrittenFile =
        rexBuilder.makeCall(
            CASE, deletedFileOp, recordsIn, rexBuilder.makeZeroLiteral(nullableBigInt));
    RexNode flagRewrittenDeleteFile =
        rexBuilder.makeCall(
            CASE, removedDeleteFileOp, recordsIn, rexBuilder.makeZeroLiteral(nullableBigInt));
    RexNode flagNewFile =
        rexBuilder.makeCall(CASE, newFileOp, recordsIn, rexBuilder.makeZeroLiteral(nullableBigInt));

    // Projected new/written data files
    List<RexNode> projectExpression =
        ImmutableList.of(flagRewrittenFile, flagRewrittenDeleteFile, flagNewFile);
    RelDataType projectedRowType =
        typeFactory
            .builder()
            .add(REWRITTEN_DATA_FILE_COUNT, nullableBigInt)
            .add(REWRITTEN_DELETE_FILE_COUNT, nullableBigInt)
            .add(NEW_DATA_FILES_COUNT, nullableBigInt)
            .build();
    ProjectPrel project =
        ProjectPrel.create(cluster, traitSet, writerPrel, projectExpression, projectedRowType);

    // Aggregated summary
    AggregateCall totalRewrittenFiles = sum(project, projectedRowType, REWRITTEN_DATA_FILE_COUNT);
    AggregateCall totalRewrittenDeleteFiles =
        sum(project, projectedRowType, REWRITTEN_DELETE_FILE_COUNT);
    AggregateCall totalNewFiles = sum(project, projectedRowType, NEW_DATA_FILES_COUNT);
    StreamAggPrel aggregatedCounts =
        StreamAggPrel.create(
            cluster,
            traitSet,
            project,
            ImmutableBitSet.of(),
            ImmutableList.of(totalRewrittenFiles, totalRewrittenDeleteFiles, totalNewFiles),
            null);

    return aggregatedCounts;
  }

  private AggregateCall sum(Prel relNode, RelDataType projectRowType, String fieldName) {
    RelDataTypeField recordsField = projectRowType.getField(fieldName, false, false);
    return AggregateCall.create(
        SUM,
        false,
        false,
        ImmutableList.of(recordsField.getIndex()),
        -1,
        RelCollations.EMPTY,
        1,
        relNode,
        null,
        recordsField.getName());
  }

  protected Prel getOptimizeManifestTableFunctionPrel(Prel input, BatchSchema outputSchema) {
    TableFunctionContext functionContext =
        new OptimizeManifestsTableFunctionContext(
            tableMetadata,
            outputSchema,
            createTableEntry.getIcebergTableProps(),
            createTableEntry.getUserId());

    TableFunctionConfig functionConfig =
        new TableFunctionConfig(
            TableFunctionConfig.FunctionType.ICEBERG_OPTIMIZE_MANIFESTS, true, functionContext);
    return new TableFunctionPrel(
        cluster, traitSet, table, input, tableMetadata, functionConfig, input.getRowType());
  }
}
