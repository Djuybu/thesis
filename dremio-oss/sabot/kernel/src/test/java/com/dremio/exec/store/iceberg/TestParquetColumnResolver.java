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
package com.dremio.exec.store.iceberg;

import static org.assertj.core.api.Assertions.assertThat;

import com.dremio.common.expression.PathSegment;
import com.dremio.common.expression.SchemaPath;
import com.dremio.exec.ExecTest;
import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.SampleMutator;
import com.dremio.exec.store.parquet.ParquetColumnDefaultResolver;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.jetbrains.annotations.NotNull;
import org.junit.Assert;
import org.junit.Test;

/** Test functions in ParquetColumnDefaultResolver */
public class TestParquetColumnResolver extends ExecTest {
  @Test
  public void testSimplePath() {
    try (SampleMutator mutator = new SampleMutator(allocator); ) {
      List<Field> fields = new ArrayList<>();
      List<SchemaPath> schemaPaths = new ArrayList<>();
      schemaPaths.add(SchemaPath.getSimplePath("string_field"));
      fields.add(
          new Field(
              schemaPaths.get(0).getRootSegment().getPath(),
              FieldType.nullable(ArrowType.Utf8.INSTANCE),
              null));

      BatchSchema schema = BatchSchema.of(fields.toArray(new Field[0]));
      schema.materializeVectors(schemaPaths, mutator);

      Assert.assertTrue(schemaPaths.get(0).toDotString().equalsIgnoreCase("string_field"));
      ParquetColumnDefaultResolver columnDefaultResolver =
          new ParquetColumnDefaultResolver(schemaPaths);
      Assert.assertTrue(
          columnDefaultResolver
              .toDotString(
                  schemaPaths.get(0),
                  mutator.getVector(schemaPaths.get(0).getRootSegment().getPath()))
              .equalsIgnoreCase(schemaPaths.get(0).toDotString()));
    }
  }

  @Test
  public void testComplexStructPath() {
    try (SampleMutator mutator = new SampleMutator(allocator); ) {
      Field strField = new Field("string_field", FieldType.nullable(ArrowType.Utf8.INSTANCE), null);
      Field structField =
          new Field(
              "struct_field",
              FieldType.nullable(ArrowType.Struct.INSTANCE),
              Collections.singletonList(strField));

      BatchSchema schema =
          BatchSchema.of(Collections.singletonList(structField).toArray(new Field[0]));
      List<SchemaPath> schemaPaths = new ArrayList<>();
      schemaPaths.add(SchemaPath.getSimplePath("struct_field"));
      schema.materializeVectors(schemaPaths, mutator);

      Assert.assertTrue(schemaPaths.get(0).toDotString().equalsIgnoreCase("struct_field"));
      ParquetColumnDefaultResolver columnDefaultResolver =
          new ParquetColumnDefaultResolver(schemaPaths);
      Assert.assertTrue(
          columnDefaultResolver
              .toDotString(
                  schemaPaths.get(0),
                  mutator.getVector(schemaPaths.get(0).getRootSegment().getPath()))
              .equalsIgnoreCase(schemaPaths.get(0).toDotString()));

      schemaPaths = new ArrayList<>();
      schemaPaths.add(SchemaPath.getCompoundPath("struct_field", "string_field"));
      schema.materializeVectors(schemaPaths, mutator);

      Assert.assertTrue(
          schemaPaths.get(0).toDotString().equalsIgnoreCase("struct_field.string_field"));
      columnDefaultResolver = new ParquetColumnDefaultResolver(schemaPaths);
      Assert.assertTrue(
          columnDefaultResolver
              .toDotString(
                  schemaPaths.get(0),
                  mutator.getVector(schemaPaths.get(0).getRootSegment().getPath()))
              .equalsIgnoreCase(schemaPaths.get(0).toDotString()));
    }
  }

