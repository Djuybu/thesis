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

import java.math.BigDecimal;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.SqlIntervalLiteral;
import org.apache.calcite.sql.SqlIntervalQualifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNumericLiteral;
import org.apache.calcite.sql.SqlUtil;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql2rel.SqlRexContext;
import org.apache.calcite.sql2rel.SqlRexConvertlet;

/**
 * This class is copy and pasted from Calcite, so that we can reuse it's behavior in Dremio's
 * implementation of
 *
 * @see ConvertletTable
 */
public class CastSqlRexConvertlet implements SqlRexConvertlet {
  public static final CastSqlRexConvertlet INSTANCE = new CastSqlRexConvertlet();

  private CastSqlRexConvertlet() {}

  @Override
  public RexNode convertCall(SqlRexContext cx, SqlCall call) {
    RelDataTypeFactory typeFactory = cx.getTypeFactory();
    final SqlValidator validator = cx.getValidator();
    assert call.getKind() == SqlKind.CAST;
    final SqlNode left = call.operand(0);
    final SqlNode right = call.operand(1);
    if (right instanceof SqlIntervalQualifier) {
      final SqlIntervalQualifier intervalQualifier = (SqlIntervalQualifier) right;
      if (left instanceof SqlIntervalLiteral) {
        RexLiteral sourceInterval = (RexLiteral) cx.convertExpression(left);
        BigDecimal sourceValue = (BigDecimal) sourceInterval.getValue();
        RexLiteral castedInterval =
            cx.getRexBuilder().makeIntervalLiteral(sourceValue, intervalQualifier);
        return castToValidatedType(cx, call, castedInterval);
      } else if (left instanceof SqlNumericLiteral) {
        RexLiteral sourceInterval = (RexLiteral) cx.convertExpression(left);
        BigDecimal sourceValue = (BigDecimal) sourceInterval.getValue();
        final BigDecimal multiplier = intervalQualifier.getUnit().multiplier;
        sourceValue = sourceValue.multiply(multiplier);
        RexLiteral castedInterval =
            cx.getRexBuilder().makeIntervalLiteral(sourceValue, intervalQualifier);
        return castToValidatedType(cx, call, castedInterval);
      }
      return castToValidatedType(cx, call, cx.convertExpression(left));
    }
    SqlDataTypeSpec dataType = (SqlDataTypeSpec) right;
    RelDataType type = dataType.deriveType(cx.getValidator());
    if (type == null) {
      type = cx.getValidator().getValidatedNodeType(dataType.getTypeName());
    }
    RexNode arg = cx.convertExpression(left);
    if (arg.getType().isNullable()) {
      type = typeFactory.createTypeWithNullability(type, true);
    }
    if (SqlUtil.isNullLiteral(left, false)) {
      validator.setValidatedNodeType(left, type);
      return cx.convertExpression(left);
    }
    if (null != dataType.getCollectionsTypeName()) {
      final RelDataType argComponentType = arg.getType().getComponentType();
      final RelDataType componentType = type.getComponentType();
      if (argComponentType.isStruct() && !componentType.isStruct()) {
        RelDataType tt =
            typeFactory
                .builder()
                .add(argComponentType.getFieldList().get(0).getName(), componentType)
                .build();
        tt = typeFactory.createTypeWithNullability(tt, componentType.isNullable());
        boolean isn = type.isNullable();
        type = typeFactory.createMultisetType(tt, -1);
        type = typeFactory.createTypeWithNullability(type, isn);
      }
    }
    return cx.getRexBuilder().makeCast(type, arg);
  }

  public RexNode castToValidatedType(SqlRexContext cx, SqlCall call, RexNode value) {
    return castToValidatedType(call, value, cx.getValidator(), cx.getRexBuilder());
  }

  /**
   * Casts a RexNode value to the validated type of a SqlCall. If the value was already of the
   * validated type, then the value is returned without an additional cast.
   */
  public static RexNode castToValidatedType(
      SqlNode node, RexNode e, SqlValidator validator, RexBuilder rexBuilder) {
    final RelDataType type = validator.getValidatedNodeType(node);
    if (e.getType() == type) {
      return e;
    }
    return rexBuilder.makeCast(type, e);
  }
}
