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

/** Represents an operand in a calcite SqlFunction. This gets used for SqlOperatorFactory. */
public abstract class SqlOperand {
  public final Type type;

  protected SqlOperand(Type type) {
    this.type = type;
  }

  public abstract boolean accepts(RelDataType relDataType, SqlCallBinding sqlCallBinding);

  public boolean accepts(SqlTypeName sqlTypeName, SqlCallBinding sqlCallBinding) {
    RelDataTypeFactory relDataTypeFactory = sqlCallBinding.getValidator().getTypeFactory();
    RelDataType relDataType = relDataTypeFactory.createSqlType(sqlTypeName);
    return accepts(relDataType, sqlCallBinding);
  }

  public enum Type {
    REGULAR,
    OPTIONAL,
    VARIADIC
  }
}
