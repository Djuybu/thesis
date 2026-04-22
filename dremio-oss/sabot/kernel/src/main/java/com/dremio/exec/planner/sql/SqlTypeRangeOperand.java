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

import com.google.common.base.Preconditions;
import java.util.Collection;
import java.util.stream.Collectors;
import org.apache.calcite.sql.type.SqlTypeName;

/** Represents an operand in a calcite SqlFunction. This gets used for SqlOperatorFactory. */
public final class SqlTypeRangeOperand {
  private SqlTypeRangeOperand() {}

  public static SqlOperand regular(Collection<SqlTypeName> sqlTypeNames) {
    Preconditions.checkNotNull(sqlTypeNames);

    int size = sqlTypeNames.size();
    switch (size) {
      case 0:
        throw new IllegalArgumentException("Expected at least one element");

      case 1:
        return SqlTypeNameSqlOperand.regular(sqlTypeNames.stream().findFirst().get());

      default:
        return UnionedSqlOperand.create(
            sqlTypeNames.stream().map(SqlTypeNameSqlOperand::regular).collect(Collectors.toList()));
    }
  }

  public static SqlOperand optional(Collection<SqlTypeName> sqlTypeNames) {
    Preconditions.checkNotNull(sqlTypeNames);

    int size = sqlTypeNames.size();
    switch (size) {
      case 0:
        throw new IllegalArgumentException("Expected at least one element");

      case 1:
        return SqlTypeNameSqlOperand.optional(sqlTypeNames.stream().findFirst().get());

      default:
        return UnionedSqlOperand.create(
            sqlTypeNames.stream()
                .map(SqlTypeNameSqlOperand::optional)
                .collect(Collectors.toList()));
    }
  }

  public static SqlOperand variadic(Collection<SqlTypeName> sqlTypeNames) {
    Preconditions.checkNotNull(sqlTypeNames);

    int size = sqlTypeNames.size();
    switch (size) {
      case 0:
        throw new IllegalArgumentException("Expected at least one element");

      case 1:
        return SqlTypeNameSqlOperand.variadic(sqlTypeNames.stream().findFirst().get());

      default:
        return UnionedSqlOperand.create(
            sqlTypeNames.stream()
                .map(SqlTypeNameSqlOperand::variadic)
                .collect(Collectors.toList()));
    }
  }
}
