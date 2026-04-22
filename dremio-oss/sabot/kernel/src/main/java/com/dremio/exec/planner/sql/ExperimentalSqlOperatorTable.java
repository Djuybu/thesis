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
import com.google.common.collect.ImmutableList;
import java.util.List;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.SqlOperandCountRange;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlOperandCountRanges;
import org.apache.calcite.sql.type.SqlOperandTypeChecker;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.util.ReflectiveSqlOperatorTable;

/** Operator table for test only functions */
public class ExperimentalSqlOperatorTable extends ReflectiveSqlOperatorTable {
  private static ExperimentalSqlOperatorTable instance;

  private ExperimentalSqlOperatorTable() {}

  private static final SqlOperandTypeChecker COMPARABLE_ARRAY_TYPE_CHECKER =
      new SqlOperandTypeChecker() {
        @Override
        public boolean checkOperandTypes(SqlCallBinding callBinding, boolean throwOnFailure) {
          RelDataType operandType = callBinding.getOperandType(0);
          if (operandType.getSqlTypeName() != SqlTypeName.ARRAY) {
            if (throwOnFailure) {
              throw UserException.validationError()
                  .message("Expected argument to be an ARRAY.")
                  .build();
            }

            return false;
          }

          List<SqlTypeName> comparableTypes =
              new ImmutableList.Builder<SqlTypeName>()
                  .addAll(SqlTypeName.BOOLEAN_TYPES)
                  .addAll(SqlTypeName.NUMERIC_TYPES)
                  .addAll(SqlTypeName.CHAR_TYPES)
                  .addAll(SqlTypeName.DATETIME_TYPES)
                  .build();

          if (!comparableTypes.contains(operandType.getComponentType().getSqlTypeName())) {
            if (throwOnFailure) {
              throw UserException.validationError()
                  .message("Expected argument to be an ARRAY of comparable types.")
                  .build();
            }

            return false;
          }

          return true;
        }

        @Override
        public SqlOperandCountRange getOperandCountRange() {
          return SqlOperandCountRanges.of(1);
        }

        @Override
        public String getAllowedSignatures(SqlOperator op, String opName) {
          return null;
        }

        @Override
        public Consistency getConsistency() {
          return null;
        }

        @Override
        public boolean isOptional(int i) {
          return false;
        }
      };

  public static final SqlOperator ARRAY_SORT =
      SqlOperatorBuilder.name("ARRAY_SORT")
          .returnType(ReturnTypes.ARG0)
          .operandTypes(COMPARABLE_ARRAY_TYPE_CHECKER)
          .build();

  public static final SqlOperator TO_VARIANT =
      SqlOperatorBuilder.name("TO_VARIANT")
          .returnType(DremioReturnTypes.VARIANT_NULL_IF_NULL)
          .operandTypes(SqlOperands.ANY)
          .build();

  public static final SqlOperator PARSE_JSON =
      SqlOperatorBuilder.name("PARSE_JSON")
          .returnType(DremioReturnTypes.VARIANT_NULL_IF_NULL)
          .operandTypes(SqlOperands.VARCHAR)
          .build();

  public static final SqlOperator TRY_PARSE_JSON =
      SqlOperatorBuilder.name("TRY_PARSE_JSON")
          .returnType(DremioReturnTypes.VARIANT_NULLABLE)
          .operandTypes(SqlOperands.VARCHAR)
          .build();

  public static final SqlOperator TO_JSON =
      SqlOperatorBuilder.name("TO_JSON")
          .returnType(DremioReturnTypes.VARCHAR_MAX_PRECISION_NULLABLE)
          .operandTypes(SqlOperands.VARIANT)
          .build();

  /** Returns the standard operator table, creating it if necessary. */
  public static synchronized ExperimentalSqlOperatorTable instance() {
    if (instance == null) {
      // Creates and initializes the standard operator table.
      // Uses two-phase construction, because we can't initialize the
      // table until the constructor of the sub-class has completed.
      instance = new ExperimentalSqlOperatorTable();
      instance.init();
    }
    return instance;
  }
}
