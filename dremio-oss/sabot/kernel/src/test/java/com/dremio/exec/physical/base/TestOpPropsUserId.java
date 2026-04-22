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
package com.dremio.exec.physical.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.BatchSchema.SelectionVectorMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.Test;

/** Tests for the userId data member in OpProps. */
public class TestOpPropsUserId {

  private static final String TEST_USER_NAME = "testUser";
  private static final String TEST_USER_ID = "test-user-id-123";
  private static final int TEST_OPERATOR_ID = 42;
  private static final long TEST_MEM_RESERVE = 1000;
  private static final long TEST_MEM_LIMIT = 2000;
  private static final long TEST_MEM_LOW_LIMIT = 500;
  private static final long TEST_FORCED_MEM_LIMIT = 1500;
  private static final double TEST_COST = 10.5;
  private static final boolean TEST_SINGLE_STREAM = true;
  private static final int TEST_TARGET_BATCH_SIZE = 100;
  private static final BatchSchema TEST_SCHEMA = new BatchSchema(ImmutableList.of());
  private static final boolean TEST_MEMORY_BOUND = false;
  private static final double TEST_MEMORY_FACTOR = 1.0;
  private static final boolean TEST_MEMORY_EXPENSIVE = false;

  @Test
  public void testConstructorWithUserId() {
    // Create OpProps with userId
    OpProps props =
        new OpProps(
            TEST_OPERATOR_ID,
            TEST_USER_NAME,
            TEST_USER_ID,
            TEST_MEM_RESERVE,
            TEST_MEM_LIMIT,
            TEST_MEM_LOW_LIMIT,
            TEST_FORCED_MEM_LIMIT,
            TEST_COST,
            TEST_SINGLE_STREAM,
            TEST_TARGET_BATCH_SIZE,
            TEST_SCHEMA,
            TEST_MEMORY_BOUND,
            TEST_MEMORY_FACTOR,
            TEST_MEMORY_EXPENSIVE);

    // Verify userId is set correctly
    assertEquals(TEST_USER_ID, props.getUserId());
    assertEquals(TEST_USER_NAME, props.getUserName());
  }

  @Test
  public void testConstructorWithoutUserId() {
    // Create OpProps without userId (using the constructor that doesn't take userId)
    OpProps props =
        new OpProps(
            TEST_OPERATOR_ID,
            TEST_USER_NAME,
            TEST_MEM_RESERVE,
            TEST_MEM_LIMIT,
            TEST_MEM_LOW_LIMIT,
            TEST_COST,
            TEST_SINGLE_STREAM,
            TEST_TARGET_BATCH_SIZE,
            TEST_SCHEMA,
            TEST_MEMORY_BOUND,
            TEST_MEMORY_FACTOR,
            TEST_MEMORY_EXPENSIVE);

    // Verify userId is null
    assertNull(props.getUserId());
    assertEquals(TEST_USER_NAME, props.getUserName());
  }

  @Test
  public void testCloneMethodsPreserveUserId() {
    // Create OpProps with userId
    OpProps props =
        new OpProps(
            TEST_OPERATOR_ID,
            TEST_USER_NAME,
            TEST_USER_ID,
            TEST_MEM_RESERVE,
            TEST_MEM_LIMIT,
            TEST_MEM_LOW_LIMIT,
            TEST_FORCED_MEM_LIMIT,
            TEST_COST,
            TEST_SINGLE_STREAM,
            TEST_TARGET_BATCH_SIZE,
            TEST_SCHEMA,
            TEST_MEMORY_BOUND,
            TEST_MEMORY_FACTOR,
            TEST_MEMORY_EXPENSIVE);

    // Test cloneWithNewReserve
    OpProps clonedProps = props.cloneWithNewReserve(TEST_MEM_RESERVE * 2);
    assertEquals(TEST_USER_ID, clonedProps.getUserId());
    assertEquals(TEST_MEM_RESERVE * 2, clonedProps.getMemReserve());

    // Test cloneWithMemoryFactor
    clonedProps = props.cloneWithMemoryFactor(2.0);
    assertEquals(TEST_USER_ID, clonedProps.getUserId());
    assertEquals(2.0, clonedProps.getMemoryFactor(), 0.001);

    // Test cloneWithBound
    clonedProps = props.cloneWithBound(true);
    assertEquals(TEST_USER_ID, clonedProps.getUserId());

    // Test cloneWithMemoryExpensive
    clonedProps = props.cloneWithMemoryExpensive(true);
    assertEquals(TEST_USER_ID, clonedProps.getUserId());

    // Test cloneWithNewBatchSize
    clonedProps = props.cloneWithNewBatchSize(200);
    assertEquals(TEST_USER_ID, clonedProps.getUserId());
    assertEquals(200, clonedProps.getTargetBatchSize());

    // Test cloneWithNewIdAndSchema
    BatchSchema newSchema = new BatchSchema(ImmutableList.of());
    clonedProps = props.cloneWithNewIdAndSchema(100, newSchema);
    assertEquals(TEST_USER_ID, clonedProps.getUserId());
    assertEquals(100, clonedProps.getOperatorId());
  }

