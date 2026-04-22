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
import java.nio.charset.Charset;
import java.util.List;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeComparability;
import org.apache.calcite.rel.type.RelDataTypeFamily;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rel.type.RelDataTypePrecedenceList;
import org.apache.calcite.rel.type.StructKind;
import org.apache.calcite.sql.SqlCollation;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlIntervalQualifier;
import org.apache.calcite.sql.type.SqlTypeName;

public class SingletonRelDataType implements RelDataType {

  private final String typeName;
  private final boolean nullable;

  protected SingletonRelDataType(String typeName, boolean nullable) {
    this.typeName = Preconditions.checkNotNull(typeName);
    this.nullable = nullable;
  }

  @Override
  public boolean isStruct() {
    return false;
  }

  @Override
  public List<RelDataTypeField> getFieldList() {
    return null;
  }

  @Override
  public List<String> getFieldNames() {
    return null;
  }

  @Override
  public int getFieldCount() {
    return 0;
  }

  @Override
  public StructKind getStructKind() {
    return null;
  }

  @Override
  public RelDataTypeField getField(String fieldName, boolean caseSensitive, boolean elideRecord) {
    return null;
  }

  @Override
  public boolean isNullable() {
    return nullable;
  }

  @Override
  public RelDataType getComponentType() {
    return null;
  }

  @Override
  public RelDataType getKeyType() {
    return null;
  }

  @Override
  public RelDataType getValueType() {
    return null;
  }

  @Override
  public Charset getCharset() {
    return null;
  }

  @Override
  public SqlCollation getCollation() {
    return null;
  }

  @Override
  public SqlIntervalQualifier getIntervalQualifier() {
    return null;
  }

  @Override
  public int getPrecision() {
    return 0;
  }

  @Override
  public int getScale() {
    return 0;
  }

  @Override
  public SqlTypeName getSqlTypeName() {
    return SqlTypeName.ANY;
  }

  @Override
  public SqlIdentifier getSqlIdentifier() {
    return null;
  }

  @Override
  public String toString() {
    return getFullTypeString();
  }

  @Override
  public String getFullTypeString() {
    return typeName + " " + (nullable ? "NULLABLE" : "NOT NULL");
  }

  @Override
  public RelDataTypeFamily getFamily() {
    return null;
  }

  @Override
  public RelDataTypePrecedenceList getPrecedenceList() {
    return null;
  }

  @Override
  public RelDataTypeComparability getComparability() {
    return null;
  }

  @Override
  public boolean isDynamicStruct() {
    return false;
  }
}
