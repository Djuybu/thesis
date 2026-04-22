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
package com.dremio.exec.store.iceberg;

import static com.dremio.exec.store.iceberg.IcebergUtils.LATEST_SUPPORTED_ICEBERG_FORMAT_VERSION;

import com.google.common.base.Preconditions;
import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableProperties;

/**
 * A central utility class for determining whether the current Dremio engine supports reading and
 * writing specific Iceberg features. Currently only support checks for format version
 */
public class IcebergFeatureManager {
  public enum IcebergFormatVersion {
    V1(1),
    V2(2),
    V3(3);

    private final int value;

    IcebergFormatVersion(int value) {
      this.value = value;
    }

    public int getValue() {
      return value;
    }

    private static final Map<Integer, IcebergFormatVersion> valueToEnum = new HashMap<>();

    static {
      for (IcebergFormatVersion v : values()) {
        valueToEnum.put(v.value, v);
      }
    }

    public static IcebergFormatVersion fromValue(int value) {
      return valueToEnum.get(value);
    }

    public static IcebergFormatVersion getLatestSupportedVersion() {
      return valueToEnum.get(LATEST_SUPPORTED_ICEBERG_FORMAT_VERSION);
    }
  }

  public static IcebergFormatVersion getIcebergFormatVersion(TableMetadata tableMetadata) {
    Preconditions.checkArgument(tableMetadata != null);
    return IcebergFormatVersion.fromValue(tableMetadata.formatVersion());
  }

  public static IcebergFormatVersion getIcebergFormatVersion(Map<String, String> tableProp) {
    Preconditions.checkArgument(tableProp != null);
    String formatVersionString = tableProp.get(TableProperties.FORMAT_VERSION);
    if (formatVersionString != null) {
      return IcebergFormatVersion.fromValue(Integer.parseInt(formatVersionString));
    }
    return IcebergFormatVersion.getLatestSupportedVersion();
  }
}
