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
package com.dremio.exec.catalog;

import com.dremio.catalog.model.CatalogEntityKey;
import com.dremio.catalog.model.VersionContext;
import com.dremio.common.Wrapper;
import com.dremio.exec.store.SchemaConfig;
import com.dremio.exec.store.sys.udf.UserDefinedFunction;
import com.dremio.service.namespace.function.proto.FunctionConfig;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface FunctionManagingPlugin extends Wrapper {
  Optional<FunctionConfig> getFunction(CatalogEntityKey functionKey);

  List<FunctionConfig> getFunctions(VersionContext versionContext);

  boolean createFunction(
      CatalogEntityKey key, SchemaConfig schemaConfig, UserDefinedFunction userDefinedFunction)
      throws IOException;

  boolean updateFunction(
      CatalogEntityKey key, SchemaConfig schemaConfig, UserDefinedFunction userDefinedFunction)
      throws IOException;

  void dropFunction(CatalogEntityKey key, SchemaConfig schemaConfig) throws IOException;
}
