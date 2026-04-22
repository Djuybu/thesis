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

import java.util.Collection;
import org.apache.calcite.sql.type.SqlTypeName;

/** Static class to store singletons for commonly used SqlOperand instances. */
public final class SqlOperands {
  // Single Types
  public static final SqlOperand ANY = regular(SqlTypeName.ANY);
  public static final SqlOperand ANY_OPTIONAL = optional(SqlTypeName.ANY);
  public static final SqlOperand ANY_VARIADIC = variadic(SqlTypeName.ANY);

  public static final SqlOperand BOOLEAN = regular(SqlTypeName.BOOLEAN);
  public static final SqlOperand BOOLEAN_OPTIONAL = optional(SqlTypeName.BOOLEAN);
  public static final SqlOperand BOOLEAN_VARIADIC = variadic(SqlTypeName.BOOLEAN);

  public static final SqlOperand TINYINT = regular(SqlTypeName.TINYINT);
  public static final SqlOperand TINYINT_OPTIONAL = optional(SqlTypeName.TINYINT);
  public static final SqlOperand TINYINT_VARIADIC = variadic(SqlTypeName.TINYINT);

  public static final SqlOperand SMALLINT = regular(SqlTypeName.SMALLINT);
  public static final SqlOperand SMALLINT_OPTIONAL = optional(SqlTypeName.SMALLINT);
  public static final SqlOperand SMALLINT_VARIADIC = variadic(SqlTypeName.SMALLINT);

  public static final SqlOperand INTEGER = regular(SqlTypeName.INTEGER);
  public static final SqlOperand INTEGER_OPTIONAL = optional(SqlTypeName.INTEGER);
  public static final SqlOperand INTEGER_VARIADIC = variadic(SqlTypeName.INTEGER);

  public static final SqlOperand BIGINT = regular(SqlTypeName.BIGINT);
  public static final SqlOperand BIGINT_OPTIONAL = optional(SqlTypeName.BIGINT);
  public static final SqlOperand BIGINT_VARIADIC = variadic(SqlTypeName.BIGINT);

  public static final SqlOperand DECIMAL = regular(SqlTypeName.DECIMAL);
  public static final SqlOperand DECIMAL_OPTIONAL = optional(SqlTypeName.DECIMAL);
  public static final SqlOperand DECIMAL_VARIADIC = variadic(SqlTypeName.DECIMAL);

  public static final SqlOperand FLOAT = regular(SqlTypeName.FLOAT);
  public static final SqlOperand FLOAT_OPTIONAL = optional(SqlTypeName.FLOAT);
  public static final SqlOperand FLOAT_VARIADIC = variadic(SqlTypeName.FLOAT);

  public static final SqlOperand REAL = regular(SqlTypeName.REAL);
  public static final SqlOperand REAL_OPTIONAL = optional(SqlTypeName.REAL);
  public static final SqlOperand REAL_VARIADIC = variadic(SqlTypeName.REAL);

  public static final SqlOperand DOUBLE = regular(SqlTypeName.DOUBLE);
  public static final SqlOperand DOUBLE_OPTIONAL = optional(SqlTypeName.DOUBLE);
  public static final SqlOperand DOUBLE_VARIADIC = variadic(SqlTypeName.DOUBLE);

  public static final SqlOperand CHAR = regular(SqlTypeName.CHAR);
  public static final SqlOperand CHAR_OPTIONAL = optional(SqlTypeName.CHAR);
  public static final SqlOperand CHAR_VARIADIC = variadic(SqlTypeName.CHAR);

  public static final SqlOperand VARCHAR = regular(SqlTypeName.VARCHAR);
  public static final SqlOperand VARCHAR_OPTIONAL = optional(SqlTypeName.VARCHAR);
  public static final SqlOperand VARCHAR_VARIADIC = variadic(SqlTypeName.VARCHAR);

  public static final SqlOperand BINARY = regular(SqlTypeName.BINARY);
  public static final SqlOperand BINARY_OPTIONAL = optional(SqlTypeName.BINARY);
  public static final SqlOperand BINARY_VARIADIC = variadic(SqlTypeName.BINARY);

  public static final SqlOperand VARBINARY = regular(SqlTypeName.VARBINARY);
  public static final SqlOperand VARBINARY_OPTIONAL = optional(SqlTypeName.VARBINARY);
  public static final SqlOperand VARBINARY_VARIADIC = variadic(SqlTypeName.VARBINARY);

  public static final SqlOperand DATE = regular(SqlTypeName.DATE);
  public static final SqlOperand DATE_OPTIONAL = optional(SqlTypeName.DATE);
  public static final SqlOperand DATE_VARIADIC = variadic(SqlTypeName.DATE);

  public static final SqlOperand TIME = regular(SqlTypeName.TIME);
  public static final SqlOperand TIME_OPTIONAL = optional(SqlTypeName.TIME);
  public static final SqlOperand TIME_VARIADIC = variadic(SqlTypeName.TIME);

