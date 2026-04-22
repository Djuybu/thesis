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

import static org.assertj.core.api.Assertions.assertThat;

import com.dremio.common.expression.SchemaPath;
import com.dremio.test.AllocatorRule;
import com.google.common.collect.ImmutableList;
import java.util.List;
import java.util.Objects;
import org.apache.arrow.memory.BufferAllocator;
import org.junit.Rule;
import org.junit.Test;

public class VectorizedListTest {
  private class InnerDummyClass {
    private String stringField;

    public InnerDummyClass(String stringField) {
      this.stringField = stringField;
    }

    public String getStringField() {
      return stringField;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      InnerDummyClass that = (InnerDummyClass) o;
      return Objects.equals(stringField, that.stringField);
    }

    @Override
    public int hashCode() {
      return Objects.hash(stringField);
    }
  }

  private class DummyClass {
    private int intField;
    private long longField;
    private double doubleField;
    private String stringField;
    private InnerDummyClass innerDummyClass;

    public DummyClass(
        int intField,
        long longField,
        double doubleField,
        String stringField,
        InnerDummyClass innerDummyClass) {
      this.intField = intField;
      this.longField = longField;
      this.doubleField = doubleField;
      this.stringField = stringField;
      this.innerDummyClass = innerDummyClass;
    }

    public int getIntField() {
      return intField;
    }

    public long getLongField() {
      return longField;
    }

    public double getDoubleField() {
      return doubleField;
    }

    public String getStringField() {
      return stringField;
    }

    public InnerDummyClass getInnerDummyClass() {
      return innerDummyClass;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      DummyClass that = (DummyClass) o;
      return intField == that.intField
          && longField == that.longField
          && Double.compare(doubleField, that.doubleField) == 0
          && Objects.equals(stringField, that.stringField)
          && Objects.equals(innerDummyClass, that.innerDummyClass);
    }

    @Override
    public int hashCode() {
      return Objects.hash(intField, longField, doubleField, stringField, innerDummyClass);
    }
  }

  @Rule public final AllocatorRule allocatorRule = AllocatorRule.defaultAllocator();

  @Test
  public void testVectorizedList() throws Exception {
    try (BufferAllocator allocator =
            allocatorRule.newAllocator(getClass().getName(), 0, Long.MAX_VALUE);
        VectorizedList<DummyClass> vectorizedList = new VectorizedList<>(allocator) {}) {

      List<DummyClass> listData =
          ImmutableList.of(
              new DummyClass(0, 1L, 2D, "1", new InnerDummyClass(null)),
              new DummyClass(3, -1L, 0, null, new InnerDummyClass("1")),
              new DummyClass(2, 5L, 7d, null, new InnerDummyClass(null)));

      listData.stream().forEach(row -> vectorizedList.add(row));
      assertThat(vectorizedList.size()).isEqualTo(listData.size());

      for (int i = 0; i < listData.size(); i++) {
        DummyClass expectedInterval = listData.get(i);
        DummyClass actualInterval = vectorizedList.get(i);
        assertThat(actualInterval).isEqualTo(expectedInterval);

        // test getValue()
        assertThat(vectorizedList.getValue(SchemaPath.getSimplePath("intField"), i))
            .isEqualTo(expectedInterval.getIntField());
        assertThat(vectorizedList.getValue(SchemaPath.getSimplePath("longField"), i))
            .isEqualTo(expectedInterval.getLongField());
        assertThat(vectorizedList.getValue(SchemaPath.getSimplePath("doubleField"), i))
            .isEqualTo(expectedInterval.getDoubleField());
        assertThat(vectorizedList.getValue(SchemaPath.getSimplePath("stringField"), i))
            .isEqualTo(expectedInterval.getStringField());
        assertThat(
                vectorizedList.getValue(
                    SchemaPath.getCompoundPath("innerDummyClass", "stringField"), i))
            .isEqualTo(((InnerDummyClass) expectedInterval.getInnerDummyClass()).getStringField());
      }

      // test setValue
      vectorizedList.setValue(SchemaPath.getSimplePath("intField"), 1, -100);
      assertThat(vectorizedList.getValue(SchemaPath.getSimplePath("intField"), 1)).isEqualTo(-100);
    }
  }
}
