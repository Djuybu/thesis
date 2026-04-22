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

package com.dremio.sabot.op.fromjson;

import static com.dremio.sabot.RecordSet.li;
import static com.dremio.sabot.RecordSet.r;
import static com.dremio.sabot.RecordSet.rs;
import static com.dremio.sabot.RecordSet.st;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.dremio.common.exceptions.UserException;
import com.dremio.common.expression.CompleteType;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.record.BatchSchema;
import com.dremio.sabot.BaseTestOperator;
import com.dremio.sabot.RecordBatchValidatorDefaultImpl;
import com.dremio.sabot.RecordSet;
import com.dremio.sabot.op.fromjson.ConvertFromJsonPOP.ConversionColumn;
import com.dremio.sabot.op.fromjson.ConvertFromJsonPOP.OriginType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.util.Arrays;
import java.util.List;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.junit.Test;

public class TestConvertFromJsonOperator extends BaseTestOperator {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  @Test
  public void testStructConversionWithNullOnErrorMode() throws Exception {
    BatchSchema inputSchema =
        BatchSchema.newBuilder()
            .addField(Field.nullable("json1", Types.MinorType.VARCHAR.getType()))
            .build();
    RecordSet input =
        rs(
            inputSchema,
            r("{ f_bigint: 100, f_varchar: \"abc\" }"),
            r("{ f_bigint: 101, f_varchar: \"def\" }"),
            r("{ f_bigint: 102, f_varchar: [ 5 ] }"),
            r("{ f_bigint: 103, f_varchar: \"ghi\" }"),
            r("{ f_bigint: 104 }"),
            r("{ f_varchar: \"jkl\", f_extra: \"ignore\" }"),
            r("{ f_varchar: \"mno\", f_bigint: 105 }"),
            r("{}"),
            r("not json"),
            r("{ f_bigint: 106, f_varchar: not json }"),
            r("[ \"a\", \"list\" ]"),
            r("true"),
            r("107"),
            r("\"abc\""),
            r(null));

    BatchSchema outputSchema =
        BatchSchema.newBuilder()
            .addField(
                new Field(
                    "json1",
                    FieldType.nullable(Types.MinorType.STRUCT.getType()),
                    ImmutableList.of(
                        Field.nullable("f_bigint", Types.MinorType.BIGINT.getType()),
                        Field.nullable("f_varchar", Types.MinorType.VARCHAR.getType()))))
            .build();
    RecordSet output =
        rs(
            outputSchema,
            r(st(100L, "abc")),
            r(st(101L, "def")),
            r(null),
            r(st(103L, "ghi")),
            r(st(104L, null)),
            r(st(null, "jkl")),
            r(st(105L, "mno")),
            // the conversion of '{}' is consistent with COPY INTO which considers it an error,
            // but inconsistent with normal CONVERT_FROM which would generate a non-null struct
            // with null field values
            r(null),
            r(null),
            r(null),
            r(null),
            r(null),
            r(null),
            r(null),
            r(null));

    ConversionColumn conversionColumn =
        new ConversionColumn(
            OriginType.RAW,
            ImmutableList.of("table"),
            "json1",
            "json1",
            CompleteType.fromField(outputSchema.getColumn(0)),
            ConvertFromErrorMode.NULL_ON_ERROR);

    validate(conversionColumn, input, output);
  }

