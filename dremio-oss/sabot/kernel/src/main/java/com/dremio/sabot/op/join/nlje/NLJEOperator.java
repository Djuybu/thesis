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
package com.dremio.sabot.op.join.nlje;

import com.dremio.common.AutoCloseables;
import com.dremio.common.exceptions.RowSizeLimitExceptionHelper;
import com.dremio.common.exceptions.RowSizeLimitExceptionHelper.RowSizeLimitExceptionType;
import com.dremio.common.exceptions.UserException;
import com.dremio.common.expression.FunctionCall;
import com.dremio.common.expression.LogicalExpression;
import com.dremio.common.expression.ValueExpressions.BooleanExpression;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.physical.config.NestedLoopJoinPOP;
import com.dremio.exec.planner.physical.NestedLoopJoinPrel;
import com.dremio.exec.record.ExpandableHyperContainer;
import com.dremio.exec.record.RecordBatchData;
import com.dremio.exec.record.VectorAccessible;
import com.dremio.exec.record.VectorContainer;
import com.dremio.exec.record.VectorWrapper;
import com.dremio.sabot.exec.context.MetricDef;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.op.copier.CopierFactory;
import com.dremio.sabot.op.join.vhash.spill.slicer.CombinedSizer;
import com.dremio.sabot.op.spi.DualInputOperator;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.VectorContainerHelper;
import org.apache.arrow.vector.util.TransferPair;
import org.apache.calcite.rel.core.JoinRelType;

/**
 * An enhanced implementation of NLJ that is vectorized and supports evaluating an expression.
 *
 * <p>The algorithm is as follows:
 *
 * <ul>
 *   <li>Collect all the build batches in memory in an ExpandableHyperContainer
 *   <li>Once collected, generate a JoinMatcher. There are two implementations: a generalized one
 *       and one for when we are doing a left join (probe non-matching) with no build input.
 *   <li>For each input probe batch, we generate a two sets of selection vectors. A SV2 for the
 *       probe input and a SV4 for the build input.
 *   <li>For each build batch, we generate the list of outputs. Because this list could be n^2 in
 *       size, we do this a partial probe batch at a time.
 * </ul>
 */
public class NLJEOperator implements DualInputOperator {

  // TODO: remove static map.
  private static final Map<String, String> VECTOR_MAP =
      ImmutableMap.<String, String>builder()
          .put("geo_nearby", "com.dremio.joust.geo.NearbyBeyond")
          .put("geo_beyond", "com.dremio.joust.geo.NearbyBeyond")
          .put("all", "com.dremio.sabot.op.join.nlje.AllVectorFunction")
          .build();
  private static final long INT_SIZE = 4;
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(NLJEOperator.class);

  private final OperatorContext context;
  private final JoinRelType joinType;
  private final NestedLoopJoinPOP config;
  private final int rowSizeLimit;
  private final boolean rowSizeLimitEnabled;
  private State state = State.NEEDS_SETUP;

  private VectorAccessible buildIncoming;

  private VectorAccessible probeIncoming;
  private ExpandableHyperContainer build;
  private VectorContainer output;
  private JoinMatcher joinMatcher;
  private long buildRecords;

  private List<FieldVector> probeInputVectors;
  private List<FieldVector> probeInputVectorsUsed;
  private List<FieldVector> probeOutputVectors;
  private List<FieldVector> buildOutputVectors;
  private List<TransferPair> probeOutputTransfers;
  private CopierFactory copierFactory;
  private ArrowBuf rowSizeAccumulator;
  private int fixedDataLenPerRow;
  private CombinedSizer variableVectorSizer;
  private boolean rowSizeLimitEnabledForThisOperator;

  public NLJEOperator(OperatorContext context, NestedLoopJoinPOP config) {
    this.context = context;
    this.config = config;
    this.joinType = config.getJoinType();
    switch (joinType) {
      case INNER:
      case LEFT:
        break; // supported.
      case FULL:
        LogicalExpression condition = config.getCondition();
        if (condition instanceof BooleanExpression
            && ((BooleanExpression) condition).getBoolean()) {
          // DX-59222 support FOJ with true condition
          break;
        }
        throw UserException.unsupportedError()
            .message("When using NLJ, we only support full outer joins with a 'true' condition.")
            .buildSilently();
      default:
        throw UserException.unsupportedError()
            .message("Joins of type %s using NLJ are not currently supported.", joinType.name())
            .buildSilently();
    }
    this.rowSizeLimitEnabled =
        context.getOptions().getOption(ExecConstants.ENABLE_ROW_SIZE_LIMIT_ENFORCEMENT);
    this.rowSizeLimitEnabledForThisOperator = rowSizeLimitEnabled;
    this.rowSizeLimit =
        Math.toIntExact(context.getOptions().getOption(ExecConstants.LIMIT_ROW_SIZE_BYTES));
  }

