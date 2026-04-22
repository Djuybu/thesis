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
package com.dremio.exec.planner.plancache;

import com.google.common.base.Suppliers;
import com.google.common.hash.HashCode;
import java.util.function.Supplier;

public class PlanCacheKey {
  private final Supplier<String> stringHash;
  private final Supplier<byte[]> bytesHash;
  private final Supplier<String> materializationStringHash;

  public PlanCacheKey(HashCode hashCode, HashCode materializationHashCode) {
    this.bytesHash = Suppliers.memoize(hashCode::asBytes);
    this.stringHash = Suppliers.memoize(hashCode::toString);
    this.materializationStringHash = Suppliers.memoize(materializationHashCode::toString);
  }

  public String getStringHash() {
    return stringHash.get();
  }

  public byte[] getBytesHash() {
    return bytesHash.get();
  }

  public String getMaterializationHashString() {
    return materializationStringHash.get();
  }
}