  @Test
  public void testListConversionWithNullOnErrorMode() throws Exception {
    BatchSchema inputSchema =
        BatchSchema.newBuilder()
            .addField(Field.nullable("json1", Types.MinorType.VARCHAR.getType()))
            .build();
    RecordSet input =
        rs(
            inputSchema,
            r("{ f1: \"a\", f2: \"struct\" }"),
            r("[ 100, 101, 102 ]"),
            r("[ 103 ]"),
            r("[ 104, \"abc\" ]"),
            r("[ 105, 106 ]"),
            r("[]"),
            r("not json"),
            r("[ 107, not json ]"),
            r("true"),
            r("107"),
            r("\"abc\""),
            r(null));

    BatchSchema outputSchema =
        BatchSchema.newBuilder()
            .addField(
                new Field(
                    "json1",
                    FieldType.nullable(Types.MinorType.LIST.getType()),
                    ImmutableList.of(
                        Field.nullable(
                            ListVector.DATA_VECTOR_NAME, Types.MinorType.BIGINT.getType()))))
            .build();
    RecordSet output =
        rs(
            outputSchema,
            r(null),
            r(li(100L, 101L, 102L)),
            r(li(103L)),
            r(null),
            r(li(105L, 106L)),
            r(li()),
            r(null),
            r(null),
            r(null),
            r(null),
            r(null),
            r(null));

    ConversionColumn conversionColumn =
        new ConversionColumn(
            OriginType.RAW,
            ImmutableList.of("table"),
            "json1",
            "json1",
            CompleteType.fromField(outputSchema.getColumn(0)),
            ConvertFromErrorMode.NULL_ON_ERROR);

    validate(conversionColumn, input, output);
  }

  @Test
  public void testTemporalTypeConversionWithNullOnErrorMode() throws Exception {
    BatchSchema inputSchema =
        BatchSchema.newBuilder()
            .addField(Field.nullable("json1", Types.MinorType.VARCHAR.getType()))
            .build();
    RecordSet input =
        rs(
            inputSchema,
            r(
                "{ f_date: \"2024-01-01\", f_time: \"05:55:55.555\", f_timestamp: \"2024-01-01 05:55:55.555\" }"),
            r(
                "{ f_date: \"not a date\", f_time: \"05:55:55.555\", f_timestamp: \"2024-01-01 05:55:55.555\" }"),
            r(
                "{ f_date: \"2024-01-01\", f_time: \"not a time\", f_timestamp: \"2024-01-01 05:55:55.555\" }"),
            r(
                "{ f_date: \"2024-01-01\", f_time: \"05:55:55.555\", f_timestamp: \"not a timestamp\" }"));

    BatchSchema outputSchema =
        BatchSchema.newBuilder()
            .addField(
                new Field(
                    "json1",
                    FieldType.nullable(Types.MinorType.STRUCT.getType()),
                    ImmutableList.of(
                        Field.nullable("f_date", Types.MinorType.DATEMILLI.getType()),
                        Field.nullable("f_time", Types.MinorType.TIMEMILLI.getType()),
                        Field.nullable("f_timestamp", Types.MinorType.TIMESTAMPMILLI.getType()))))
            .build();
    RecordSet output =
        rs(
            outputSchema,
            r(
                st(
                    LocalDate.of(2024, Month.JANUARY, 1),
                    LocalTime.of(5, 55, 55, 555000000),
                    LocalDateTime.of(2024, Month.JANUARY, 1, 5, 55, 55, 555000000))),
            r(null),
            r(null),
            r(null));

    ConversionColumn conversionColumn =
        new ConversionColumn(
            OriginType.RAW,
            ImmutableList.of("table"),
            "json1",
            "json1",
            CompleteType.fromField(outputSchema.getColumn(0)),
            ConvertFromErrorMode.NULL_ON_ERROR);

    validate(conversionColumn, input, output);
  }

