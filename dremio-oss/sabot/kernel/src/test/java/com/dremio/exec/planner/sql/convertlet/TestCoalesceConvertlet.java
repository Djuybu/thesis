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
package com.dremio.exec.planner.sql.convertlet;

import static org.apache.calcite.sql.fun.SqlStdOperatorTable.COALESCE;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dremio.exec.planner.types.JavaTypeFactoryImpl;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.sql.type.SqlTypeName;
import org.junit.Test;

public class TestCoalesceConvertlet {
  private static final RelDataTypeFactory TYPE_FACTORY = JavaTypeFactoryImpl.INSTANCE;
  private static final RexBuilder REX_BUILDER = new RexBuilder(TYPE_FACTORY);

  @Test
  public void test() {
    ConvertletContext cx = mock(ConvertletContext.class);
    when(cx.getRexBuilder()).thenReturn(REX_BUILDER);

    RelDataTypeFactory factory = REX_BUILDER.getTypeFactory();
    RexLiteral literal =
        REX_BUILDER.makeLiteral(
            42, REX_BUILDER.getTypeFactory().createSqlType(SqlTypeName.INTEGER));

    RexCall call =
        (RexCall)
            REX_BUILDER.makeCall(
                COALESCE,
                REX_BUILDER.makeInputRef(
                    factory.createTypeWithNullability(
                        factory.createSqlType(SqlTypeName.BIGINT), true),
                    0),
                REX_BUILDER.makeInputRef(
                    factory.createTypeWithNullability(
                        factory.createSqlType(SqlTypeName.BIGINT), true),
                    1),
                literal,
                REX_BUILDER.makeInputRef(factory.createSqlType(SqlTypeName.BIGINT), 2),
                REX_BUILDER.makeInputRef(factory.createSqlType(SqlTypeName.BIGINT), 3));

    RexCall rexCall = (RexCall) CoalesceConvertlet.INSTANCE.convert(cx, call);
    assertEquals(5, rexCall.getOperands().size());
    assertEquals(literal, rexCall.getOperands().get(rexCall.getOperands().size() - 1));
  }
}