  public static final SqlOperand TIMESTAMP = regular(SqlTypeName.TIMESTAMP);
  public static final SqlOperand TIMESTAMP_OPTIONAL = optional(SqlTypeName.TIMESTAMP);
  public static final SqlOperand TIMESTAMP_VARIADIC = variadic(SqlTypeName.TIMESTAMP);

  public static final SqlOperand INTERVAL_YEAR_MONTH = regular(SqlTypeName.INTERVAL_YEAR_MONTH);
  public static final SqlOperand INTERVAL_YEAR_MONTH_OPTIONAL =
      optional(SqlTypeName.INTERVAL_YEAR_MONTH);
  public static final SqlOperand INTERVAL_YEAR_MONTH_VARIADIC =
      variadic(SqlTypeName.INTERVAL_YEAR_MONTH);

  public static final SqlOperand INTERVAL_DAY_HOUR = regular(SqlTypeName.INTERVAL_DAY_HOUR);
  public static final SqlOperand INTERVAL_DAY_TIME_OPTIONAL =
      optional(SqlTypeName.INTERVAL_DAY_HOUR);
  public static final SqlOperand INTERVAL_DAY_TIME_VARIADIC =
      variadic(SqlTypeName.INTERVAL_DAY_HOUR);

  public static final SqlOperand ARRAY = regular(SqlTypeName.ARRAY);
  public static final SqlOperand ARRAY_OPTIONAL = optional(SqlTypeName.ARRAY);
  public static final SqlOperand ARRAY_VARIADIC = variadic(SqlTypeName.ARRAY);

  public static final SqlOperand MAP = regular(SqlTypeName.MAP);
  public static final SqlOperand MAP_OPTIONAL = optional(SqlTypeName.MAP);
  public static final SqlOperand MAP_VARIADIC = variadic(SqlTypeName.MAP);

  public static final SqlOperand MULTISET = regular(SqlTypeName.MULTISET);
  public static final SqlOperand MULTISET_OPTIONAL = optional(SqlTypeName.MULTISET);
  public static final SqlOperand MULTISET_VARIADIC = variadic(SqlTypeName.MULTISET);

  public static final SqlOperand ROW = regular(SqlTypeName.ROW);
  public static final SqlOperand ROW_OPTIONAL = optional(SqlTypeName.ROW);
  public static final SqlOperand ROW_VARIADIC = variadic(SqlTypeName.ROW);

  public static final SqlOperand DISTINCT = regular(SqlTypeName.DISTINCT);
  public static final SqlOperand DISTINCT_OPTIONAL = optional(SqlTypeName.DISTINCT);
  public static final SqlOperand DISTINCT_VARIADIC = variadic(SqlTypeName.DISTINCT);

  public static final SqlOperand STRUCTURED = regular(SqlTypeName.STRUCTURED);
  public static final SqlOperand STRUCTURED_OPTIONAL = optional(SqlTypeName.STRUCTURED);
  public static final SqlOperand STRUCTURED_VARIADIC = variadic(SqlTypeName.STRUCTURED);

  public static final SqlOperand NULL = regular(SqlTypeName.NULL);
  public static final SqlOperand NULL_OPTIONAL = optional(SqlTypeName.NULL);
  public static final SqlOperand NULL_VARIADIC = variadic(SqlTypeName.NULL);

  // Group Types
  public static final SqlOperand ALL_TYPES = regular(SqlTypeName.ALL_TYPES);
  public static final SqlOperand ALL_TYPES_OPTIONAL = optional(SqlTypeName.ALL_TYPES);
  public static final SqlOperand ALL_TYPES_VARIADIC = variadic(SqlTypeName.ALL_TYPES);

  public static final SqlOperand BOOLEAN_TYPES = regular(SqlTypeName.BOOLEAN_TYPES);
  public static final SqlOperand BOOLEAN_TYPES_OPTIONAL = optional(SqlTypeName.BOOLEAN_TYPES);
  public static final SqlOperand BOOLEAN_TYPES_VARIADIC = variadic(SqlTypeName.BOOLEAN_TYPES);

  public static final SqlOperand BINARY_TYPES = regular(SqlTypeName.BINARY_TYPES);
  public static final SqlOperand BINARY_TYPES_OPTIONAL = optional(SqlTypeName.BINARY_TYPES);
  public static final SqlOperand BINARY_TYPES_VARIADIC = variadic(SqlTypeName.BINARY_TYPES);

  public static final SqlOperand INT_TYPES = regular(SqlTypeName.INT_TYPES);
  public static final SqlOperand INT_TYPES_OPTIONAL = optional(SqlTypeName.INT_TYPES);
  public static final SqlOperand INT_TYPES_VARIADIC = variadic(SqlTypeName.INT_TYPES);

  public static final SqlOperand EXACT_TYPES = regular(SqlTypeName.EXACT_TYPES);
  public static final SqlOperand EXACT_TYPES_OPTIONAL = optional(SqlTypeName.EXACT_TYPES);
  public static final SqlOperand EXACT_TYPES_VARIADIC = variadic(SqlTypeName.EXACT_TYPES);

