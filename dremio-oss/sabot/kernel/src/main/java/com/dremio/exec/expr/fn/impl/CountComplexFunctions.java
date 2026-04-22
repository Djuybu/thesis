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
package com.dremio.exec.expr.fn.impl;

import com.dremio.exec.expr.AggrFunction;
import com.dremio.exec.expr.annotations.FunctionTemplate;
import com.dremio.exec.expr.annotations.Output;
import com.dremio.exec.expr.annotations.Param;
import com.dremio.exec.expr.annotations.Workspace;
import org.apache.arrow.vector.complex.reader.FieldReader;
import org.apache.arrow.vector.holders.NullableBigIntHolder;

public class CountComplexFunctions {

  @FunctionTemplate(name = "count", scope = FunctionTemplate.FunctionScope.POINT_AGGREGATE)
  public static class NullableComplexCountFunction implements AggrFunction {

    @Param private FieldReader in;
    @Workspace NullableBigIntHolder value;
    @Output NullableBigIntHolder out;

    @Override
    public void setup() {
      value = new NullableBigIntHolder();
      value.value = 0;
      value.isSet = 1;
    }

    @Override
    public void add() {
      if (!in.isSet() || in.readObject() == null) {
        return;
      }
      value.value++;
    }

    @Override
    public void output() {
      out.value = value.value;
      out.isSet = 1;
    }

    @Override
    public void reset() {
      value.value = 0;
    }
  }
}
