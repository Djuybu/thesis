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
package com.dremio.exec.tablefunctions.clusteringinfo;

import com.dremio.exec.catalog.SimpleCatalog;
import com.dremio.service.namespace.NamespaceKey;
import java.util.List;
import org.apache.calcite.schema.FunctionParameter;
import org.apache.calcite.schema.TableMacro;
import org.apache.calcite.schema.TranslatableTable;
import org.apache.calcite.schema.impl.ReflectiveFunctionBase;

/**
 * {@link org.apache.calcite.schema.TableMacro} implementation entry point for
 * clustering_information table function.
 */
public class ClusteringInfoMacro implements TableMacro {
  private static final String MACRO_NAME = "clustering_information";
  private final SimpleCatalog<?> catalog;
  static final List<FunctionParameter> FUNCTION_PARAMETERS =
      new ReflectiveFunctionBase.ParameterListBuilder().add(String.class, "table_name").build();

  public ClusteringInfoMacro(SimpleCatalog<?> catalog) {
    this.catalog = catalog;
  }

  @Override
  public TranslatableTable apply(List<?> arguments) {
    ClusteringInfoContext context = new ClusteringInfoContext(catalog, (String) arguments.get(0));
    return new ClusteringInfoTranslatableTable(context);
  }

  @Override
  public List<FunctionParameter> getParameters() {
    return FUNCTION_PARAMETERS;
  }

  public static boolean isClusteringInformationFunction(NamespaceKey path) {
    return MACRO_NAME.equalsIgnoreCase(path.getLeaf());
  }
}