  @Override
  public <OUT, IN, EXCEP extends Throwable> OUT accept(
      OperatorVisitor<OUT, IN, EXCEP> visitor, IN value) throws EXCEP {
    return visitor.visitDualInput(this, value);
  }

  @Override
  public void close() throws Exception {
    AutoCloseables.close(build, joinMatcher, output);
    if (rowSizeAccumulator != null) {
      rowSizeAccumulator.close();
      rowSizeAccumulator = null;
    }
  }

  @SuppressWarnings("unchecked")
  @Override
  public VectorAccessible setup(VectorAccessible left, VectorAccessible right) throws Exception {
    this.probeIncoming = left;
    this.buildIncoming = right;
    this.build = new ExpandableHyperContainer(context.getAllocator(), right.getSchema());
    this.output = new VectorContainer();

    List<FieldVector> buildIncomingVectors =
        (List<FieldVector>)
            StreamSupport.stream(buildIncoming.spliterator(), false)
                .map(VectorWrapper::getValueVector)
                .collect(Collectors.toList());
    buildOutputVectors =
        buildIncomingVectors.stream()
            .map(v -> (FieldVector) v.getTransferPair(v.getField(), context.getAllocator()).getTo())
            .collect(Collectors.toList());
    buildOutputVectors.forEach(v -> output.add(v));

    probeInputVectors =
        (List<FieldVector>)
            StreamSupport.stream(probeIncoming.spliterator(), false)
                .map(VectorWrapper::getValueVector)
                .collect(Collectors.toList());
    probeOutputTransfers =
        probeInputVectors.stream()
            .map(v -> v.getTransferPair(v.getField(), context.getAllocator()))
            .collect(Collectors.toList());
    probeOutputVectors =
        probeOutputTransfers.stream()
            .map(v -> (FieldVector) v.getTo())
            .collect(Collectors.toList());
    probeOutputVectors.forEach(v -> output.add(v));

    this.output.buildSchema();
    copierFactory = CopierFactory.getInstance(context.getConfig(), context.getOptions());
    state = State.CAN_CONSUME_R;

    if (rowSizeLimitEnabled) {
      fixedDataLenPerRow = VectorContainerHelper.getFixedDataLenPerRow(output);
      if (!VectorContainerHelper.isVarLenColumnPresent(output)) {
        rowSizeLimitEnabledForThisOperator = false;
        if (fixedDataLenPerRow > rowSizeLimit) {
          throw RowSizeLimitExceptionHelper.createRowSizeLimitException(
              rowSizeLimit, RowSizeLimitExceptionType.PROCESSING, logger);
        }
      } else {
        createNewRowLengthAccumulatorIfRequired(context.getTargetBatchSize());
        this.variableVectorSizer = VectorContainerHelper.createSizer(output, false);
      }
    }
    return output;
  }

  @Override
  public State getState() {
    return state;
  }

  @SuppressWarnings("resource")
  @Override
  public void consumeDataRight(int records) throws Exception {
    final RecordBatchData batchCopy = new RecordBatchData(buildIncoming, context.getAllocator());
    build.addBatch(batchCopy.getVectorAccessible());
    buildRecords += records;
  }

  private DualRange getInitialMatchState() throws Exception {
    final int targetGenerateAtOnce =
        (int) context.getOptions().getOption(NestedLoopJoinPrel.OUTPUT_COUNT);
    VectorWrapper<?> wrapper = build.iterator().next();
    ValueVector[] vectors = wrapper.getValueVectors();
    int[] counts = new int[vectors.length];
    int maxBuildCount = 0;
    for (int i = 0; i < vectors.length; i++) {
      counts[i] = vectors[i].getValueCount();
      maxBuildCount = Math.max(maxBuildCount, counts[i]);
    }
    if (config.getVectorOp() == null) {
      return new IndexRange(targetGenerateAtOnce, counts);
    } else {
      return getVectorRange((FunctionCall) config.getVectorOp(), targetGenerateAtOnce, counts);
    }
  }

