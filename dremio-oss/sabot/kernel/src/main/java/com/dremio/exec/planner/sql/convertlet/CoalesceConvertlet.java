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

import java.util.ArrayList;
import java.util.List;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;

public final class CoalesceConvertlet extends RexCallConvertlet {
  public static final RexCallConvertlet INSTANCE = new CoalesceConvertlet();

  private CoalesceConvertlet() {}

  @Override
  public boolean matchesCall(RexCall call) {
    return call.getOperator() == COALESCE;
  }

  @Override
  public RexCall convertCall(ConvertletContext cx, RexCall call) {
    RexBuilder rexBuilder = cx.getRexBuilder();

    List<RexNode> caseExprs = new ArrayList<>();
    List<RexNode> operands = call.getOperands();
    int index = 0;
    for (; index < operands.size(); index++) {
      RexNode operand = operands.get(index);
      // Short circuit when operand is not null, but we need to keep at least 2 operands for the
      // CASE statement
      if (index > 0 && !operand.getType().isNullable()) {
        caseExprs.add(operand);
        break;
      }
      if (index < operands.size() - 1) {
        caseExprs.add(rexBuilder.makeCall(SqlStdOperatorTable.IS_NOT_NULL, operand));
      }
      caseExprs.add(operand);
    }
    RexCall rexCall =
        (RexCall) rexBuilder.makeCall(call.getType(), SqlStdOperatorTable.CASE, caseExprs);

    return rexCall;
  }
}
