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
package com.dremio.sabot.rpc.user;

import java.util.List;

interface SessionOptionValueOrBuilder {
  boolean hasStringValue();

  /**
   * <code>string string_value = 1;</code>
   *
   * @return The stringValue.
   */
  java.lang.String getStringValue();

  /**
   * <code>string string_value = 1;</code>
   *
   * @return The bytes for stringValue.
   */
  com.google.protobuf.ByteString getStringValueBytes();

  /**
   * <code>bool bool_value = 2;</code>
   *
   * @return Whether the boolValue field is set.
   */
  boolean hasBoolValue();

  /**
   * <code>bool bool_value = 2;</code>
   *
   * @return The boolValue.
   */
  boolean getBoolValue();

  /**
   * <code>sfixed32 int32_value = 3;</code>
   *
   * @return Whether the int32Value field is set.
   */
  boolean hasInt32Value();

  /**
   * <code>sfixed32 int32_value = 3;</code>
   *
   * @return The int32Value.
   */
  int getInt32Value();

  /**
   * <code>sfixed64 int64_value = 4;</code>
   *
   * @return Whether the int64Value field is set.
   */
  boolean hasInt64Value();

  /**
   * <code>sfixed64 int64_value = 4;</code>
   *
   * @return The int64Value.
   */
  long getInt64Value();

  /**
   * <code>float float_value = 5;</code>
   *
   * @return Whether the floatValue field is set.
   */
  boolean hasFloatValue();

  /**
   * <code>float float_value = 5;</code>
   *
   * @return The floatValue.
   */
  float getFloatValue();

  /**
   * <code>double double_value = 6;</code>
   *
   * @return Whether the doubleValue field is set.
   */
  boolean hasDoubleValue();

  /**
   * <code>double double_value = 6;</code>
   *
   * @return The doubleValue.
   */
  double getDoubleValue();

  /**
   * <code>.exec.user.SessionOptionValue.StringListValue string_list_value = 7;</code>
   *
   * @return Whether the stringListValue field is set.
   */
  boolean hasStringListValue();

  /**
   * <code>.exec.user.SessionOptionValue.StringListValue string_list_value = 7;</code>
   *
   * @return The stringListValue.
   */
  List<String> getStringListValue();

  /** <code>.exec.user.SessionOptionValue.StringListValue string_list_value = 7;</code> */
  OptionValueCase getOptionValueCase();
}
