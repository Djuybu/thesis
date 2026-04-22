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

import com.google.protobuf.ByteString;
import java.util.List;

public final class SessionOptionValue implements SessionOptionValueOrBuilder {
  private int optionValueCase_ = 0;
  private java.lang.Object optionValue_;

  @Override
  public boolean hasStringValue() {
    return optionValueCase_ == 1;
  }

  @Override
  public String getStringValue() {
    if (optionValueCase_ == 1) {
      return (String) optionValue_;
    }
    return null;
  }

  @Override
  public ByteString getStringValueBytes() {
    if (optionValueCase_ == 1) {
      return (ByteString) optionValue_;
    }
    return null;
  }

  @Override
  public boolean hasBoolValue() {
    return optionValueCase_ == 2;
  }

  @Override
  public boolean getBoolValue() {
    if (optionValueCase_ == 2) {
      return (boolean) optionValue_;
    }
    return false;
  }

  @Override
  public boolean hasInt32Value() {
    return optionValueCase_ == 3;
  }

  @Override
  public int getInt32Value() {
    if (optionValueCase_ == 3) {
      return (int) optionValue_;
    }
    return 0;
  }

  @Override
  public boolean hasInt64Value() {
    return optionValueCase_ == 4;
  }

  @Override
  public long getInt64Value() {
    if (optionValueCase_ == 4) {
      return (long) optionValue_;
    }
    return 0L;
  }

  @Override
  public boolean hasFloatValue() {
    return optionValueCase_ == 5;
  }

  @Override
  public float getFloatValue() {
    if (optionValueCase_ == 5) {
      return (float) optionValue_;
    }
    return 0F;
  }

  @Override
  public boolean hasDoubleValue() {
    return optionValueCase_ == 6;
  }

  @Override
  public double getDoubleValue() {
    if (optionValueCase_ == 6) {
      return (double) optionValue_;
    }
    return 0D;
  }

  @Override
  public boolean hasStringListValue() {
    return optionValueCase_ == 7;
  }

  @Override
  public List<String> getStringListValue() {
    if (optionValueCase_ == 7) {
      return (List<String>) optionValue_;
    }
    return null;
  }

  @Override
  public OptionValueCase getOptionValueCase() {
    return OptionValueCase.forNumber(optionValueCase_);
  }

  public static class Builder {
    private int optionValueCase_;
    private Object optionValue_;

    public static Builder newBuilder() {
      return new Builder();
    }

    public SessionOptionValue build() {
      SessionOptionValue result = new SessionOptionValue();

      if (optionValueCase_ == 1) {
        result.optionValue_ = optionValue_;
      }
      if (optionValueCase_ == 2) {
        result.optionValue_ = optionValue_;
      }
      if (optionValueCase_ == 3) {
        result.optionValue_ = optionValue_;
      }
      if (optionValueCase_ == 4) {
        result.optionValue_ = optionValue_;
      }
      if (optionValueCase_ == 5) {
        result.optionValue_ = optionValue_;
      }
      if (optionValueCase_ == 6) {
        result.optionValue_ = optionValue_;
      }
      if (optionValueCase_ == 7) {
        result.optionValue_ = optionValue_;
      }
      result.optionValueCase_ = optionValueCase_;
      return result;
    }

    Builder() {
      optionValueCase_ = 0;
    }

    /**
     * <code>string string_value = 1;</code>
     *
     * @param value The stringValue to set.
     * @return This builder for chaining.
     */
    public Builder setStringValue(String value) {
      if (value == null) {
        throw new NullPointerException();
      }
      optionValueCase_ = 1;
      optionValue_ = value;
      return this;
    }

    /**
     * <code>bool bool_value = 2;</code>
     *
     * @param value The boolValue to set.
     * @return This builder for chaining.
     */
    public Builder setBoolValue(boolean value) {
      optionValueCase_ = 2;
      optionValue_ = value;
      return this;
    }

    /**
     * <code>sfixed32 int32_value = 3;</code>
     *
     * @param value The int32Value to set.
     * @return This builder for chaining.
     */
    public Builder setInt32Value(int value) {
      optionValueCase_ = 3;
      optionValue_ = value;
      return this;
    }

    /**
     * <code>sfixed64 int64_value = 4;</code>
     *
     * @param value The int64Value to set.
     * @return This builder for chaining.
     */
    public Builder setInt64Value(long value) {
      optionValueCase_ = 4;
      optionValue_ = value;
      return this;
    }

    /**
     * <code>float float_value = 5;</code>
     *
     * @param value The floatValue to set.
     * @return This builder for chaining.
     */
    public Builder setFloatValue(float value) {
      optionValueCase_ = 5;
      optionValue_ = value;
      return this;
    }

    /**
     * <code>double double_value = 6;</code>
     *
     * @param value The doubleValue to set.
     * @return This builder for chaining.
     */
    public Builder setDoubleValue(double value) {
      optionValueCase_ = 6;
      optionValue_ = value;
      return this;
    }

    public Builder setStringListValue(List<String> value) {
      optionValueCase_ = 7;
      optionValue_ = value;
      return this;
    }
  }

  protected SessionOptionValue() {}
}
