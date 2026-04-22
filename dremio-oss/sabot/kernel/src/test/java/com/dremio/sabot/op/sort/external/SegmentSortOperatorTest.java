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
package com.dremio.sabot.op.sort.external;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;

import com.dremio.common.AutoCloseables;
import com.dremio.common.expression.CompleteType;
import com.dremio.common.expression.FieldReference;
import com.dremio.common.expression.SchemaPath;
import com.dremio.common.logical.data.Order.Ordering;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.physical.base.OpProps;
import com.dremio.exec.physical.config.ExternalSort;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.RecordBatchData;
import com.dremio.exec.record.SchemaBuilder;
import com.dremio.exec.record.VectorAccessible;
import com.dremio.exec.record.VectorContainer;
import com.dremio.exec.store.SystemSchemas;
import com.dremio.options.OptionValue;
import com.dremio.sabot.BaseTestOperator;
import com.dremio.sabot.Generator;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.calcite.rel.RelFieldCollation.Direction;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class SegmentSortOperatorTest extends BaseTestOperator {

  @BeforeClass
  public static void setUp() throws Exception {
    testContext
        .getOptions()
        .setOption(
            OptionValue.createBoolean(
                OptionValue.OptionType.SYSTEM,
                ExecConstants.EXTERNAL_SORT_ENABLE_SEGMENT_SORT.getOptionName(),
                true));
  }

  @AfterClass
  public static void cleanup() throws Exception {
    testContext
        .getOptions()
        .setOption(
            OptionValue.createBoolean(
                OptionValue.OptionType.SYSTEM,
                ExecConstants.EXTERNAL_SORT_ENABLE_SEGMENT_SORT.getOptionName(),
                ExecConstants.EXTERNAL_SORT_ENABLE_SEGMENT_SORT.getDefault().getBoolVal()));
  }

  @Test
  public void testSingleSegment() throws Exception {
    testSegmentSort(ImmutableList.of(10), 4000, 4000);
  }

  @Test
  public void testSingleElementSegments() throws Exception {
    testSegmentSort(ImmutableList.of(1, 1, 1), 4000, 4000);
  }

  @Test
  public void testMultipleSegmentsInSingleInputBatch() throws Exception {
    testSegmentSort(ImmutableList.of(3, 5, 2), 4000, 4000);
  }

  @Test
  public void testMultipleSegmentsInMultipleInputBatches() throws Exception {
    testSegmentSort(ImmutableList.of(3, 5, 2), 2, 4000);
  }

  @Test
  public void testSingleSegmentSpill() throws Exception {
    testSegmentSort(ImmutableList.of(20_0000), 4000, 4000, 100L, false);
  }

  private void testSegmentSort(
      List<Integer> segmentRowCounts, int inputBatchSize, int outputBatchSize) throws Exception {
    testSegmentSort(segmentRowCounts, inputBatchSize, outputBatchSize, null, true);
  }

  private void testSegmentSort(
      List<Integer> segmentRowCounts,
      int inputBatchSize,
      int outputBatchSize,
      Long newReserve,
      boolean verifyResults)
      throws Exception {
    List<RecordBatchData> output = null;
    try (Generator generator = new SegmentSortInputGenerator(segmentRowCounts, getTestAllocator());
        AutoCloseable option = with(ExecConstants.EXTERNAL_SORT_ENABLE_SPLAY_SORT, false)) {
      OpProps props = PROPS.cloneWithNewBatchSize(outputBatchSize);
      if (newReserve != null) {
        props = props.cloneWithNewReserve(newReserve);
      }
      ExternalSort sort =
          new ExternalSort(
              props,
              null,
              singletonList(
                  new Ordering(
                      Direction.DESCENDING,
                      new FieldReference(SegmentSortInputGenerator.INT_FIELD_NAME))),
              false);
      sort.getProps().setMemLimit(2_000_000); // this can't go below sort's initialAllocation (20K)
      output =
          getSingleBatchOutput(
              sort, ExternalSortOperator.class, generator, inputBatchSize, outputBatchSize);

      if (!verifyResults) {
        return;
      }
      assertThat(output.size()).isEqualTo(segmentRowCounts.size());

      int totalRowIndex = 0;
      for (int segmentId = 0; segmentId < segmentRowCounts.size(); segmentId++) {
        int segmentRowCount = segmentRowCounts.get(segmentId);
        RecordBatchData segmentSortedData = output.get(segmentId);
        assertThat(segmentSortedData.getRecordCount()).isEqualTo(segmentRowCount);

        IntVector intVector =
            segmentSortedData
                .getVectorAccessible()
                .getValueAccessorById(
                    IntVector.class,
                    segmentSortedData
                        .getVectorAccessible()
                        .getValueVectorId(new SchemaPath(SegmentSortInputGenerator.INT_FIELD_NAME))
                        .getFieldIds())
                .getValueVector();

        for (int segmentRowIndex = 0; segmentRowIndex < segmentRowCount; segmentRowIndex++) {
          // segment data was sorted descendingly
          assertThat(intVector.get(segmentRowIndex))
              .isEqualTo(totalRowIndex + segmentRowCount - segmentRowIndex);
        }
        totalRowIndex += segmentRowCount;
      }

    } finally {
      if (output != null) {
        for (RecordBatchData batch : output) {
          batch.close();
        }
      }
    }
  }

  private static class SegmentSortInputGenerator implements Generator {

    public static String INT_FIELD_NAME = "IntField";
    private final VectorContainer container;
    private final List<org.apache.calcite.util.Pair<Integer, Integer>> values = new ArrayList<>();

    private int rowCount;
    private int position = 0;

    private final Field intField = CompleteType.INT.toField(INT_FIELD_NAME);
    private final Field segmentChangeField =
        CompleteType.BIT.toField(SystemSchemas.SEGMENT_COMPARATOR_FIELD);
    private final IntVector intFieldVector;
    private final BitVector segmentChangeVector;
    private final List<Integer> segmentRowCounts;

    public SegmentSortInputGenerator(List<Integer> segmentRowCounts, BufferAllocator allocator) {
      this.segmentRowCounts = segmentRowCounts;

      SchemaBuilder schemaBuilder = BatchSchema.newBuilder();
      schemaBuilder.addField(intField);
      schemaBuilder.addField(segmentChangeField);
      BatchSchema schema = schemaBuilder.build();

      container = VectorContainer.create(allocator, schema);
      for (Field field : schema.getFields()) {
        container.addOrGet(field);
      }
      segmentChangeVector = container.addOrGet(segmentChangeField);
      intFieldVector = container.addOrGet(intField);

      generateValues();
    }

    private void generateValues() {
      int rowIndex = 1;
      for (int segmentRowCount : segmentRowCounts) {
        Preconditions.checkState(segmentRowCount > 0);

        List<Integer> segmentRows =
            IntStream.rangeClosed(rowIndex, rowIndex + segmentRowCount - 1)
                .boxed()
                .collect(Collectors.toList());
        Collections.shuffle(segmentRows);
        // first segment does not have changing value bit
        boolean hasChangingValue = !(values.size() == 0);
        values.add(
            new org.apache.calcite.util.Pair<>(segmentRows.get(0), hasChangingValue ? 1 : 0));
        for (int segmentRowIndex = 1; segmentRowIndex < segmentRowCount; segmentRowIndex++) {
          values.add(new org.apache.calcite.util.Pair<>(segmentRows.get(segmentRowIndex), 0));
        }
        rowIndex += segmentRowCount;
      }
      rowCount = rowIndex - 1;
    }

    @Override
    public VectorAccessible getOutput() {
      return container;
    }

    public BatchSchema getSchema() {
      return container.getSchema();
    }

    @Override
    public int next(int records) {
      if (position == rowCount) {
        return 0; // no more data available
      }

      int returned = Math.min(records, rowCount - position);
      container.allocateNew();
      for (int i = 0; i < returned; i++) {
        intFieldVector.setSafe(i, values.get(position).left);
        segmentChangeVector.setSafe(i, values.get(position++).right);
      }
      container.setAllCount(returned);

      return returned;
    }

    @Override
    public void close() throws Exception {
      AutoCloseables.close(container);
    }
  }
}
