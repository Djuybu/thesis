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
package com.dremio.exec.catalog.lineage;

import com.dremio.service.namespace.dataset.proto.FieldOrigin;
import com.dremio.service.namespace.dataset.proto.ParentDataset;
import java.util.List;
import org.apache.calcite.rel.type.RelDataType;
import org.immutables.value.Value;

@Value.Immutable
public interface TableLineage {
  RelDataType getFields();

  List<ParentDataset> getUpstreams();

  List<FieldOrigin> getFieldUpstreams();
}