  @Test
  public void testNestedComplexTypesWithNullOnErrorMode() throws Exception {
    BatchSchema inputSchema =
        BatchSchema.newBuilder()
            .addField(Field.nullable("json1", Types.MinorType.VARCHAR.getType()))
            .build();
    RecordSet input =
        rs(
            inputSchema,
            r(
                createListOfStructsAsJson(
                    createStruct(1.5, createNestedStruct(true, ImmutableList.of("a", "b"))),
                    createStruct(2.0, createNestedStruct(null, ImmutableList.of())),
                    null)),
            r(
                createListOfStructsAsJson(
                    createStruct(2.5, createNestedStruct(false, null)),
                    createStruct(null, createNestedStruct(true, ImmutableList.of("c", "d", "e"))),
                    createEmptyStruct())),
            r(null),
            // invalid - struct instead of list at root
            r(OBJECT_MAPPER.writeValueAsString(createInvalidStruct())),
            // invalid - 2nd root list element has list in nested struct field f_struct_in_struct
            r(createListOfStructsAsJson(createStruct(3.0, null), createInvalidStruct())));

    // list of structs of structs with lists
    BatchSchema outputSchema =
        BatchSchema.newBuilder()
            .addField(
                new Field(
                    "json1",
                    FieldType.nullable(Types.MinorType.LIST.getType()),
                    ImmutableList.of(
                        new Field(
                            ListVector.DATA_VECTOR_NAME,
                            FieldType.nullable(Types.MinorType.STRUCT.getType()),
                            ImmutableList.of(
                                Field.nullable("f_double", Types.MinorType.FLOAT8.getType()),
                                new Field(
                                    "f_struct_in_struct",
                                    FieldType.nullable(Types.MinorType.STRUCT.getType()),
                                    ImmutableList.of(
                                        Field.nullable("f_bit", Types.MinorType.BIT.getType()),
                                        new Field(
                                            "f_list_in_struct",
                                            FieldType.nullable(Types.MinorType.LIST.getType()),
                                            ImmutableList.of(
                                                Field.nullable(
                                                    ListVector.DATA_VECTOR_NAME,
                                                    Types.MinorType.VARCHAR.getType()))))))))))
            .build();

    RecordSet output =
        rs(
            outputSchema,
            r(li(st(1.5, st(true, li("a", "b"))), st(2.0, st(null, li())), null)),
            r(li(st(2.5, st(false, null)), st(null, st(true, li("c", "d", "e"))), st(null, null))),
            r(null),
            r(null),
            r(null));

    ConversionColumn conversionColumn =
        new ConversionColumn(
            OriginType.RAW,
            ImmutableList.of("table"),
            "json1",
            "json1",
            CompleteType.fromField(outputSchema.getColumn(0)),
            ConvertFromErrorMode.NULL_ON_ERROR);

    validate(conversionColumn, input, output);
  }

  @Test
  public void testMultipleConversionsWithDifferentErrorModes() throws Exception {
    BatchSchema inputSchema =
        BatchSchema.newBuilder()
            .addField(Field.nullable("json1", Types.MinorType.VARCHAR.getType()))
            .addField(Field.nullable("json2", Types.MinorType.VARCHAR.getType()))
            .build();
    RecordSet input =
        rs(
            inputSchema,
            r("{ f_bigint: 100, f_varchar: \"abc\" }", "{ f_bigint: 100, f_varchar: \"abc\" }"),
            r("{ f_bigint: 101, f_varchar: \"def\" }", "{ f_bigint: 101, f_varchar: \"def\" }"),
            r("{ f_bigint: 102, f_varchar: \"ghi\" }", "{ f_bigint: 102, f_varchar: \"ghi\" }"));

    BatchSchema outputSchema =
        BatchSchema.newBuilder()
            .addField(
                new Field(
                    "json1",
                    FieldType.nullable(Types.MinorType.STRUCT.getType()),
                    ImmutableList.of(
                        Field.nullable("f_bigint", Types.MinorType.BIGINT.getType()),
                        Field.nullable("f_varchar", Types.MinorType.VARCHAR.getType()))))
            .addField(
                new Field(
                    "json2",
                    FieldType.nullable(Types.MinorType.STRUCT.getType()),
                    ImmutableList.of(
                        Field.nullable("f_bigint", Types.MinorType.BIGINT.getType()),
                        Field.nullable("f_varchar", Types.MinorType.VARCHAR.getType()))))
            .build();
    RecordSet output =
        rs(
            outputSchema,
            r(st(100L, "abc"), st(100L, "abc")),
            r(st(101L, "def"), st(101L, "def")),
            r(st(102L, "ghi"), st(102L, "ghi")));

    ConversionColumn conversionColumn1 =
        new ConversionColumn(
            OriginType.RAW,
            ImmutableList.of("table"),
            "json1",
            "json1",
            CompleteType.fromField(outputSchema.getColumn(0)),
            ConvertFromErrorMode.NULL_ON_ERROR);

    ConversionColumn conversionColumn2 =
        new ConversionColumn(
            OriginType.RAW,
            ImmutableList.of("table"),
            "json2",
            "json2",
            CompleteType.fromField(outputSchema.getColumn(1)),
            ConvertFromErrorMode.ERROR_ON_ERROR);

    validate(ImmutableList.of(conversionColumn1, conversionColumn2), input, output);
  }

