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

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.implicit.TypeCoercion;

public final class SqlTypeNameSqlOperand extends SqlOperand {
  private final SqlTypeName acceptedType;

  private SqlTypeNameSqlOperand(Type type, SqlTypeName sqlTypeName) {
    super(type);
    this.acceptedType = sqlTypeName;
  }

  @Override
  public boolean accepts(RelDataType relDataType, SqlCallBinding sqlCallBinding) {
    if (acceptedType.equals(relDataType.getSqlTypeName())) {
      // We have an exact match
      return true;
    }

    if (isWider(acceptedType, relDataType, sqlCallBinding)) {
      return true;
    }

    return false;
  }

  private static boolean isWider(
      SqlTypeName acceptedType, RelDataType userType, SqlCallBinding sqlCallBinding) {
    if (acceptedType.equals(SqlTypeName.ANY)) {
      // getTightestCommonType doesn't work for ANY for whatever reason ...
      return true;
    }

    // For non sql types we can't call createSqlType, so we need to add manual
    // checks
    if (acceptedType == SqlTypeName.ARRAY) {
      return userType.getSqlTypeName() == SqlTypeName.ARRAY;
    }

    if (acceptedType == SqlTypeName.MAP) {
      return userType.getSqlTypeName() == SqlTypeName.MAP;
    }

    // Technically this should only support going up a bigger interval type
    if (SqlTypeName.INTERVAL_TYPES.contains(acceptedType)) {
      return SqlTypeName.INTERVAL_TYPES.contains(userType.getSqlTypeName());
    }

    RelDataTypeFactory relDataTypeFactory = sqlCallBinding.getValidator().getTypeFactory();
    RelDataType acceptedRelDataType = relDataTypeFactory.createSqlType(acceptedType);

    TypeCoercion typeCoercion = sqlCallBinding.getValidator().getTypeCoercion();
    RelDataType commonType = typeCoercion.getTightestCommonType(acceptedRelDataType, userType);
    if (commonType == null) {
      return false;
    }

    return commonType.getSqlTypeName() == acceptedType;
  }

  public static SqlTypeNameSqlOperand regular(SqlTypeName sqlTypeName) {
    return new SqlTypeNameSqlOperand(Type.REGULAR, sqlTypeName);
  }

  public static SqlTypeNameSqlOperand optional(SqlTypeName sqlTypeName) {
    return new SqlTypeNameSqlOperand(Type.OPTIONAL, sqlTypeName);
  }

  public static SqlTypeNameSqlOperand variadic(SqlTypeName sqlTypeName) {
    return new SqlTypeNameSqlOperand(Type.VARIADIC, sqlTypeName);
  }
}