  @Test
  public void testComplexListPath() {
    try (SampleMutator mutator = new SampleMutator(allocator); ) {
      Field strField = new Field("string_field", FieldType.nullable(ArrowType.Utf8.INSTANCE), null);
      Field listField =
          new Field(
              "list_field",
              FieldType.nullable(ArrowType.List.INSTANCE),
              Collections.singletonList(strField));

      BatchSchema schema =
          BatchSchema.of(Collections.singletonList(listField).toArray(new Field[0]));
      List<SchemaPath> schemaPaths = new ArrayList<>();
      schemaPaths.add(SchemaPath.getSimplePath("list_field"));
      schema.materializeVectors(schemaPaths, mutator);

      Assert.assertTrue(schemaPaths.get(0).toDotString().equalsIgnoreCase("list_field"));
      ParquetColumnDefaultResolver columnDefaultResolver =
          new ParquetColumnDefaultResolver(schemaPaths);
      Assert.assertTrue(
          columnDefaultResolver
              .toDotString(
                  schemaPaths.get(0),
                  mutator.getVector(schemaPaths.get(0).getRootSegment().getPath()))
              .equalsIgnoreCase(schemaPaths.get(0).toDotString()));

      schemaPaths = new ArrayList<>();
      schemaPaths.add(SchemaPath.getCompoundPath("list_field", "string_field"));
      schema.materializeVectors(schemaPaths, mutator);

      Assert.assertTrue(
          schemaPaths.get(0).toDotString().equalsIgnoreCase("list_field.string_field"));
      columnDefaultResolver = new ParquetColumnDefaultResolver(schemaPaths);
      Assert.assertTrue(
          columnDefaultResolver
              .toDotString(
                  schemaPaths.get(0),
                  mutator.getVector(schemaPaths.get(0).getRootSegment().getPath()))
              .equalsIgnoreCase("list_field.list.element.string_field"));
    }
  }

  @Test
  public void testComplexListPathWithArraySegment() {
    try (SampleMutator mutator = new SampleMutator(allocator); ) {
      Field strField = new Field("c", FieldType.nullable(ArrowType.Utf8.INSTANCE), null);
      Field listField =
          new Field(
              "b",
              FieldType.nullable(ArrowType.List.INSTANCE),
              Collections.singletonList(strField));
      Field structField =
          new Field(
              "a",
              FieldType.nullable(ArrowType.Struct.INSTANCE),
              Collections.singletonList(listField));

      BatchSchema schema =
          BatchSchema.of(Collections.singletonList(structField).toArray(new Field[0]));
      List<SchemaPath> schemaPaths = new ArrayList<>();
      schemaPaths.add(SchemaPath.getSimplePath("a"));
      schema.materializeVectors(schemaPaths, mutator);

      Assert.assertTrue(schemaPaths.get(0).toDotString().equalsIgnoreCase("a"));
      ParquetColumnDefaultResolver columnDefaultResolver =
          new ParquetColumnDefaultResolver(schemaPaths);
      Assert.assertTrue(
          columnDefaultResolver
              .toDotString(
                  schemaPaths.get(0),
                  mutator.getVector(schemaPaths.get(0).getRootSegment().getPath()))
              .equalsIgnoreCase(schemaPaths.get(0).toDotString()));

      schemaPaths = new ArrayList<>();
      schemaPaths.add(SchemaPath.getCompoundPath("a", "b"));
      schema.materializeVectors(schemaPaths, mutator);

      Assert.assertTrue(schemaPaths.get(0).toDotString().equalsIgnoreCase("a.b"));
      columnDefaultResolver = new ParquetColumnDefaultResolver(schemaPaths);
      Assert.assertTrue(
          columnDefaultResolver
              .toDotString(
                  schemaPaths.get(0),
                  mutator.getVector(schemaPaths.get(0).getRootSegment().getPath()))
              .equalsIgnoreCase(schemaPaths.get(0).toDotString()));

      SchemaPath schemaPathWithArraySegment = getSchemaPathWithArraySegment();
      schemaPaths = new ArrayList<>();
      schemaPaths.add(schemaPathWithArraySegment);
      Assert.assertTrue(schemaPaths.get(0).toDotString().equalsIgnoreCase("a.b.list.element.c"));
      columnDefaultResolver = new ParquetColumnDefaultResolver(schemaPaths);
      Assert.assertTrue(
          columnDefaultResolver
              .toDotString(
                  schemaPaths.get(0),
                  mutator.getVector(schemaPaths.get(0).getRootSegment().getPath()))
              .equalsIgnoreCase(schemaPaths.get(0).toDotString()));
    }
  }

