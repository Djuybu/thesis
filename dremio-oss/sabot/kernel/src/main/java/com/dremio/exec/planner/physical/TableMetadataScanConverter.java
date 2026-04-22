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
package com.dremio.exec.planner.physical;

import com.dremio.exec.ops.OptimizerRulesContext;
import com.dremio.exec.planner.logical.Rel;
import com.dremio.exec.planner.logical.drel.TableMetadataScan;
import com.dremio.exec.store.iceberg.IcebergManifestColumnMetadataPlanBuilder;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterRule;

/**
 * Converts a {@link TableMetadataScan} operator into its physical scan implementation. Currently,
 * it only handles conversion to an Iceberg scan.
 */
public class TableMetadataScanConverter extends ConverterRule {

  private final OptimizerRulesContext context;

  public TableMetadataScanConverter(OptimizerRulesContext context) {
    super(TableMetadataScan.class, Rel.LOGICAL, Prel.PHYSICAL, "TableMetadataScanConverter");
    this.context = context;
  }

  @Override
  public RelNode convert(RelNode rel) {
    TableMetadataScan tableMetadataScan = (TableMetadataScan) rel;
    return new IcebergManifestColumnMetadataPlanBuilder(tableMetadataScan, context)
        .buildColumnMetadataReaderPlan();
  }
}