  private DualRange getVectorRange(
      FunctionCall expression, int targetGenerateAtOnce, int[] batchCounts) throws Exception {
    String factoryName = VECTOR_MAP.get(expression.getName());
    if (factoryName == null) {
      throw new UnsupportedOperationException("Unknown vector operation " + expression.getName());
    }

    DualRangeFunctionFactory factory;
    try {
      factory =
          (DualRangeFunctionFactory)
              Class.forName(factoryName).getDeclaredConstructor().newInstance();
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      Throwables.throwIfInstanceOf(cause, Exception.class);
      Throwables.throwIfUnchecked(cause);
      throw e;
    }
    return factory.create(
        context.getAllocator(),
        probeIncoming,
        build,
        context.getTargetBatchSize(),
        targetGenerateAtOnce,
        batchCounts,
        expression);
  }

  @SuppressWarnings("unchecked")
  @Override
  public void noMoreToConsumeRight() throws Exception {
    if (buildRecords == 0 && (joinType == JoinRelType.INNER || joinType == JoinRelType.RIGHT)) {
      state = State.DONE;
      return;
    }

    if (buildRecords == 0) {
      this.joinMatcher = new StraightThroughMatcher(output, probeOutputTransfers);
    } else {
      Stopwatch watch = Stopwatch.createStarted();
      List<FieldVector[]> buildInputVectors =
          (List<FieldVector[]>)
              (Object)
                  StreamSupport.stream(build.spliterator(), false)
                      .map(VectorWrapper::getValueVectors)
                      .collect(Collectors.toList());
      final int targetGenerateAtOnce =
          (int) context.getOptions().getOption(NestedLoopJoinPrel.OUTPUT_COUNT);
      this.joinMatcher =
          new EvaluatingJoinMatcher(
              context,
              probeIncoming,
              build,
              targetGenerateAtOnce,
              getInitialMatchState(),
              copierFactory.getTwoByteCopiers(probeInputVectors, probeOutputVectors),
              copierFactory.getFourByteCopiers(buildInputVectors, buildOutputVectors),
              joinType);
      context.getStats().setLongStat(Metric.COMPILE_NANOS, watch.elapsed(TimeUnit.NANOSECONDS));
    }

    joinMatcher.setup(config.getCondition(), context.getClassProducer(), probeIncoming, build);
    state = State.CAN_CONSUME_L;
  }

  @Override
  public int outputData() throws Exception {
    Preconditions.checkArgument(!joinMatcher.needNextInput());

    int records = joinMatcher.output();
    if (joinMatcher.needNextInput()) {
      state = State.CAN_CONSUME_L;
    }
    output.setAllCount(records);

    updateStats();
    checkForRowSizeOverLimit(records);

    return records;
  }

  @Override
  public void consumeDataLeft(int records) throws Exception {

    joinMatcher.startNextProbe(records);

    if (!joinMatcher.needNextInput()) {
      // if we can produce output, we don't want to consume another batch of records.
      state = State.CAN_PRODUCE;
    }

    updateStats();
  }

  private void updateStats() {
    context.getStats().setLongStat(Metric.MATCH_NANOS, joinMatcher.getMatchNanos());
    context.getStats().setLongStat(Metric.COPY_NANOS, joinMatcher.getCopyNanos());
    context.getStats().setLongStat(Metric.PROBE_COUNT, joinMatcher.getProbeCount());
  }

  @Override
  public void noMoreToConsumeLeft() throws Exception {
    // TODO: add right/outer support.
    state = State.DONE;
  }

  private void createNewRowLengthAccumulatorIfRequired(int batchSize) {
    if (rowSizeAccumulator != null) {
      if (rowSizeAccumulator.capacity() < (long) batchSize * INT_SIZE) {
        rowSizeAccumulator.close();
        rowSizeAccumulator = null;
      } else {
        return;
      }
    }
    rowSizeAccumulator = context.getAllocator().buffer((long) batchSize * INT_SIZE);
  }

  private void checkForRowSizeOverLimit(int recordCount) {
    if (!rowSizeLimitEnabledForThisOperator || recordCount <= 0) {
      return;
    }
    createNewRowLengthAccumulatorIfRequired(recordCount);
    VectorContainerHelper.checkForRowSizeOverLimit(
        output,
        recordCount,
        rowSizeLimit - fixedDataLenPerRow,
        rowSizeLimit,
        rowSizeAccumulator,
        variableVectorSizer,
        RowSizeLimitExceptionType.PROCESSING,
        logger);
  }

  public enum Metric implements MetricDef {
    MATCH_NANOS,
    COPY_NANOS,
    COMPILE_NANOS,
    PROBE_COUNT;

    @Override
    public int metricId() {
      return ordinal();
    }
  }
}
