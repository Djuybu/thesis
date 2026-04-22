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

import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.util.ReflectiveSqlOperatorTable;

/** Operator table for test only functions */
public class TestOnlySqlOperatorTable extends ReflectiveSqlOperatorTable {
  private static TestOnlySqlOperatorTable instance;

  private TestOnlySqlOperatorTable() {}

  public static final SqlOperator NO_ARG_RETURNS_VARIANT =
      SqlOperatorBuilder.name("NO_ARG_RETURNS_VARIANT")
          .returnType(DremioReturnTypes.VARIANT_NULLABLE)
          .noOperands()
          .build();

  public static final SqlOperator TAKES_VARIANT =
      SqlOperatorBuilder.name("TAKES_VARIANT")
          .returnType(DremioReturnTypes.VARIANT_NULLABLE)
          .operandTypes(SqlOperands.VARIANT)
          .build();

  public static final SqlOperator RETURNS_VARIANT =
      SqlOperatorBuilder.name("RETURNS_VARIANT")
          .returnType(DremioReturnTypes.VARIANT_NULLABLE)
          .operandTypes(SqlOperands.ANY)
          .build();

  public static final SqlOperator RETURNS_VARIANT_NEVER_NULL =
      SqlOperatorBuilder.name("RETURNS_VARIANT_NEVER_NULL")
          .returnType(DremioReturnTypes.VARIANT_NEVER_NULL)
          .operandTypes(SqlOperands.ANY)
          .build();

  public static final SqlOperator RETURNS_VARIANT_NULL_IF_NULL =
      SqlOperatorBuilder.name("RETURNS_VARIANT_NULL_IF_NULL")
          .returnType(DremioReturnTypes.VARIANT_NULL_IF_NULL)
          .operandTypes(SqlOperands.ANY)
          .build();

  public static final SqlOperator TAKES_AND_RETURNS_VARIANT =
      SqlOperatorBuilder.name("TAKES_AND_RETURNS_VARIANT")
          .returnType(DremioReturnTypes.VARIANT_NULLABLE)
          .operandTypes(SqlOperands.VARIANT)
          .build();

  /** Returns the standard operator table, creating it if necessary. */
  public static synchronized TestOnlySqlOperatorTable instance() {
    if (instance == null) {
      // Creates and initializes the standard operator table.
      // Uses two-phase construction, because we can't initialize the
      // table until the constructor of the sub-class has completed.
      instance = new TestOnlySqlOperatorTable();
      instance.init();
    }
    return instance;
  }
}
