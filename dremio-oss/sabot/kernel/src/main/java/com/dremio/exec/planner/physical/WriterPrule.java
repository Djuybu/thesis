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
package com.dremio.exec.planner.physical;

import static com.dremio.exec.ExecConstants.ENABLE_ICEBERG_SINGLE_MANIFEST_WRITER;
import static com.dremio.exec.planner.physical.PlannerSettings.CTAS_BALANCE;
import static com.dremio.exec.planner.physical.PlannerSettings.CTAS_ROUND_ROBIN;

import com.dremio.common.exceptions.UserException;
import com.dremio.common.expression.SchemaPath;
import com.dremio.common.util.Numbers;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.catalog.SupportsFsMutablePlugin;
import com.dremio.exec.physical.base.IcebergWriterOptions;
import com.dremio.exec.physical.base.ImmutableIcebergWriterOptions;
import com.dremio.exec.physical.base.ImmutableTableFormatWriterOptions;
import com.dremio.exec.physical.base.TableFormatWriterOptions;
import com.dremio.exec.physical.base.WriterOptions;
import com.dremio.exec.planner.common.MoreRelOptUtil;
import com.dremio.exec.planner.logical.CreateTableEntry;
import com.dremio.exec.planner.logical.IncrementalRefreshByPartitionWriterRel;
import com.dremio.exec.planner.logical.Rel;
import com.dremio.exec.planner.logical.RelOptHelper;
import com.dremio.exec.planner.logical.WriterRel;
import com.dremio.exec.planner.physical.AggregatePrel.OperatorPhase;
import com.dremio.exec.planner.physical.DistributionTrait.DistributionField;
import com.dremio.exec.planner.physical.HashPrelUtil.HashExpressionCreatorHelper;
import com.dremio.exec.planner.physical.HashPrelUtil.RexNodeBasedHashExpressionCreatorHelper;
import com.dremio.exec.planner.physical.visitor.WriterUpdater;
import com.dremio.exec.planner.sql.parser.DmlUtils;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.SchemaBuilder;
import com.dremio.exec.store.RecordWriter;
import com.dremio.exec.store.SystemSchemas;
import com.dremio.exec.store.dfs.IcebergTableProps;
import com.dremio.exec.store.dfs.copyinto.SystemIcebergTablePluginAwareCreateTableEntry;
import com.dremio.exec.store.iceberg.IcebergManifestScanPrel;
import com.dremio.exec.store.iceberg.IcebergManifestWriterPrel;
import com.dremio.exec.store.iceberg.IcebergScanPrel;
import com.dremio.exec.store.iceberg.ManifestContentType;
import com.dremio.exec.store.iceberg.SchemaConverter;
import com.dremio.exec.store.iceberg.SupportsIcebergMutablePlugin;
import com.dremio.exec.store.iceberg.model.IcebergCommandType;
import com.dremio.exec.store.iceberg.model.ImmutableManifestScanOptions;
import com.dremio.io.file.Path;
import com.dremio.options.OptionResolver;
import com.dremio.service.namespace.dataset.proto.DatasetConfig;
import com.google.common.collect.ImmutableList;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptRuleOperand;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.InvalidRelException;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rel.type.RelDataTypeFieldImpl;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.PartitionSpec;

public class WriterPrule extends Prule {

  public static final RelOptRule INSTANCE = new WriterPrule();

  public WriterPrule() {
    super(
        RelOptHelper.some(WriterRel.class, Rel.LOGICAL, RelOptHelper.any(RelNode.class)),
        "Prel.WriterPrule");
  }

  protected WriterPrule(RelOptRuleOperand operand, String description) {
    super(operand, description);
  }

  @Override
  public boolean matches(RelOptRuleCall call) {
    WriterRel writerRel = call.rel(0);
    return !(writerRel instanceof IncrementalRefreshByPartitionWriterRel);
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    final WriterRel writer = call.rel(0);
    final RelNode input = call.rel(1);

    OptionResolver optionResolver = PrelUtil.getPlannerSettings(input.getCluster()).getOptions();

    final boolean addRoundRobin = optionResolver.getOption(CTAS_ROUND_ROBIN);
    final RelTraitSet requestedTraits =
        writer
            .getCreateTableEntry()
            .getOptions()
            .inferTraits(input.getTraitSet(), input.getRowType(), addRoundRobin);
    final RelNode convertedInput = convert(input, requestedTraits);

    if (!new WriteTraitPull(call).go(writer, convertedInput)) {
      call.transformTo(convertWriter(writer, convertedInput));
    }
  }