  @Test
  public void testPrototypeMethods() {
    // Test prototype() - should have null userId
    OpProps props = OpProps.prototype();
    assertNull(props.getUserId());

    // Test prototype(int) - should have null userId
    props = OpProps.prototype(TEST_OPERATOR_ID);
    assertNull(props.getUserId());
    assertEquals(TEST_OPERATOR_ID, props.getOperatorId());

    // Test prototype(long, long) - should have null userId
    props = OpProps.prototype(TEST_MEM_RESERVE, TEST_MEM_LIMIT);
    assertNull(props.getUserId());
    assertEquals(TEST_MEM_RESERVE, props.getMemReserve());
    assertEquals(TEST_MEM_LIMIT, props.getMemLimit());

    // Test prototype(int, long, long) - should have null userId
    props = OpProps.prototype(TEST_OPERATOR_ID, TEST_MEM_RESERVE, TEST_MEM_LIMIT);
    assertNull(props.getUserId());
    assertEquals(TEST_OPERATOR_ID, props.getOperatorId());
    assertEquals(TEST_MEM_RESERVE, props.getMemReserve());
    assertEquals(TEST_MEM_LIMIT, props.getMemLimit());
  }

  @Test
  public void testJsonSerialization() throws Exception {
    // Create OpProps with userId
    OpProps props =
        new OpProps(
            TEST_OPERATOR_ID,
            TEST_USER_NAME,
            TEST_USER_ID,
            TEST_MEM_RESERVE,
            TEST_MEM_LIMIT,
            TEST_MEM_LOW_LIMIT,
            TEST_FORCED_MEM_LIMIT,
            TEST_COST,
            TEST_SINGLE_STREAM,
            TEST_TARGET_BATCH_SIZE,
            TEST_SCHEMA,
            TEST_MEMORY_BOUND,
            TEST_MEMORY_FACTOR,
            TEST_MEMORY_EXPENSIVE);

    // Serialize to JSON
    ObjectMapper mapper = new ObjectMapper();
    String json = mapper.writeValueAsString(props);

    // Deserialize from JSON
    OpProps deserializedProps = mapper.readValue(json, OpProps.class);

    // Verify userId is preserved
    assertEquals(TEST_USER_ID, deserializedProps.getUserId());
    assertEquals(TEST_USER_NAME, deserializedProps.getUserName());
    assertEquals(TEST_OPERATOR_ID, deserializedProps.getOperatorId());
  }

  @Test
  public void testJsonCreatorConstructor() throws Exception {
    // Create OpProps using the @JsonCreator constructor
    OpProps props =
        new OpProps(
            TEST_OPERATOR_ID,
            TEST_USER_NAME,
            TEST_USER_ID,
            TEST_MEM_RESERVE,
            TEST_MEM_LIMIT,
            TEST_COST,
            TEST_SINGLE_STREAM,
            TEST_TARGET_BATCH_SIZE,
            TEST_SCHEMA.clone(SelectionVectorMode.NONE).toByteString().hashCode());

    // Verify userId is set correctly
    assertEquals(TEST_USER_ID, props.getUserId());
    assertEquals(TEST_USER_NAME, props.getUserName());
  }
}
