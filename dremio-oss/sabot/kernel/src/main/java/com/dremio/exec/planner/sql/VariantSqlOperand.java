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
import org.apache.calcite.sql.SqlCallBinding;

public final class VariantSqlOperand extends SqlOperand {
  public static final VariantSqlOperand REGULAR = new VariantSqlOperand(Type.REGULAR);
  public static final VariantSqlOperand OPTIONAL = new VariantSqlOperand(Type.OPTIONAL);
  public static final VariantSqlOperand VARIADIC = new VariantSqlOperand(Type.VARIADIC);

  private VariantSqlOperand(Type type) {
    super(type);
  }

  @Override
  public boolean accepts(RelDataType relDataType, SqlCallBinding sqlCallBinding) {
    return relDataType.equals(VariantNullableRelDataType.INSTANCE)
        || relDataType.equals(VariantNotNullRelDataType.INSTANCE);
  }
}