  private class WriteTraitPull extends SubsetTransformer<WriterRel, RuntimeException> {

    public WriteTraitPull(RelOptRuleCall call) {
      super(call);
    }

    @Override
    public RelNode convertChild(WriterRel writer, RelNode rel) throws RuntimeException {
      return convertWriter(writer, rel);
    }
  }

  public static Prel createWriter(
      RelNode relNode,
      RelDataType rowType,
      DatasetConfig datasetConfig,
      CreateTableEntry createTableEntry,
      Function<RelNode, Prel> finalize) {
    final boolean addRoundRobin =
        PrelUtil.getPlannerSettings(relNode.getCluster()).getOptions().getOption(CTAS_ROUND_ROBIN);
    final boolean balanced =
        PrelUtil.getPlannerSettings(relNode.getCluster()).getOptions().getOption(CTAS_BALANCE);
    if (balanced && relNode instanceof IcebergScanPrel) {
      TableFunctionPrel tblFnc =
          (TableFunctionPrel)
              WriterUpdater.getTableFunctionOnPartitionColumns(
                      createTableEntry.getOptions(),
                      (Prel) relNode,
                      (Prel) relNode,
                      new ArrayList<>(createTableEntry.getOptions().getPartitionColumns()),
                      createTableEntry.getOptions().getPartitionSpec())
                  .getInput(0);
      RelNode convertedInput = augmentPhysical((IcebergScanPrel) relNode, tblFnc, createTableEntry);
      return convertWriter(
          relNode, convertedInput, rowType, datasetConfig, createTableEntry, finalize);
    }
    final RelTraitSet requestedTraits =
        createTableEntry.getOptions().inferTraits(relNode.getTraitSet(), rowType, addRoundRobin);
    final RelNode convertedInput = convert(relNode, requestedTraits);

    return convertWriter(
        relNode, convertedInput, rowType, datasetConfig, createTableEntry, finalize);
  }

  private static Prel buildManifestScan(
      IcebergScanPrel scanPrel, PartitionSpec partitionSpec, final long recordCount) {
    SchemaBuilder schemaBuilder =
        BatchSchema.newBuilder().addFields(SystemSchemas.ICEBERG_MANIFEST_SCAN_SCHEMA);
    partitionSpec
        .fields()
        .forEach(
            f ->
                schemaBuilder.addField(
                    Field.nullable(
                        f.name() + "_val",
                        SchemaConverter.fromIcebergPrimitiveType(
                                partitionSpec.partitionType().fieldType(f.name()).asPrimitiveType())
                            .getType())));
    BatchSchema schema =
        schemaBuilder.setSelectionVectorMode(BatchSchema.SelectionVectorMode.NONE).build();
    final List<SchemaPath> columns =
        schema.getFields().stream()
            .map(field -> SchemaPath.getSimplePath(field.getName()))
            .collect(Collectors.toList());

    // No need for split generation as we are only interested
    // in manifest entry partition field values.
    final ImmutableManifestScanOptions options =
        new ImmutableManifestScanOptions.Builder()
            .setIncludesSplitGen(false)
            .setManifestContentType(ManifestContentType.DATA)
            .build();

    return scanPrel.buildManifestScan(recordCount, options, columns, schema);
  }

