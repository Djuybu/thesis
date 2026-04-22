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

public enum OptionValueCase {
  STRING_VALUE(1),
  BOOL_VALUE(2),
  INT32_VALUE(3),
  INT64_VALUE(4),
  FLOAT_VALUE(5),
  DOUBLE_VALUE(6),
  STRING_LIST_VALUE(7),
  OPTIONVALUE_NOT_SET(0);
  private final int value;

  private OptionValueCase(int value) {
    this.value = value;
  }

  public static OptionValueCase forNumber(int value) {
    switch (value) {
      case 1:
        return STRING_VALUE;
      case 2:
        return BOOL_VALUE;
      case 3:
        return INT32_VALUE;
      case 4:
        return INT64_VALUE;
      case 5:
        return FLOAT_VALUE;
      case 6:
        return DOUBLE_VALUE;
      case 7:
        return STRING_LIST_VALUE;
      case 0:
        return OPTIONVALUE_NOT_SET;
      default:
        return null;
    }
  }

  public int getNumber() {
    return this.value;
  }
};