  public static final SqlOperand APPROX_TYPES = regular(SqlTypeName.APPROX_TYPES);
  public static final SqlOperand APPROX_TYPES_OPTIONAL = optional(SqlTypeName.APPROX_TYPES);
  public static final SqlOperand APPROX_TYPES_VARIADIC = variadic(SqlTypeName.APPROX_TYPES);

  public static final SqlOperand NUMERIC_TYPES = regular(SqlTypeName.NUMERIC_TYPES);
  public static final SqlOperand NUMERIC_TYPES_OPTIONAL = optional(SqlTypeName.NUMERIC_TYPES);
  public static final SqlOperand NUMERIC_TYPES_VARIADIC = variadic(SqlTypeName.NUMERIC_TYPES);

  public static final SqlOperand FRACTIONAL_TYPES = regular(SqlTypeName.FRACTIONAL_TYPES);
  public static final SqlOperand FRACTIONAL_TYPES_OPTIONAL = optional(SqlTypeName.FRACTIONAL_TYPES);
  public static final SqlOperand FRACTIONAL_TYPES_VARIADIC = variadic(SqlTypeName.FRACTIONAL_TYPES);

  public static final SqlOperand CHAR_TYPES = regular(SqlTypeName.CHAR_TYPES);
  public static final SqlOperand CHAR_TYPES_OPTIONAL = optional(SqlTypeName.CHAR_TYPES);
  public static final SqlOperand CHAR_TYPES_VARIADIC = variadic(SqlTypeName.CHAR_TYPES);

  public static final SqlOperand STRING_TYPES = regular(SqlTypeName.STRING_TYPES);
  public static final SqlOperand STRING_TYPES_OPTIONAL = optional(SqlTypeName.STRING_TYPES);
  public static final SqlOperand STRING_TYPES_VARIADIC = variadic(SqlTypeName.STRING_TYPES);

  public static final SqlOperand DATETIME_TYPES = regular(SqlTypeName.DATETIME_TYPES);
  public static final SqlOperand DATETIME_TYPES_OPTIONAL = optional(SqlTypeName.DATETIME_TYPES);
  public static final SqlOperand DATETIME_TYPES_VARIADIC = variadic(SqlTypeName.DATETIME_TYPES);

  public static final SqlOperand YEAR_INTERVAL_TYPES = regular(SqlTypeName.YEAR_INTERVAL_TYPES);
  public static final SqlOperand YEAR_INTERVAL_TYPES_OPTIONAL =
      optional(SqlTypeName.YEAR_INTERVAL_TYPES);
  public static final SqlOperand YEAR_INTERVAL_TYPES_VARIADIC =
      variadic(SqlTypeName.YEAR_INTERVAL_TYPES);

  public static final SqlOperand DAY_INTERVAL_TYPES = regular(SqlTypeName.DAY_INTERVAL_TYPES);
  public static final SqlOperand DAY_INTERVAL_TYPES_OPTIONAL =
      optional(SqlTypeName.DAY_INTERVAL_TYPES);
  public static final SqlOperand DAY_INTERVAL_TYPES_VARIADIC =
      variadic(SqlTypeName.DAY_INTERVAL_TYPES);

  public static final SqlOperand INTERVAL_TYPES = regular(SqlTypeName.INTERVAL_TYPES);
  public static final SqlOperand INTERVAL_TYPES_OPTIONAL = optional(SqlTypeName.INTERVAL_TYPES);
  public static final SqlOperand INTERVAL_TYPES_VARIADIC = variadic(SqlTypeName.INTERVAL_TYPES);

  public static final SqlOperand VARIANT = VariantSqlOperand.REGULAR;
  public static final SqlOperand VARIANT_OPTIONAL = VariantSqlOperand.OPTIONAL;
  public static final SqlOperand VARIANT_VARIADIC = VariantSqlOperand.VARIADIC;

  private static SqlOperand regular(SqlTypeName sqlTypeName) {
    return SqlTypeNameSqlOperand.regular(sqlTypeName);
  }

  private static SqlOperand optional(SqlTypeName sqlTypeName) {
    return SqlTypeNameSqlOperand.optional(sqlTypeName);
  }

  private static SqlOperand variadic(SqlTypeName sqlTypeName) {
    return SqlTypeNameSqlOperand.variadic(sqlTypeName);
  }

  private static SqlOperand regular(Collection<SqlTypeName> sqlTypeName) {
    return SqlTypeRangeOperand.regular(sqlTypeName);
  }

  private static SqlOperand optional(Collection<SqlTypeName> sqlTypeName) {
    return SqlTypeRangeOperand.optional(sqlTypeName);
  }

  private static SqlOperand variadic(Collection<SqlTypeName> sqlTypeName) {
    return SqlTypeRangeOperand.variadic(sqlTypeName);
  }

  private SqlOperands() {
    // Utility Class
  }
}
