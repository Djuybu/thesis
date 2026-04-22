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

import static com.dremio.exec.planner.common.MoreRexUtil.convertToJsonNode;
import static com.dremio.test.scaffolding.ScaffoldingRel.BIG_INT_TYPE;
import static com.dremio.test.scaffolding.ScaffoldingRel.FLOAT_TYPE;
import static com.dremio.test.scaffolding.ScaffoldingRel.INT_TYPE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import com.dremio.exec.planner.DremioRexBuilder;
import com.dremio.exec.planner.types.JavaTypeFactoryImpl;
import com.dremio.exec.planner.types.RelDataTypeSystemImpl;
import com.dremio.exec.vector.complex.fn.ExtendedType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.stream.IntStream;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rel.type.RelDataTypeFieldImpl;
import org.apache.calcite.rel.type.RelRecordType;
import org.apache.calcite.rel.type.StructKind;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.sql.type.BasicSqlType;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

/** Tests aspects of {@link ValuesRel} */
@RunWith(MockitoJUnitRunner.class)
public class TestValuesRel {

  @Mock private RelOptCluster cluster;

  private static final DremioRexBuilder REX_BUILDER = DremioRexBuilder.INSTANCE;
  private static final RelDataTypeFactory TYPE_FACTORY = JavaTypeFactoryImpl.INSTANCE;

  // Mockito cannot mock RelTraitSet as it is final. Add the required LOGICAL convention.
  private static final RelTraitSet traits = RelTraitSet.createEmpty().plus(Rel.LOGICAL);

  @Before
  public void setup() {
    // Mock cluster - Handle getter for type factory.
    when(cluster.getTypeFactory()).thenReturn(TYPE_FACTORY);
  }

  // Test the row type adjustment, modelling the tuples structure of an IN list.
  @Test
  public void testNumericValuesRelRowTypeAdjustment() {
    final int lengthOfINList = 20;

    // Build RowType & Tuples
    RelDataTypeField relDataType =
        new RelDataTypeFieldImpl(
            "ROW_VALUE",
            0,
            new BasicSqlType(RelDataTypeSystemImpl.REL_DATA_TYPE_SYSTEM, SqlTypeName.ANY));
    RelDataType rowType = new RelRecordType(StructKind.FULLY_QUALIFIED, Arrays.asList(relDataType));
    ImmutableList<ImmutableList<RexLiteral>> tuples =
        IntStream.range(0, 20)
            .mapToObj(i -> ImmutableList.of(REX_BUILDER.makeExactLiteral(new BigDecimal(i))))
            .collect(ImmutableList.toImmutableList());

    // Check original types.
    assertEquals(1, rowType.getFieldCount());
    assertEquals(SqlTypeName.ANY, rowType.getFieldList().get(0).getType().getSqlTypeName());

    // Construct ValuesRel
    final ValuesRel valuesRel = new ValuesRel(cluster, rowType, tuples, traits);

    // Check the adjusted types.
    RelDataType adjustedRowType = valuesRel.getRowType();
    assertEquals(1, adjustedRowType.getFieldCount());
    assertEquals(
        SqlTypeName.INTEGER, adjustedRowType.getFieldList().get(0).getType().getSqlTypeName());
  }

  // Test the row type adjustment, modelling the tuples structure of an IN list.
  @Test
  public void testCharValuesRelRowTypeAdjustment() {
    final int lengthOfINList = 20;

    // Build RowType & Tuples
    RelDataTypeField relDataType =
        new RelDataTypeFieldImpl(
            "ROW_VALUE",
            0,
            new BasicSqlType(RelDataTypeSystemImpl.REL_DATA_TYPE_SYSTEM, SqlTypeName.ANY));
    RelDataType rowType = new RelRecordType(StructKind.FULLY_QUALIFIED, Arrays.asList(relDataType));
    ImmutableList<ImmutableList<RexLiteral>> tuples =
        IntStream.range(0, lengthOfINList)
            .mapToObj(i -> ImmutableList.of(REX_BUILDER.makeLiteral(charLiteralBuilder(i))))
            .collect(ImmutableList.toImmutableList());

    // Check original types.
    assertEquals(1, rowType.getFieldCount());
    assertEquals(SqlTypeName.ANY, rowType.getFieldList().get(0).getType().getSqlTypeName());

    // Construct ValuesRel
    final ValuesRel valuesRel = new ValuesRel(cluster, rowType, tuples, traits);

    // Check the adjusted types.
    RelDataType adjustedRowType = valuesRel.getRowType();
    assertEquals(1, adjustedRowType.getFieldCount());
    assertEquals(
        SqlTypeName.VARCHAR, adjustedRowType.getFieldList().get(0).getType().getSqlTypeName());
    assertEquals(
        lengthOfINList - 1, adjustedRowType.getFieldList().get(0).getType().getPrecision());
  }

  @Test
  public void testIntValuesRel() throws IOException {
    ImmutableList<ImmutableList<RexLiteral>> tuples =
        IntStream.range(0, 5)
            .mapToObj(i -> ImmutableList.of(REX_BUILDER.makeLiteral(i, INT_TYPE)))
            .collect(ImmutableList.toImmutableList());
    testTypeValuesRel(INT_TYPE, ExtendedType.INTEGER, tuples);
  }

