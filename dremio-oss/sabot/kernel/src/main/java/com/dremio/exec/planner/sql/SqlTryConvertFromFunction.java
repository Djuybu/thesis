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

package com.dremio.exec.planner.sql;

import com.dremio.common.exceptions.UserException;
import com.dremio.common.expression.CompleteType;
import com.google.common.base.Preconditions;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlOperandCountRange;
import org.apache.calcite.sql.SqlOperatorBinding;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.type.InferTypes;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.SqlOperandCountRanges;

public class SqlTryConvertFromFunction extends SqlFunction {

  public SqlTryConvertFromFunction() {
    super(
        "TRY_CONVERT_FROM",
        SqlKind.OTHER_FUNCTION,
        null,
        InferTypes.FIRST_KNOWN,
        null,
        SqlFunctionCategory.USER_DEFINED_FUNCTION);
  }

  @Override
  public RelDataType inferReturnType(SqlOperatorBinding opBinding) {
    Preconditions.checkArgument(opBinding.getOperandCount() == 2);
    return opBinding.getOperandType(1);
  }

  @Override
  public String getSignatureTemplate(int operandsCount) {
    return "{0}({1} AS {2})";
  }

  @Override
  public SqlOperandCountRange getOperandCountRange() {
    return SqlOperandCountRanges.of(2);
  }

  @Override
  public boolean checkOperandTypes(SqlCallBinding callBinding, boolean throwOnFailure) {
    if (!OperandTypes.CHARACTER.checkSingleOperandType(
        callBinding, callBinding.getCall().operand(0), 0, false)) {
      if (throwOnFailure) {
        throw UserException.validationError()
            .message("First argument to TRY_CONVERT_FROM must be of type CHAR or VARCHAR.")
            .buildSilently();
      }
      return false;
    }

    SqlDataTypeSpec returnType = callBinding.getCall().operand(1);
    return validateReturnType(returnType.deriveType(callBinding.getValidator()), throwOnFailure);
  }

  @Override
  public SqlSyntax getSyntax() {
    return SqlSyntax.SPECIAL;
  }

  @Override
  public void unparse(SqlWriter writer, SqlCall call, int leftPrec, int rightPrec) {
    SqlWriter.Frame frame = writer.startFunCall(this.getName());
    call.operand(0).unparse(writer, 0, 0);
    writer.sep("AS");
    call.operand(1).unparse(writer, 0, 0);
    writer.endFunCall(frame);
  }

  private boolean validateReturnType(RelDataType returnType, boolean throwOnFailure) {
    Field field = CalciteArrowHelper.fieldFromCalciteRowType("", returnType).orElse(null);
    CompleteType type = field != null ? CompleteType.fromField(field) : null;

    if (field == null
        || !field.isNullable()
        || !((type.isStruct() && !type.getChildren().isEmpty()) || type.isList())) {
      if (throwOnFailure) {
        throw UserException.validationError()
            .message(
                "Invalid type %s in TRY_CONVERT_FROM.  Type must be either a nullable ROW or ARRAY type.",
                returnType.toString())
            .buildSilently();
      }

      return false;
    }

    return true;
  }
}