  private static RelNode augmentPhysical(
      IcebergScanPrel prel, TableFunctionPrel tableFunctionPrel, CreateTableEntry entry) {
    try {
      final int numEndPoints = PrelUtil.getSettings(prel.getCluster()).numEndPoints();
      final long maxWidthPerNode = PrelUtil.getSettings(prel.getCluster()).getMaxWidthPerNode();
      final long targetFileSize =
          Optional.ofNullable(entry.getOptions().getTableFormatOptions().getTargetFileSize())
              .orElse(
                  PrelUtil.getSettings(prel.getCluster())
                      .getOptions()
                      .getOption(ExecConstants.PARQUET_BLOCK_SIZE_VALIDATOR));
      final int buckets = Numbers.nextPowerOfTwo((int) maxWidthPerNode * numEndPoints);
      Prel manifestPrel =
          buildManifestScan(
              prel.withParititionValuesEnabled(),
              entry.getOptions().getPartitionSpec(),
              prel.getSurvivingFileCount());
      while (!(manifestPrel instanceof IcebergManifestScanPrel)) {
        manifestPrel = (Prel) manifestPrel.getInput(0);
      }
      final Prel manifestScan = manifestPrel;
      List<Integer> groupColumns =
          entry.getOptions().getPartitionSpec().fields().stream()
              .map(PartitionField::name)
              .map(c -> c + "_val")
              .map(c -> manifestScan.getRowType().getField(c, false, false).getIndex())
              .collect(Collectors.toList());

      int sizeIndex = manifestScan.getRowType().getField("fileSize", false, false).getIndex();

      HashAggPrel hashAgg =
          HashAggPrel.create(
              prel.getCluster(),
              prel.getTraitSet(),
              manifestScan,
              ImmutableBitSet.of(groupColumns),
              ImmutableList.of(
                  AggregateCall.create(
                      SqlStdOperatorTable.SUM,
                      false,
                      false,
                      false,
                      ImmutableList.of(sizeIndex),
                      -1,
                      RelCollations.EMPTY,
                      groupColumns.size(),
                      manifestScan,
                      null,
                      null)),
              OperatorPhase.PHASE_1of2);

      UnionExchangePrel exchangePrel =
          new UnionExchangePrel(
              hashAgg.getCluster(),
              hashAgg.getTraitSet().replace(DistributionTrait.SINGLETON),
              hashAgg);

      HashAggPrel hashAgg2 =
          HashAggPrel.create(
              prel.getCluster(),
              prel.getTraitSet(),
              exchangePrel,
              ImmutableBitSet.range(groupColumns.size()),
              ImmutableList.of(
                  AggregateCall.create(
                      SqlStdOperatorTable.SUM,
                      false,
                      false,
                      false,
                      ImmutableList.of(groupColumns.size()),
                      -1,
                      RelCollations.EMPTY,
                      groupColumns.size(),
                      exchangePrel,
                      null,
                      null)),
              OperatorPhase.PHASE_2of2);
      RexBuilder rexBuilder = hashAgg2.getCluster().getRexBuilder();
      RelDataTypeFactory typeFactory = hashAgg2.getCluster().getTypeFactory();

      RexNode bucketExpr =
          rexBuilder.makeCast(
              typeFactory.createSqlType(SqlTypeName.INTEGER),
              rexBuilder.makeCall(
                  SqlStdOperatorTable.CEIL,
                  rexBuilder.makeCall(
                      SqlStdOperatorTable.MULTIPLY,
                      rexBuilder.makeApproxLiteral(BigDecimal.valueOf(1.15)),
                      rexBuilder.makeCall(
                          SqlStdOperatorTable.DIVIDE,
                          rexBuilder.makeInputRef(
                              hashAgg2, hashAgg2.getRowType().getFieldCount() - 1),
                          rexBuilder.makeApproxLiteral(BigDecimal.valueOf(targetFileSize))))));

      RelDataType bucketProjectType =
          typeFactory.createStructType(
              ImmutableList.<RelDataTypeField>builder()
                  .addAll(hashAgg2.getRowType().getFieldList())
                  .add(
                      new RelDataTypeFieldImpl(
                          "buckets", hashAgg2.getRowType().getFieldCount(), bucketExpr.getType()))
                  .build());

      ProjectPrel bucketProject =
          ProjectPrel.create(
              hashAgg2.getCluster(),
              hashAgg2.getTraitSet(),
              hashAgg2,
              ImmutableList.<RexNode>builder()
                  .addAll(MoreRelOptUtil.identityProjects(hashAgg2.getRowType()))
                  .add(bucketExpr)
                  .build(),
              bucketProjectType);

      FilterPrel filter =
          FilterPrel.create(
              bucketProject.getCluster(),
              bucketProject.getTraitSet(),
              bucketProject,
              rexBuilder.makeCall(
                  SqlStdOperatorTable.GREATER_THAN,
                  rexBuilder.makeInputRef(
                      bucketProject, bucketProject.getRowType().getFieldCount() - 1),
                  rexBuilder.makeBigintLiteral(BigDecimal.ONE)));

      TopNPrel sort =
          new TopNPrel(
              filter.getCluster(),
              filter.getTraitSet(),
              filter,
              buckets,
              RelCollations.of(filter.getRowType().getFieldCount() - 1));

      BroadcastExchangePrel broadcast =
          new BroadcastExchangePrel(
              sort.getCluster(), sort.getTraitSet().replace(DistributionTrait.BROADCAST), sort);

      List<Integer> joinColumns =
          entry.getOptions().getPartitionSpec().fields().stream()
              .map(PartitionField::name)
              .map(c -> tableFunctionPrel.getRowType().getField(c, false, false).getIndex())
              .collect(Collectors.toList());
      RexNode condition =
          MoreRelOptUtil.createEquiJoinConditionINDF(
              tableFunctionPrel,
              joinColumns,
              broadcast,
              ImmutableBitSet.range(groupColumns.size()).asList(),
              rexBuilder);
      HashJoinPrel join =
          HashJoinPrel.create(
              broadcast.getCluster(),
              tableFunctionPrel.getTraitSet(),
              tableFunctionPrel,
              broadcast,
              condition,
              null,
              JoinRelType.LEFT,
              true);

      int bucketsIdx = join.getRowType().getFieldCount() - 1;
      final HashExpressionCreatorHelper<RexNode> hashHelper =
          new RexNodeBasedHashExpressionCreatorHelper(rexBuilder);
      List<RexNode> hashKeys =
          joinColumns.stream()
              .map(c -> rexBuilder.makeInputRef(join, c))
              .collect(Collectors.toList());
      List<RexNode> hashKeysPlusSeed =
          ImmutableList.<RexNode>builder()
              .addAll(hashKeys)
              .add(
                  rexBuilder.makeCast(
                      typeFactory.createSqlType(SqlTypeName.INTEGER),
                      rexBuilder.makeCall(
                          SqlStdOperatorTable.MULTIPLY,
                          rexBuilder.makeCall(SqlStdOperatorTable.RAND),
                          rexBuilder.makeCall(
                              SqlStdOperatorTable.CASE,
                              rexBuilder.makeCall(
                                  SqlStdOperatorTable.IS_NOT_NULL,
                                  rexBuilder.makeInputRef(join, bucketsIdx)),
                              rexBuilder.makeInputRef(join, bucketsIdx),
                              rexBuilder.makeBigintLiteral(BigDecimal.ZERO)))))
              .build();
      RexNode hashExpr = HashPrelUtil.createHashExpression(hashKeysPlusSeed, hashHelper);
      List<RexNode> projects =
          ImmutableList.<RexNode>builder()
              .addAll(MoreRelOptUtil.identityProjects(join.getRowType()))
              .add(hashExpr)
              .build();
      RelDataType hashProjectType =
          typeFactory.createStructType(
              ImmutableList.<RelDataTypeField>builder()
                  .addAll(join.getRowType().getFieldList())
                  .add(
                      new RelDataTypeFieldImpl(
                          HashPrelUtil.HASH_EXPR_NAME,
                          hashAgg2.getRowType().getFieldCount(),
                          bucketExpr.getType()))
                  .build());
      ProjectPrel project =
          ProjectPrel.create(
              join.getCluster(), join.getTraitSet(), join, projects, hashProjectType);

      HashToRandomExchangePrel finalExchange =
          new HashToRandomExchangePrel(
              project.getCluster(),
              project
                  .getTraitSet()
                  .replace(
                      WriterOptions.hashDistributedOn(
                          joinColumns.stream()
                              .map(c -> project.getRowType().getFieldList().get(c).getName())
                              .collect(Collectors.toList()),
                          project.getRowType())),
              project,
              ImmutableList.of(new DistributionField(project.getRowType().getFieldCount() - 1)));

      ProjectPrel finalProject =
          ProjectPrel.create(
              finalExchange.getCluster(),
              finalExchange.getTraitSet(),
              finalExchange,
              MoreRelOptUtil.identityProjects(
                  finalExchange.getRowType(),
                  ImmutableBitSet.range(tableFunctionPrel.getRowType().getFieldCount())),
              MoreRelOptUtil.fieldType(
                  finalExchange.getRowType(),
                  typeFactory,
                  ImmutableBitSet.range(tableFunctionPrel.getRowType().getFieldCount())));
      return finalProject;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  protected Prel convertWriter(WriterRel writer, RelNode rel) {
    return convertWriter(
        writer, rel, writer.getExpectedInboundRowType(), null, writer.getCreateTableEntry(), null);
  }

  protected static Prel convertWriter(
      RelNode writer,
      RelNode rel,
      RelDataType rowType,
      DatasetConfig datasetConfig,
      CreateTableEntry createTableEntry,
      Function<RelNode, Prel> finalize) {
    OptionResolver optionResolver = PrelUtil.getPlannerSettings(rel.getCluster()).options;

    DistributionTrait childDist = rel.getTraitSet().getTrait(DistributionTraitDef.INSTANCE);
    final RelTraitSet traits =
        writer.getTraitSet().plus(DistributionTrait.SINGLETON).plus(Prel.PHYSICAL);

    // first, resolve our children.
    final boolean isSingleWriter = createTableEntry.getOptions().isSingleWriter();
    final String finalPath = createTableEntry.getLocation();
    final String userName = createTableEntry.getUserName();
    final Path finalStructuredPath = Path.of(finalPath);
    final SupportsFsMutablePlugin plugin = createTableEntry.getPlugin();

    // Create the Writer with the child's distribution because the degree of parallelism for the
    // writer
    // should correspond to the number of child minor fragments. The Writer itself is not concerned
    // with
    // the collation of the child.  Note that the Writer's output RowType consists of
    // {fragment_id varchar(255), number_of_records_written bigint} which are very different from
    // the
    // child's output RowType.
    RelTraitSet writerTraitSet =
        writer
            .getTraitSet()
            .plus(isSingleWriter ? DistributionTrait.SINGLETON : childDist)
            .plus(Prel.PHYSICAL);
    RelNode writerInput = isSingleWriter ? convert(rel, traits) : rel;
    String tempPath =
        optionResolver.getOption(PlannerSettings.WRITER_TEMP_FILE)
            ? generateTempPath(finalStructuredPath).toString()
            : null;
    CreateTableEntry createTableEntryWithTempPathIfNeeded =
        optionResolver.getOption(PlannerSettings.WRITER_TEMP_FILE)
            ? createTableEntry.cloneWithNewLocation(tempPath)
            : createTableEntry;

    final WriterPrel child =
        new WriterPrel(
            writer.getCluster(),
            writerTraitSet,
            writerInput,
            createTableEntryWithTempPathIfNeeded,
            rowType);

    final RelNode newChild =
        withManifestWriterPrelIfNeeded(child, traits, writer, createTableEntry, childDist);
    WriterCommitterPrel writerCommitterPrel =
        createWriterCommitterPrel(
            writer.getCluster(),
            traits,
            finalize != null ? finalize.apply(newChild) : newChild,
            plugin,
            tempPath,
            finalPath,
            userName,
            createTableEntry,
            datasetConfig);
    return withInsertRowCountPlanIfNeeded(writerCommitterPrel, createTableEntry);
  }

  private static WriterCommitterPrel createWriterCommitterPrel(
      RelOptCluster cluster,
      RelTraitSet traits,
      RelNode newChild,
      SupportsFsMutablePlugin plugin,
      String tempPath,
      String finalPath,
      String userName,
      CreateTableEntry fileEntry,
      DatasetConfig datasetConfig) {
    if (fileEntry instanceof SystemIcebergTablePluginAwareCreateTableEntry
        && ((SystemIcebergTablePluginAwareCreateTableEntry) fileEntry)
                .getSystemIcebergTablesPlugin()
            != null) {
      return new CopyIntoHistoryWriterCommitterPrel(
          cluster,
          traits,
          newChild,
          (SupportsIcebergMutablePlugin) plugin,
          tempPath,
          finalPath,
          userName,
          fileEntry,
          Optional.ofNullable(datasetConfig),
          false,
          false,
          ((SystemIcebergTablePluginAwareCreateTableEntry) fileEntry)
              .getSystemIcebergTablesPlugin());
    }
    return new WriterCommitterPrel(
        cluster,
        traits,
        newChild,
        plugin,
        tempPath,
        finalPath,
        userName,
        fileEntry,
        Optional.ofNullable(datasetConfig),
        false,
        false,
        null);
  }

  /***
   * This function is to get Iceberg DML Flow with ManifestWriter. This writer is responsible to consume data files output from ParquetWriter
   * create ManifestFile out of it and then send to Writer Committer Operator which will commit to iceberg table.
   */
  public static RelNode withManifestWriterPrelIfNeeded(
      RelNode child,
      RelTraitSet oldTraits,
      RelNode writer,
      CreateTableEntry fileEntry,
      DistributionTrait childDist) {
    // For OPTIMIZE TABLE command IcebergManifestWriterPrel is not required.
    if (fileEntry.getIcebergTableProps() == null
        || fileEntry.getIcebergTableProps().getIcebergOpType() == IcebergCommandType.OPTIMIZE) {
      return convert(child, oldTraits);
    }

    boolean isSingleWriter =
        PrelUtil.getPlannerSettings(writer.getCluster())
            .getOptions()
            .getOption(ENABLE_ICEBERG_SINGLE_MANIFEST_WRITER);
    RelNode newChild;
    if (isSingleWriter) {
      final RelTraitSet singletonTraitSet =
          child.getTraitSet().plus(DistributionTrait.SINGLETON).plus(Prel.PHYSICAL);
      newChild = new UnionExchangePrel(writer.getCluster(), singletonTraitSet, child);
    } else {
      DistributionTrait.DistributionField distributionField =
          new DistributionTrait.DistributionField(
              RecordWriter.SCHEMA.getFields().indexOf(RecordWriter.ICEBERG_METADATA));
      DistributionTrait distributionTrait =
          new DistributionTrait(
              DistributionTrait.DistributionType.HASH_DISTRIBUTED,
              ImmutableList.of(distributionField));
      final RelTraitSet newTraits =
          writer.getTraitSet().plus(distributionTrait).plus(Prel.PHYSICAL);

      newChild =
          new HashToRandomExchangePrel(
              child.getCluster(),
              newTraits,
              child,
              distributionTrait.getFields(),
              HashPrelUtil.DATA_FILE_DISTRIBUTE_HASH_FUNCTION_NAME,
              null);
    }

    CreateTableEntry icebergCreateTableEntry =
        withCreateTableEntryForManifestWriter(fileEntry, fileEntry.getIcebergTableProps());
    final WriterPrel manifestWriterPrel =
        new IcebergManifestWriterPrel(
            child.getCluster(),
            writer.getTraitSet().plus(childDist).plus(Prel.PHYSICAL),
            newChild,
            icebergCreateTableEntry,
            isSingleWriter);
    return convert(manifestWriterPrel, oldTraits);
  }

  private static Prel withInsertRowCountPlanIfNeeded(
      Prel relNode, CreateTableEntry createTableEntry) {
    // if not insert op return original plan
    if (!DmlUtils.isInsertOperation(createTableEntry)) {
      return relNode;
    }

    boolean isCopyIntoHistory = relNode instanceof CopyIntoHistoryWriterCommitterPrel;

    // Return as plan for insert command only with records columns. Same is not applicable in case
    // of incremental reflections refresh
    RelDataTypeField recordsField =
        relNode.getRowType().getField(RecordWriter.RECORDS.getName(), false, false);

    List<AggregateCall> aggregateCalls = new ArrayList<>();
    aggregateCalls.add(
        AggregateCall.create(
            SqlStdOperatorTable.SUM,
            false,
            false,
            ImmutableList.of(recordsField.getIndex()),
            -1,
            0,
            relNode,
            null,
            RecordWriter.RECORDS.getName()));

    if (isCopyIntoHistory) {
      RelDataTypeField rejectedRecordsField =
          relNode.getRowType().getField(RecordWriter.REJECTED_RECORDS.getName(), false, false);
      AggregateCall aggRejectedRecordCount =
          AggregateCall.create(
              SqlStdOperatorTable.SUM,
              false,
              false,
              ImmutableList.of(rejectedRecordsField.getIndex()),
              -1,
              0,
              relNode,
              null,
              RecordWriter.REJECTED_RECORDS.getName());
      aggregateCalls.add(aggRejectedRecordCount);
    }

    try {
      RexBuilder rexBuilder = relNode.getCluster().getRexBuilder();
      StreamAggPrel rowCountAgg =
          StreamAggPrel.create(
              relNode.getCluster(),
              relNode.getTraitSet(),
              relNode,
              ImmutableBitSet.of(),
              aggregateCalls,
              null);
      // Project: return 0 as row count in case there is no Agg record (i.e., no DMLed results)
      recordsField =
          rowCountAgg.getRowType().getField(RecordWriter.RECORDS.getName(), false, false);
      List<String> projectNames = new ArrayList<>();
      projectNames.add(recordsField.getName());
      RexNode zeroLiteral =
          rexBuilder.makeLiteral(
              0,
              rowCountAgg.getCluster().getTypeFactory().createSqlType(SqlTypeName.INTEGER),
              true);
      // check if the count of row count records is 0 (i.e., records column is null)
      RexNode rowCountRecordExistsCheckCondition =
          rexBuilder.makeCall(
              SqlStdOperatorTable.IS_NULL,
              rexBuilder.makeInputRef(recordsField.getType(), recordsField.getIndex()));
      // case when the count of row count records is 0, return 0, else return aggregated row count
      RexNode rowCountProjectExpr =
          rexBuilder.makeCall(
              SqlStdOperatorTable.CASE,
              rowCountRecordExistsCheckCondition,
              zeroLiteral,
              rexBuilder.makeInputRef(recordsField.getType(), recordsField.getIndex()));

      List<RexNode> projectExprs = new ArrayList<>();
      projectExprs.add(rowCountProjectExpr);

      if (isCopyIntoHistory) {
        RelDataTypeField rejectedRecordsField =
            rowCountAgg
                .getRowType()
                .getField(RecordWriter.REJECTED_RECORDS.getName(), false, false);
        projectNames.add(rejectedRecordsField.getName());
        RexNode rowCountRejectedRecordExistsCheckCondition =
            rexBuilder.makeCall(
                SqlStdOperatorTable.IS_NULL,
                rexBuilder.makeInputRef(
                    rejectedRecordsField.getType(), rejectedRecordsField.getIndex()));
        RexNode rejectedRowCountProjectExpr =
            rexBuilder.makeCall(
                SqlStdOperatorTable.CASE,
                rowCountRejectedRecordExistsCheckCondition,
                zeroLiteral,
                rexBuilder.makeInputRef(
                    rejectedRecordsField.getType(), rejectedRecordsField.getIndex()));
        projectExprs.add(rejectedRowCountProjectExpr);
      }

      RelDataType projectRowType =
          RexUtil.createStructType(
              rowCountAgg.getCluster().getTypeFactory(), projectExprs, projectNames, null);
      return ProjectPrel.create(
          rowCountAgg.getCluster(),
          rowCountAgg.getTraitSet(),
          rowCountAgg,
          projectExprs,
          projectRowType);
    } catch (InvalidRelException e) {
      throw UserException.planError(e).buildSilently();
    }
  }

  public static CreateTableEntry withCreateTableEntryForManifestWriter(
      CreateTableEntry fileEntry, IcebergTableProps icebergTableProps) {
    WriterOptions oldOptions = fileEntry.getOptions();
    IcebergWriterOptions icebergOptions =
        new ImmutableIcebergWriterOptions.Builder().setIcebergTableProps(icebergTableProps).build();
    TableFormatWriterOptions tableFormatOptions =
        new ImmutableTableFormatWriterOptions.Builder()
            .setIcebergSpecificOptions(icebergOptions)
            .build();
    WriterOptions manifestWriterOption =
        new WriterOptions(
            null,
            null,
            null,
            null,
            null,
            false,
            oldOptions.getRecordLimit(),
            tableFormatOptions,
            oldOptions.getExtendedProperty(),
            false,
            oldOptions.isMergeOnReadRowSplitterMode());
    // IcebergTableProps is the only obj we need in manifestWriter
    return fileEntry.cloneWithFields(manifestWriterOption);
  }

  public static Path generateTempPath(Path finalStructuredPath) {
    return finalStructuredPath
        .getParent()
        .resolve("." + finalStructuredPath.getName() + "-" + System.currentTimeMillis());
  }
}