  @Test
  public void testBigIntValuesRel() throws IOException {
    final int lengthOfINList = 5;

    ImmutableList<ImmutableList<RexLiteral>> tuples =
        IntStream.range(0, lengthOfINList)
            .mapToObj(i -> ImmutableList.of(REX_BUILDER.makeLiteral(i, BIG_INT_TYPE)))
            .collect(ImmutableList.toImmutableList());
    testTypeValuesRel(BIG_INT_TYPE, ExtendedType.LONG, tuples);
  }

  @Test
  public void testFloatValuesRel() throws IOException {
    final int lengthOfINList = 5;
    ImmutableList<ImmutableList<RexLiteral>> tuples =
        IntStream.range(0, lengthOfINList)
            .mapToObj(i -> ImmutableList.of(REX_BUILDER.makeLiteral(i, FLOAT_TYPE)))
            .collect(ImmutableList.toImmutableList());
    testTypeValuesRel(FLOAT_TYPE, ExtendedType.FLOAT, tuples);
  }

  @Test
  public void testDoubleValuesRel() throws IOException {
    final RelDataType doubleType = TYPE_FACTORY.createSqlType(SqlTypeName.DOUBLE);
    ImmutableList<ImmutableList<RexLiteral>> tuples =
        IntStream.range(0, 5)
            .mapToObj(i -> ImmutableList.of(REX_BUILDER.makeLiteral(i, doubleType)))
            .collect(ImmutableList.toImmutableList());
    testTypeValuesRel(doubleType, ExtendedType.DOUBLE, tuples);
  }

  @Test
  public void testDecimalValuesRel() throws IOException {
    RelDataType decimalType = TYPE_FACTORY.createSqlType(SqlTypeName.DECIMAL);
    ImmutableList<ImmutableList<RexLiteral>> tuples =
        IntStream.range(0, 5)
            .mapToObj(i -> ImmutableList.of(REX_BUILDER.makeLiteral(i, decimalType)))
            .collect(ImmutableList.toImmutableList());
    testTypeValuesRel(decimalType, ExtendedType.DECIMAL, tuples);
  }

  private void testTypeValuesRel(
      RelDataType relDataType,
      ExtendedType extendedType,
      ImmutableList<ImmutableList<RexLiteral>> tuples) {

    // Build RowType & Tuples
    BasicSqlType anyType =
        new BasicSqlType(RelDataTypeSystemImpl.REL_DATA_TYPE_SYSTEM, SqlTypeName.ANY);

    RelDataType rowType =
        TYPE_FACTORY.createStructType(ImmutableList.of(anyType), ImmutableList.of("ROW_VALUE"));

    // Construct ValuesRel
    final ValuesRel valuesRel = new ValuesRel(cluster, rowType, tuples, traits);
    // Check the adjusted types.
    RelDataType adjustedRowType = valuesRel.getRowType();
    assertEquals(1, adjustedRowType.getFieldCount());
    assertEquals(
        relDataType.getSqlTypeName(),
        adjustedRowType.getFieldList().get(0).getType().getSqlTypeName());
    JsonNode root = convertToJsonNode(valuesRel.getRowType(), valuesRel.getTuples(), false);
    checkJsonNode(root, extendedType);
  }

  private void checkJsonNode(JsonNode root, ExtendedType extendedType) {
    // The json structure is:
    // [  # List of tuples
    //   {  # Object for each tuple with keys equal to field names
    //     "FIELD_NAME_HERE": { # Object for with type encoded as field name
    //       "$numberLong": 0,
    //       ...
    //     },
    //     ...
    //   }
    // ]
    //

    assertTrue(root.isArray());
    for (JsonNode tuple : root) {
      assertTrue(tuple instanceof ObjectNode);
      ObjectNode objectNode = (ObjectNode) tuple;

      assertEquals(1, objectNode.size());
      JsonNode childObjectNode = objectNode.get("ROW_VALUE");
      { // IMPLICIT BLOCK for Name to ValueAndType
        assertNotNull(childObjectNode);
        assertEquals(1, childObjectNode.size());
        JsonNode valueAndType = childObjectNode.get(extendedType.serialized.getValue());
        { // Implicit BLOCK for ValueAndType
          assertNotNull(childObjectNode.toString(), valueAndType);
          switch (extendedType) {
            case INTEGER:
              assertTrue(valueAndType.isInt());
              break;
            case LONG:
              assertTrue(valueAndType.isLong());
              break;
            case FLOAT:
              assertTrue(valueAndType.isFloat());
              break;
            case DOUBLE:
              assertTrue(valueAndType.isDouble());
              break;
            case DECIMAL:
              assertTrue(valueAndType.isBigDecimal());
              break;
          }
        } // ValueAndType
      } // Name to ValueAndType
    } // tuples
  }

  private String charLiteralBuilder(int length) {
    StringBuilder sb = new StringBuilder(length);
    sb.append("");
    for (int i = 0; i < length; i++) {
      sb.append("a");
    }
    return sb.toString();
  }
}