  @Test
  public void testMultipleConversionsWithErrorOnErrorFailure() {
    BatchSchema inputSchema =
        BatchSchema.newBuilder()
            .addField(Field.nullable("json1", Types.MinorType.VARCHAR.getType()))
            .addField(Field.nullable("json2", Types.MinorType.VARCHAR.getType()))
            .build();
    RecordSet input =
        rs(
            inputSchema,
            r("{ f_bigint: 100, f_varchar: \"abc\" }", "{ f_bigint: 100, f_varchar: \"abc\" }"),
            r("{ f_bigint: 101, f_varchar: \"def\" }", "{ f_bigint: 101, f_varchar: \"def\" }"),
            r("not json", "not json"));

    BatchSchema outputSchema =
        BatchSchema.newBuilder()
            .addField(
                new Field(
                    "json1",
                    FieldType.nullable(Types.MinorType.STRUCT.getType()),
                    ImmutableList.of(
                        Field.nullable("f_bigint", Types.MinorType.BIGINT.getType()),
                        Field.nullable("f_varchar", Types.MinorType.VARCHAR.getType()))))
            .addField(
                new Field(
                    "json2",
                    FieldType.nullable(Types.MinorType.STRUCT.getType()),
                    ImmutableList.of(
                        Field.nullable("f_bigint", Types.MinorType.BIGINT.getType()),
                        Field.nullable("f_varchar", Types.MinorType.VARCHAR.getType()))))
            .build();
    RecordSet output =
        rs(
            outputSchema,
            r(st(100L, "abc"), st(100L, "abc")),
            r(st(101L, "def"), st(101L, "def")),
            r(st(102L, "ghi"), st(102L, "ghi")));

    ConversionColumn conversionColumn1 =
        new ConversionColumn(
            OriginType.RAW,
            ImmutableList.of("table"),
            "json1",
            "json1",
            CompleteType.fromField(outputSchema.getColumn(0)),
            ConvertFromErrorMode.NULL_ON_ERROR);

    ConversionColumn conversionColumn2 =
        new ConversionColumn(
            OriginType.RAW,
            ImmutableList.of("table"),
            "json2",
            "json2",
            CompleteType.fromField(outputSchema.getColumn(1)),
            ConvertFromErrorMode.ERROR_ON_ERROR);

    assertThatThrownBy(
            () -> validate(ImmutableList.of(conversionColumn1, conversionColumn2), input, output))
        .isInstanceOf(UserException.class)
        .hasMessage("Failure converting field json2 from JSON.");
  }

