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
package com.dremio.exec.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.SearchableBatchSchema;
import com.dremio.test.DremioTest;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.stream.Stream;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.UnionMode;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class BatchSchemaFieldTest extends DremioTest {
  @Test
  public void testFromFieldWithPrimitiveTypes() {
    List<Field> fields = new ArrayList<>();
    List<String> expectedType = new ArrayList<>();
    fields.add(new Field("string_field", FieldType.nullable(ArrowType.Utf8.INSTANCE), null));
    expectedType.add("string_field: VARCHAR");

    fields.add(new Field("int_field", FieldType.nullable(new ArrowType.Int(32, true)), null));
    expectedType.add("int_field: INTEGER");

    fields.add(new Field("bigint_field", FieldType.nullable(new ArrowType.Int(64, true)), null));
    expectedType.add("bigint_field: BIGINT");

    fields.add(
        new Field(
            "float_field",
            FieldType.nullable(new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)),
            null));
    expectedType.add("float_field: FLOAT");

    fields.add(
        new Field(
            "double_field",
            FieldType.nullable(new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
            null));
    expectedType.add("double_field: DOUBLE");

    fields.add(
        new Field("decimal_field", FieldType.nullable(new ArrowType.Decimal(10, 5, 128)), null));
    expectedType.add("decimal_field: DECIMAL");

    assertEquals(fields.size(), expectedType.size());
    for (int pos = 0; pos < fields.size(); ++pos) {
      assertEquals(expectedType.get(pos), BatchSchemaField.fromField(fields.get(pos)).toString());
    }
  }

  @Test
  public void testFromFieldWithListTypes() {
    List<Field> fields = new ArrayList<>();
    fields.add(new Field("$data$", FieldType.nullable(ArrowType.Utf8.INSTANCE), null));
    Field list_field = new Field("list_field", FieldType.nullable(new ArrowType.List()), fields);
    String expected = "list_field: LIST<$data$: VARCHAR>";
    assertEquals(expected, BatchSchemaField.fromField(list_field).toString());

    fields.clear();
    fields.add(
        new Field(
            "$data$",
            FieldType.nullable(new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
            null));
    list_field = new Field("list_field", FieldType.nullable(new ArrowType.List()), fields);
    expected = "list_field: LIST<$data$: DOUBLE>";
    assertEquals(expected, BatchSchemaField.fromField(list_field).toString());
  }

  @Test
  public void testFromFieldWithStructTypes() {
    List<Field> fields = new ArrayList<>();
    fields.add(new Field("string_field", FieldType.nullable(ArrowType.Utf8.INSTANCE), null));
    fields.add(new Field("int_field", FieldType.nullable(new ArrowType.Int(32, true)), null));
    fields.add(new Field("bigint_field", FieldType.nullable(new ArrowType.Int(64, true)), null));
    fields.add(
        new Field(
            "float_field",
            FieldType.nullable(new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)),
            null));
    fields.add(
        new Field(
            "double_field",
            FieldType.nullable(new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
            null));
    fields.add(
        new Field("decimal_field", FieldType.nullable(new ArrowType.Decimal(10, 5, 128)), null));

    Field struct_field =
        new Field("struct_field", FieldType.nullable(new ArrowType.Struct()), fields);

    String expected =
        "struct_field: STRUCT<string_field: VARCHAR, "
            + "int_field: INTEGER, bigint_field: BIGINT, float_field: FLOAT, "
            + "double_field: DOUBLE, decimal_field: DECIMAL>";
    assertEquals(expected, BatchSchemaField.fromField(struct_field).toString());
  }

  @Test
  public void testFromFieldWithNestedTypes() {
    List<Field> fields = new ArrayList<>();
    fields.add(new Field("string_field", FieldType.nullable(ArrowType.Utf8.INSTANCE), null));
    fields.add(new Field("int_field", FieldType.nullable(new ArrowType.Int(32, true)), null));
    fields.add(new Field("bigint_field", FieldType.nullable(new ArrowType.Int(64, true)), null));
    fields.add(
        new Field(
            "float_field",
            FieldType.nullable(new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)),
            null));
    fields.add(
        new Field(
            "double_field",
            FieldType.nullable(new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
            null));
    fields.add(
        new Field("decimal_field", FieldType.nullable(new ArrowType.Decimal(10, 5, 128)), null));

    List<Field> list_struct_child = new ArrayList<>();
    list_struct_child.add(new Field("$data$", FieldType.nullable(new ArrowType.Struct()), fields));
    Field list_struct_field =
        new Field("list_struct_field", FieldType.nullable(new ArrowType.List()), list_struct_child);

    String list_struct_field_expected =
        "list_struct_field: LIST<"
            + "$data$: STRUCT<string_field: VARCHAR, "
            + "int_field: INTEGER, bigint_field: BIGINT, float_field: FLOAT, "
            + "double_field: DOUBLE, decimal_field: DECIMAL>>";
    assertEquals(
        list_struct_field_expected, BatchSchemaField.fromField(list_struct_field).toString());

    List<Field> struct_list_child = new ArrayList<>();
    struct_list_child.add(list_struct_field);
    Field struct_list_field =
        new Field(
            "struct_list_field", FieldType.nullable(new ArrowType.Struct()), struct_list_child);
    String struct_list_field_expected =
        "struct_list_field: STRUCT<list_struct_field: LIST<"
            + "$data$: STRUCT<string_field: VARCHAR, "
            + "int_field: INTEGER, bigint_field: BIGINT, float_field: FLOAT, "
            + "double_field: DOUBLE, decimal_field: DECIMAL>>>";
    assertEquals(
        struct_list_field_expected, BatchSchemaField.fromField(struct_list_field).toString());
  }

  @MethodSource("equalSetsOrCoverage")
  @ParameterizedTest
  public void testComparePrimitiveField(final BiPredicate<BatchSchema, BatchSchema> predicate) {
    assertTrue(
        predicate.test(
            BatchSchema.of(
                new Field("string_field", FieldType.nullable(ArrowType.Utf8.INSTANCE), null)),
            BatchSchema.of(
                new Field("string_field", FieldType.nullable(ArrowType.Utf8.INSTANCE), null))));

    assertFalse(
        predicate.test(
            BatchSchema.of(
                new Field("col1", FieldType.nullable(new ArrowType.Int(32, false)), null)),
            BatchSchema.of(new Field("col1", FieldType.nullable(ArrowType.Utf8.INSTANCE), null))));
  }

  @MethodSource("equalSetsOrCoverage")
  @ParameterizedTest
  public void testCompareComplexNestedFields(
      final BiPredicate<BatchSchema, BatchSchema> predicate) {
    List<Field> fields = new ArrayList<>();
    fields.add(new Field("string_field", FieldType.nullable(ArrowType.Utf8.INSTANCE), null));
    fields.add(new Field("int_field", FieldType.nullable(new ArrowType.Int(32, true)), null));
    fields.add(new Field("bigint_field", FieldType.nullable(new ArrowType.Int(64, true)), null));
    fields.add(
        new Field(
            "float_field",
            FieldType.nullable(new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)),
            null));
    fields.add(
        new Field(
            "double_field",
            FieldType.nullable(new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
            null));
    fields.add(
        new Field("decimal_field", FieldType.nullable(new ArrowType.Decimal(10, 5, 128)), null));

    List<Field> list_struct_child_src = new ArrayList<>();
    List<Field> list_struct_child_tgt = new ArrayList<>();

    list_struct_child_src.add(
        new Field("$data$", FieldType.nullable(new ArrowType.Struct()), fields));
    Collections.shuffle(fields);
    list_struct_child_tgt.add(
        new Field("$data$", FieldType.nullable(new ArrowType.Struct()), fields));

    Field list_struct_field_src =
        new Field(
            "list_struct_field", FieldType.nullable(new ArrowType.List()), list_struct_child_src);
    Field list_struct_field_tgt =
        new Field(
            "list_struct_field", FieldType.nullable(new ArrowType.List()), list_struct_child_tgt);

    assertTrue(
        predicate.test(
            BatchSchema.of(list_struct_field_src), BatchSchema.of(list_struct_field_tgt)));

    List<Field> struct_list_child_src = new ArrayList<>();
    struct_list_child_src.add(list_struct_field_src);
    List<Field> struct_list_child_tgt = new ArrayList<>();
    struct_list_child_tgt.add(list_struct_field_tgt);
    Field struct_list_field_src =
        new Field(
            "struct_list_field", FieldType.nullable(new ArrowType.Struct()), struct_list_child_src);
    Field struct_list_field_tgt =
        new Field(
            "struct_list_field", FieldType.nullable(new ArrowType.Struct()), struct_list_child_tgt);

    assertTrue(
        predicate.test(
            BatchSchema.of(struct_list_field_src), BatchSchema.of(struct_list_field_tgt)));
  }

  @MethodSource("equalSetsOrCoverage")
  @ParameterizedTest
  public void testCompareComplexNestedFieldsFailureTypeChange(
      final BiPredicate<BatchSchema, BatchSchema> predicate) {
    List<Field> fields = new ArrayList<>();
    fields.add(new Field("string_field", FieldType.nullable(ArrowType.Utf8.INSTANCE), null));
    fields.add(new Field("int_field", FieldType.nullable(new ArrowType.Int(32, true)), null));
    fields.add(new Field("bigint_field", FieldType.nullable(new ArrowType.Int(64, true)), null));
    fields.add(
        new Field(
            "float_field",
            FieldType.nullable(new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)),
            null));
    fields.add(
        new Field(
            "double_field",
            FieldType.nullable(new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
            null));
    fields.add(
        new Field("decimal_field", FieldType.nullable(new ArrowType.Decimal(10, 5, 128)), null));

    List<Field> list_struct_child_src = new ArrayList<>();
    List<Field> list_struct_child_tgt = new ArrayList<>();

    list_struct_child_src.add(
        new Field("$data$", FieldType.nullable(new ArrowType.Struct()), fields));
    // change type of field 0 from string to int. this should cause comparison to fail
    fields.set(0, new Field("string_field", FieldType.nullable(new ArrowType.Int(32, true)), null));
    Collections.shuffle(fields);
    list_struct_child_tgt.add(
        new Field("$data$", FieldType.nullable(new ArrowType.Struct()), fields));

    Field list_struct_field_src =
        new Field(
            "list_struct_field", FieldType.nullable(new ArrowType.List()), list_struct_child_src);
    Field list_struct_field_tgt =
        new Field(
            "list_struct_field", FieldType.nullable(new ArrowType.List()), list_struct_child_tgt);

    assertFalse(
        predicate.test(
            BatchSchema.of(list_struct_field_src), BatchSchema.of(list_struct_field_tgt)));

    List<Field> struct_list_child_src = new ArrayList<>();
    struct_list_child_src.add(list_struct_field_src);
    List<Field> struct_list_child_tgt = new ArrayList<>();
    struct_list_child_tgt.add(list_struct_field_tgt);
    Field struct_list_field_src =
        new Field(
            "struct_list_field", FieldType.nullable(new ArrowType.Struct()), struct_list_child_src);
    Field struct_list_field_tgt =
        new Field(
            "struct_list_field", FieldType.nullable(new ArrowType.Struct()), struct_list_child_tgt);

    assertFalse(
        predicate.test(
            BatchSchema.of(struct_list_field_src), BatchSchema.of(struct_list_field_tgt)));
  }

  @MethodSource("equalSets")
  @ParameterizedTest
  public void testCompareComplexNestedFieldsFailure(
      final BiPredicate<BatchSchema, BatchSchema> predicate) {
    List<Field> fields = new ArrayList<>();
    fields.add(new Field("string_field", FieldType.nullable(ArrowType.Utf8.INSTANCE), null));
    fields.add(new Field("int_field", FieldType.nullable(new ArrowType.Int(32, true)), null));
    fields.add(new Field("bigint_field", FieldType.nullable(new ArrowType.Int(64, true)), null));
    fields.add(
        new Field(
            "float_field",
            FieldType.nullable(new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)),
            null));
    fields.add(
        new Field(
            "double_field",
            FieldType.nullable(new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)),
            null));
    fields.add(
        new Field("decimal_field", FieldType.nullable(new ArrowType.Decimal(10, 5, 128)), null));

    List<Field> list_struct_child_src = new ArrayList<>();
    List<Field> list_struct_child_tgt = new ArrayList<>();

    list_struct_child_src.add(
        new Field("$data$", FieldType.nullable(new ArrowType.Struct()), fields));
    Collections.shuffle(fields);
    // add one extra non-nullable field to the child list. this should cause comparison to fail
    fields.add(new Field("int_field_2", FieldType.notNullable(new ArrowType.Int(32, true)), null));
    list_struct_child_tgt.add(
        new Field("$data$", FieldType.notNullable(new ArrowType.Struct()), fields));

    Field list_struct_field_src =
        new Field(
            "list_struct_field", FieldType.nullable(new ArrowType.List()), list_struct_child_src);
    Field list_struct_field_tgt =
        new Field(
            "list_struct_field", FieldType.nullable(new ArrowType.List()), list_struct_child_tgt);

    assertFalse(
        predicate.test(
            BatchSchema.of(list_struct_field_src), BatchSchema.of(list_struct_field_tgt)));

    List<Field> struct_list_child_src = new ArrayList<>();
    struct_list_child_src.add(list_struct_field_src);
    List<Field> struct_list_child_tgt = new ArrayList<>();
    struct_list_child_tgt.add(list_struct_field_tgt);
    Field struct_list_field_src =
        new Field(
            "struct_list_field", FieldType.nullable(new ArrowType.Struct()), struct_list_child_src);
    Field struct_list_field_tgt =
        new Field(
            "struct_list_field", FieldType.nullable(new ArrowType.Struct()), struct_list_child_tgt);

    assertFalse(
        predicate.test(
            BatchSchema.of(struct_list_field_src), BatchSchema.of(struct_list_field_tgt)));
  }

  @MethodSource("equalSetsOrCoverage")
  @ParameterizedTest
  public void testCompareUnionFields(final BiPredicate<BatchSchema, BatchSchema> predicate) {
    List<Field> union_children = new ArrayList<>();
    List<Field> rev_union_children = new ArrayList<>();

    union_children.add(
        new Field("string_field", FieldType.nullable(ArrowType.Utf8.INSTANCE), null));
    union_children.add(
        new Field("int_field", FieldType.nullable(new ArrowType.Int(32, false)), null));

    rev_union_children.add(
        new Field("int_field", FieldType.nullable(new ArrowType.Int(32, false)), null));
    rev_union_children.add(
        new Field("string_field", FieldType.nullable(ArrowType.Utf8.INSTANCE), null));

    int[] typeIds = new int[2];
    typeIds[0] = Types.getMinorTypeForArrowType(union_children.get(0).getType()).ordinal();
    typeIds[1] = Types.getMinorTypeForArrowType(union_children.get(1).getType()).ordinal();

    int[] revTypeIds = new int[2];
    revTypeIds[0] = Types.getMinorTypeForArrowType(rev_union_children.get(0).getType()).ordinal();
    revTypeIds[1] = Types.getMinorTypeForArrowType(rev_union_children.get(1).getType()).ordinal();

    assertTrue(
        predicate.test(
            BatchSchema.of(
                new Field(
                    "union_field",
                    FieldType.nullable(new ArrowType.Union(UnionMode.Sparse, typeIds)),
                    union_children)),
            BatchSchema.of(
                new Field(
                    "union_field",
                    FieldType.nullable(new ArrowType.Union(UnionMode.Sparse, revTypeIds)),
                    rev_union_children))));
  }

  @Test
  public void testContainsPrimitiveFields() {
    assertTrue(
        BatchSchema.of(new Field("s", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null))
            .insertsInto(
                BatchSchema.of(
                    new Field("s", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null))));

    // The LHS schema is not allowed to have columns not mentioned in the RHS schema
    assertFalse(
        BatchSchema.of(
                new Field("s", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null),
                new Field("i", FieldType.notNullable(new ArrowType.Int(32, false)), null))
            .insertsInto(
                BatchSchema.of(
                    new Field("s", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null))));

    assertFalse(
        BatchSchema.of(new Field("s", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null))
            .insertsInto(
                BatchSchema.of(
                    new Field("s2", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null))));

    assertTrue(
        BatchSchema.of(new Field("i", FieldType.notNullable(new ArrowType.Int(32, false)), null))
            .insertsInto(
                BatchSchema.of(
                    new Field("s", FieldType.nullable(ArrowType.Utf8.INSTANCE), null),
                    new Field("i", FieldType.notNullable(new ArrowType.Int(32, false)), null))));

    // Reordering fields shouldn't matter
    assertTrue(
        BatchSchema.of(new Field("i", FieldType.notNullable(new ArrowType.Int(32, false)), null))
            .insertsInto(
                BatchSchema.of(
                    new Field("i", FieldType.notNullable(new ArrowType.Int(32, false)), null),
                    new Field("s", FieldType.nullable(ArrowType.Utf8.INSTANCE), null))));

    // Types must match, not just names and nullability
    assertFalse(
        BatchSchema.of(
                new Field("s", FieldType.nullable(new ArrowType.Int(64, false)), null),
                new Field("i", FieldType.notNullable(new ArrowType.Int(32, false)), null))
            .insertsInto(
                BatchSchema.of(
                    new Field("s", FieldType.nullable(ArrowType.Utf8.INSTANCE), null),
                    new Field("i", FieldType.notNullable(new ArrowType.Int(32, false)), null))));

    // Case-insensitivity
    assertTrue(
        BatchSchema.of(new Field("i", FieldType.notNullable(new ArrowType.Int(32, false)), null))
            .insertsInto(
                BatchSchema.of(
                    new Field("s", FieldType.nullable(ArrowType.Utf8.INSTANCE), null),
                    new Field("I", FieldType.notNullable(new ArrowType.Int(32, false)), null))));

    // Despite case-insensitivity with respect to i/I, fail if the LHS has a column not in the RHS
    assertFalse(
        BatchSchema.of(
                new Field("s", FieldType.nullable(ArrowType.Utf8.INSTANCE), null),
                new Field("I", FieldType.notNullable(new ArrowType.Int(32, false)), null))
            .insertsInto(
                BatchSchema.of(
                    new Field("i", FieldType.notNullable(new ArrowType.Int(32, false)), null))));

    assertFalse(
        BatchSchema.of(new Field("i", FieldType.notNullable(new ArrowType.Int(32, false)), null))
            .insertsInto(
                BatchSchema.of(
                    new Field("s", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null),
                    new Field("i", FieldType.notNullable(new ArrowType.Int(32, false)), null))));

    // This should fail because of the difference in datatypes on i
    assertFalse(
        BatchSchema.of(new Field("i", FieldType.notNullable(new ArrowType.Int(32, false)), null))
            .insertsInto(
                BatchSchema.of(
                    new Field("s", FieldType.nullable(ArrowType.Utf8.INSTANCE), null),
                    new Field("i", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null))));
  }

  @Test
  public void testContainsStructWithNullableFields() {
    /* Sketch of types used in this test:
     *
     * struct $data_a$ NOT NULL {
     *    string_field
     *    int_field
     * }
     *
     * struct $data_b$ {
     *     float_field
     *     decimal_field
     * }
     */

    final Field stringField =
        new Field("string_field", FieldType.nullable(ArrowType.Utf8.INSTANCE), null);

    final List<Field> fieldGroupA =
        ImmutableList.of(
            stringField,
            new Field("int_field", FieldType.nullable(new ArrowType.Int(32, true)), null));

    final Field decimalField =
        new Field("decimal_field", FieldType.nullable(new ArrowType.Decimal(10, 5, 128)), null);

    final List<Field> fieldGroupB =
        ImmutableList.of(
            new Field(
                "float_field",
                FieldType.nullable(new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)),
                null),
            decimalField);

    final Field structA =
        new Field("$data_a$", FieldType.notNullable(new ArrowType.Struct()), fieldGroupA);
    final Field structB =
        new Field("$data_b$", FieldType.nullable(new ArrowType.Struct()), fieldGroupB);

    assertTrue(BatchSchema.of(structA).insertsInto(BatchSchema.of(structA, structB)));

    // Reordering BatchSchema fields should have no effect
    assertTrue(BatchSchema.of(structA).insertsInto(BatchSchema.of(structB, structA)));

    assertTrue(BatchSchema.of(structA).insertsInto(BatchSchema.of(structA)));

    assertFalse(BatchSchema.of().insertsInto(BatchSchema.of(structA)));

    assertFalse(BatchSchema.of(structB).insertsInto(BatchSchema.of(structA)));

    assertTrue(BatchSchema.of(structB).insertsInto(BatchSchema.of(structB)));

    assertTrue(BatchSchema.of().insertsInto(BatchSchema.of(structB)));

    // structB is not mentioned in the RHS, and that's not allowed
    assertFalse(BatchSchema.of(structA, structB).insertsInto(BatchSchema.of(structA)));

    // structA is not nullable and missing from the LHS
    assertFalse(BatchSchema.of(structB).insertsInto(BatchSchema.of(structA, structB)));

    /* We supply a field "$data_a$" of type struct, but with the wrong constituent columns
     * (a copy from structB).  This is not allowed, even though structA on the RHS has
     * only nullable columns.  In practice, this would produce a projection with no data values
     */
    assertFalse(
        BatchSchema.of(
                new Field("$data_a$", FieldType.notNullable(new ArrowType.Struct()), fieldGroupB))
            .insertsInto(BatchSchema.of(structA, structB)));

    final Field justDecimal =
        new Field(
            "$data_b$", FieldType.nullable(new ArrowType.Struct()), ImmutableList.of(decimalField));

    // Everything involved here is nullable
    assertTrue(BatchSchema.of(justDecimal).insertsInto(BatchSchema.of(structB)));

    final Field justString =
        new Field(
            "$data_a$", FieldType.nullable(new ArrowType.Struct()), ImmutableList.of(stringField));

    assertTrue(BatchSchema.of(justString).insertsInto(BatchSchema.of(structA)));

    assertFalse(BatchSchema.of().insertsInto(BatchSchema.of(structA)));
  }

  @Test
  public void testContainsNestedNonNullableFields() {
    final String sReq = "s_req";
    final String sOpt = "s_opt";

    final Field fieldA = new Field("a", FieldType.nullable(ArrowType.Utf8.INSTANCE), null);
    final Field fieldB = new Field("b", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null);
    final Field fieldX = new Field("x", FieldType.nullable(ArrowType.Utf8.INSTANCE), null);
    final Field fieldY = new Field("y", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null);

    final Field reqStruct =
        new Field(
            sReq, FieldType.notNullable(new ArrowType.Struct()), ImmutableList.of(fieldA, fieldB));
    final Field optStruct =
        new Field(
            sOpt, FieldType.nullable(new ArrowType.Struct()), ImmutableList.of(fieldX, fieldY));

    final BatchSchema target = BatchSchema.of(reqStruct, optStruct);

    assertTrue(BatchSchema.of(reqStruct).insertsInto(target));
    assertFalse(BatchSchema.of(optStruct).insertsInto(target));
    assertTrue(BatchSchema.of(reqStruct, optStruct).insertsInto(target));
    assertTrue(BatchSchema.of(optStruct, reqStruct).insertsInto(target));
    assertTrue(target.insertsInto(target));

    // Fails; missing field a
    final Field reqStructWithFieldA =
        new Field(sReq, FieldType.notNullable(new ArrowType.Struct()), ImmutableList.of(fieldA));
    assertFalse(BatchSchema.of(reqStructWithFieldA).insertsInto(target));

    // Provides minimal field set required for acceptance (s_req and its constituent field a)
    final Field reqStructWithFieldB =
        new Field(sReq, FieldType.notNullable(new ArrowType.Struct()), ImmutableList.of(fieldB));
    assertTrue(BatchSchema.of(reqStructWithFieldB).insertsInto(target));

    // Fails; missing fields s_req, y
    final Field optStructWithFieldX =
        new Field(sOpt, FieldType.notNullable(new ArrowType.Struct()), ImmutableList.of(fieldX));
    assertFalse(BatchSchema.of(optStructWithFieldX).insertsInto(target));

    // Still fails; missing s_req
    final Field optStructWithFieldY =
        new Field(sReq, FieldType.notNullable(new ArrowType.Struct()), ImmutableList.of(fieldY));
    assertFalse(BatchSchema.of(optStructWithFieldY).insertsInto(target));

    // Supply all leaf fields, but in the wrong structs, expecting rejection
    final BatchSchema leavesSwappedBetweenStructs =
        BatchSchema.of(
            new Field(
                sReq,
                FieldType.notNullable(new ArrowType.Struct()),
                ImmutableList.of(fieldX, fieldY)),
            new Field(
                sOpt,
                FieldType.nullable(new ArrowType.Struct()),
                ImmutableList.of(fieldA, fieldB)));
    assertFalse(leavesSwappedBetweenStructs.insertsInto(target));
  }

  @Test
  public void testFindField() {
    int columns = 1000000;
    String fieldPrefix = "field_name_";
    List<Field> fields = new ArrayList<>();
    for (int c = 0; c < columns; ++c) {
      fields.add(
          new Field(fieldPrefix + c, FieldType.nullable(new ArrowType.Int(32, false)), null));
    }

    BatchSchema schema = BatchSchema.of(fields.toArray(new Field[0]));
    SearchableBatchSchema searchableBatchSchema = SearchableBatchSchema.of(schema);

    for (int c = 0; c < columns; ++c) {
      String fieldName = fieldPrefix + c;
      Optional<Field> field = searchableBatchSchema.findFieldIgnoreCase(fieldName);
      assertTrue(field.isPresent());
      assertEquals(fieldName, field.get().getName());
    }

    String fieldName = fieldPrefix + "non_existent";
    Optional<Field> field = searchableBatchSchema.findFieldIgnoreCase(fieldName);
    assertFalse(field.isPresent());
  }

  public static Stream<BiPredicate<BatchSchema, BatchSchema>> equalSetsOrCoverage() {
    /* coversNonNullable is not generally commutative or interchangeable with
     * equalsTypesWithoutPositions, but many test methods in this class happen to be
     * written such that the following predicates all return the same truth value for
     * a given set of fixture operands.
     */
    return Stream.of(
        BatchSchema::equalsTypesWithoutPositions,
        BatchSchema::insertsInto,
        (a, b) -> b.equalsTypesWithoutPositions(a),
        (a, b) -> b.insertsInto(a));
  }

  public static Stream<BiPredicate<BatchSchema, BatchSchema>> equalSets() {
    return Stream.of(
        BatchSchema::equalsTypesWithoutPositions, (a, b) -> b.equalsTypesWithoutPositions(a));
  }
}
