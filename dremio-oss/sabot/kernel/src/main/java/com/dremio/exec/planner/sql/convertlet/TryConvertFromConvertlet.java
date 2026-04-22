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

import static com.dremio.exec.planner.sql.DremioSqlOperatorTable.CONVERT_FROMJSON;

import com.dremio.sabot.op.fromjson.ConvertFromErrorMode;
import com.google.common.collect.ImmutableList;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql2rel.SqlRexContext;
import org.apache.calcite.sql2rel.SqlRexConvertlet;

public class TryConvertFromConvertlet implements SqlRexConvertlet {
  public static final SqlRexConvertlet INSTANCE = new TryConvertFromConvertlet();

  private TryConvertFromConvertlet() {}

  @Override
  public RexNode convertCall(SqlRexContext context, SqlCall call) {
    RexBuilder builder = context.getRexBuilder();
    SqlDataTypeSpec dataType = call.operand(1);
    RelDataType returnType = dataType.deriveType(context.getValidator());

    return builder.makeCall(
        returnType,
        CONVERT_FROMJSON,
        ImmutableList.of(
            context.convertExpression(call.operand(0)),
            builder.makeFlag(ConvertFromErrorMode.NULL_ON_ERROR)));
  }
}