  @Test
  public void testRowSizeCheck() throws Exception {
    try (AutoCloseable ac = with(ExecConstants.ENABLE_ROW_SIZE_LIMIT_ENFORCEMENT, true);
        AutoCloseable ac1 = with(ExecConstants.LIMIT_ROW_SIZE_BYTES, 10); ) {
      BatchSchema inputSchema =
          BatchSchema.newBuilder()
              .addField(Field.nullable("json1", Types.MinorType.VARCHAR.getType()))
              .build();
      RecordSet input =
          rs(
              inputSchema,
              r("{ f_bigint: 100, f_varchar: \"abc\" }"),
              r("{ f_bigint: 101, f_varchar: \"def\" }"),
              r("{ f_bigint: 102, f_varchar: [ 5 ] }"),
              r("{ f_bigint: 103, f_varchar: \"ghi\" }"),
              r("{ f_bigint: 104 }"),
              r("{ f_varchar: \"jkl\", f_extra: \"ignore\" }"),
              r("{ f_varchar: \"mno\", f_bigint: 105 }"),
              r("{}"),
              r("not json"),
              r("{ f_bigint: 106, f_varchar: not json }"),
              r("[ \"a\", \"list\" ]"),
              r("true"),
              r("107"),
              r("\"abc\""),
              r(null));

      BatchSchema outputSchema =
          BatchSchema.newBuilder()
              .addField(
                  new Field(
                      "json1",
                      FieldType.nullable(Types.MinorType.STRUCT.getType()),
                      ImmutableList.of(
                          Field.nullable("f_bigint", Types.MinorType.BIGINT.getType()),
                          Field.nullable("f_varchar", Types.MinorType.VARCHAR.getType()))))
              .build();
      RecordSet output =
          rs(
              outputSchema,
              r(st(100L, "abc")),
              r(st(101L, "def")),
              r(null),
              r(st(103L, "ghi")),
              r(st(104L, null)),
              r(st(null, "jkl")),
              r(st(105L, "mno")),
              // the conversion of '{}' is consistent with COPY INTO which considers it an error,
              // but inconsistent with normal CONVERT_FROM which would generate a non-null struct
              // with null field values
              r(null),
              r(null),
              r(null),
              r(null),
              r(null),
              r(null),
              r(null),
              r(null));

      ConversionColumn conversionColumn =
          new ConversionColumn(
              OriginType.RAW,
              ImmutableList.of("table"),
              "json1",
              "json1",
              CompleteType.fromField(outputSchema.getColumn(0)),
              ConvertFromErrorMode.NULL_ON_ERROR);

      validate(conversionColumn, input, output);
      fail("Query should have throw RowSizeLimitException");
    } catch (UserException e) {
      assertTrue(e.getMessage().contains("Exceeded maximum allowed row size "));
    }
  }

  private ObjectNode createNestedStruct(Boolean fBit, List<String> fList) {
    ObjectNode struct = OBJECT_MAPPER.createObjectNode();
    struct.put("f_bit", fBit);

    if (fList != null) {
      ArrayNode listInStruct = OBJECT_MAPPER.createArrayNode();
      fList.forEach(listInStruct::add);
      struct.set("f_list_in_struct", listInStruct);
    }

    return struct;
  }

  private ObjectNode createStruct(Double fDouble, ObjectNode fStructInStruct) {
    ObjectNode struct = OBJECT_MAPPER.createObjectNode();
    struct.put("f_double", fDouble);
    struct.set("f_struct_in_struct", fStructInStruct);
    return struct;
  }

  private ObjectNode createEmptyStruct() {
    return OBJECT_MAPPER.createObjectNode();
  }

  private ObjectNode createInvalidStruct() {
    // put a list where the nested struct is expected
    ArrayNode list = OBJECT_MAPPER.createArrayNode();
    list.add("invalid");
    ObjectNode struct = OBJECT_MAPPER.createObjectNode();
    struct.set("f_struct_in_struct", list);
    return struct;
  }

  private String createListOfStructsAsJson(ObjectNode... structs) throws Exception {
    ArrayNode listOfStructs = OBJECT_MAPPER.createArrayNode();
    listOfStructs.addAll(Arrays.asList(structs));

    return OBJECT_MAPPER.writeValueAsString(listOfStructs);
  }

  private void validate(ConversionColumn conversionColumn, RecordSet input, RecordSet output)
      throws Exception {
    validate(ImmutableList.of(conversionColumn), input, output);
  }

  private void validate(List<ConversionColumn> conversionColumns, RecordSet input, RecordSet output)
      throws Exception {
    validateSingle(
        getPop(conversionColumns),
        ConvertFromJsonOperator.class,
        input,
        new RecordBatchValidatorDefaultImpl(output));
  }

  private ConvertFromJsonPOP getPop(List<ConversionColumn> conversionColumns) {
    return new ConvertFromJsonPOP(PROPS, null, conversionColumns);
  }
}
