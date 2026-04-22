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
package com.dremio.plugins.icebergcatalog.store;

import com.dremio.exec.proto.UserBitShared;
import com.dremio.exec.store.iceberg.model.IcebergBaseCommand;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.UpdateSchema;

public class IcebergCatalogCommand extends IcebergBaseCommand {
  private final String icebergPath;

  public IcebergCatalogCommand(
      Configuration configuration,
      String icebergPath,
      TableOperations tableOperations,
      UserBitShared.QueryId queryId) {
    super(configuration, icebergPath, tableOperations, queryId);

    this.icebergPath = icebergPath;
  }

  @Override
  /*
   * This method should return the iceberg table location.
   * E.g. abfss://<container>@<account-name>.dfs.core.windows.net/path/to/table
   *
   * This method is overridden as calling IcebergUtils.getValidIcebergPath() is unnecessary and
   * risky given incomplete hadoop configuration values for azure.
   */
  public String getTableLocation() {
    return icebergPath;
  }

  @Override
  public void updatePrimaryKey(List<Field> columns) {
    final Collection<String> columnNames =
        columns.stream().map(Field::getName).collect(Collectors.toList());
    final UpdateSchema updateSchema = loadTable().updateSchema().setIdentifierFields(columnNames);

    performNonTransactionCommit(updateSchema);
  }
}