  @Test
  public void testComplexListPathWithArraySegmentButPartialVectors() {
    try (SampleMutator mutator = new SampleMutator(allocator);
        SampleMutator trimmedMutator = new SampleMutator(allocator)) {
      final BatchSchema fullSchema = createSchemaWithStructOfStringAndListOfString();

      // Test Case 1: Use fullSchema to prepare vectors/mutator so that they contain vectors
      // corresponding to all columns.
      List<SchemaPath> schemaPaths =
          Arrays.asList(SchemaPath.getSimplePath("c1"), SchemaPath.getSimplePath("s2"));
      fullSchema.materializeVectors(schemaPaths, mutator);

      List<SchemaPath> simpleSchemaPaths =
          List.of(SchemaPath.getCompoundPath("c1", "s2"), SchemaPath.getCompoundPath("c1", "l2"));

      // Test that toDotString handles null vector during traversal
      testToDotString(mutator, simpleSchemaPaths, Arrays.asList("c1.s2", "c1.l2"));

      // Test Case 2: Use partialSchema to prepare vectors/mutator so that vector corresponding to
      // "l2" is not created.
      BatchSchema partialSchema = schemaWithStructOfString();
      partialSchema.materializeVectors(schemaPaths, trimmedMutator);

      // SchemaPath with ArraySegment: c1.l2[0]
      PathSegment.NameSegment indexedSegment =
          new PathSegment.NameSegment(
              "c1", new PathSegment.NameSegment("l2", new PathSegment.ArraySegment(0)));
      SchemaPath schemaPathWithArraySegment = new SchemaPath(indexedSegment);

      // Test that toDotString handles null vector during traversal
      testToDotString(
          trimmedMutator,
          Collections.singletonList(schemaPathWithArraySegment),
          Collections.singletonList("c1.l2.list.element"));
    }
  }

  @NotNull
  private SchemaPath getSchemaPathWithArraySegment() {
    UserBitShared.NamePart.Builder cPart = UserBitShared.NamePart.newBuilder();
    cPart.setName("c");

    UserBitShared.NamePart.Builder bArrayPart = UserBitShared.NamePart.newBuilder();
    bArrayPart.setType(UserBitShared.NamePart.Type.ARRAY);
    bArrayPart.setChild(cPart);

    UserBitShared.NamePart.Builder bPart = UserBitShared.NamePart.newBuilder();
    bPart.setName("b");
    bPart.setChild(bArrayPart);

    UserBitShared.NamePart.Builder aPart = UserBitShared.NamePart.newBuilder();
    aPart.setName("a");
    aPart.setChild(bPart);
    return SchemaPath.create(aPart.build());
  }

  /**
   * Creates a schema with struct containing list of strings.
   *
   * <pre
   * BatchSchema structure: c1 row< l2: Array<varchar>, s2: varchar >
   * </pre>
   */
  @NotNull
  private BatchSchema createSchemaWithStructOfStringAndListOfString() {
    Field elementField = new Field("$data$", FieldType.nullable(ArrowType.Utf8.INSTANCE), null);
    Field l2 =
        new Field(
            "l2",
            FieldType.nullable(ArrowType.List.INSTANCE),
            Collections.singletonList(elementField));
    Field s2 = new Field("s2", FieldType.nullable(ArrowType.Utf8.INSTANCE), null);
    Field c1 = new Field("c1", FieldType.nullable(ArrowType.Struct.INSTANCE), List.of(l2, s2));

    return BatchSchema.of(Collections.singletonList(c1).toArray(new Field[0]));
  }

  /**
   * Creates a schema with struct containing list of strings.
   *
   * <pre>
   * BatchSchema structure: c1 row< s2: varchar >
   * </pre>
   */
  @NotNull
  private BatchSchema schemaWithStructOfString() {
    Field s2 = new Field("s2", FieldType.nullable(ArrowType.Utf8.INSTANCE), null);
    Field c1 =
        new Field(
            "c1", FieldType.nullable(ArrowType.Struct.INSTANCE), Collections.singletonList(s2));

    return BatchSchema.of(Collections.singletonList(c1).toArray(new Field[0]));
  }

  /** Tests toDotString method with given expected result. */
  private void testToDotString(
      SampleMutator mutator, List<SchemaPath> schemaPaths, List<String> expectedResolvedColumns) {

    ParquetColumnDefaultResolver columnDefaultResolver =
        new ParquetColumnDefaultResolver(schemaPaths);

    for (int i = 0; i < schemaPaths.size(); i++) {
      String result =
          columnDefaultResolver.toDotString(
              schemaPaths.get(i), mutator.getVector(schemaPaths.get(i).getRootSegment().getPath()));

      assertThat(result).isEqualToIgnoringCase(expectedResolvedColumns.get(i));
    }
  }
}
