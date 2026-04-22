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
package com.dremio.exec.planner.sql.parser;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.sql.SqlBasicTypeNameSpec;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.validate.SqlValidator;

/**
 * Extends SqlDataTypeSpec to support complex specs. Facilitates customized deriveType for complex
 * types
 */
public class SqlComplexDataTypeSpec extends SqlDataTypeSpec {

  public SqlComplexDataTypeSpec(SqlDataTypeSpec spec) {
    super(spec.getTypeNameSpec(), spec.getTimeZone(), spec.getNullable(), spec.getParserPosition());
  }

  @Override
  public RelDataType deriveType(SqlValidator validator) {
    if (this.getTypeName() instanceof SqlTypeNameSpec) {
      // Create type directly if this typeName is a SqlTypeNameSpec.
      return getDataTypeForComplex(validator);
    }
    return super.deriveType(validator); // DEFAULT
  }

  private RelDataType getDataTypeForComplex(SqlValidator validator) {
    RelDataType type = createTypeFromTypeNameSpec(validator, (SqlTypeNameSpec) this.getTypeName());
    if (type == null) {
      return null;
    }
    type =
        validator
            .getTypeFactory()
            .createTypeWithNullability(
                type, this.getNullable() == null ? true : this.getNullable());

    return type;
  }

  /**
   * Create type from the type name specification directly.
   *
   * @param validator SQL validator.
   * @return the type.
   */
  private RelDataType createTypeFromTypeNameSpec(
      SqlValidator validator, SqlTypeNameSpec typeNameSpec) {
    return typeNameSpec.deriveType(validator);
  }

  private int tryGetPrecision(SqlDataTypeSpec spec) {
    // Only SqlBasicTypeNameSpec has precision
    if (spec.getTypeNameSpec() instanceof SqlBasicTypeNameSpec) {
      return ((SqlBasicTypeNameSpec) spec.getTypeNameSpec()).getPrecision();
    }

    return -1;
  }

  private int tryGetScale(SqlDataTypeSpec spec) {
    // Only SqlBasicTypeNameSpec has scale
    if (spec.getTypeNameSpec() instanceof SqlBasicTypeNameSpec) {
      return ((SqlBasicTypeNameSpec) spec.getTypeNameSpec()).getScale();
    }

    return -1;
  }

  /**
   * Makes sure that the type inside the complex type is valid.
   *
   * @return the invalid SqlDataTypeSpec or null when the type is valid.
   */
  public SqlDataTypeSpec validateType() {
    if (this.getTypeName() instanceof ValidatableTypeNameSpec) {
      return ((ValidatableTypeNameSpec) this.getTypeName()).validateType();
    }
    return null;
  }
}
