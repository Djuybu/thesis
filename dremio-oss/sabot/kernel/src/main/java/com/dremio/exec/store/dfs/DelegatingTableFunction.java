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
package com.dremio.exec.store.dfs;

import com.dremio.exec.physical.base.OpProps;
import com.dremio.exec.physical.config.TableFunctionConfig;
import com.dremio.exec.record.VectorAccessible;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.fragment.FragmentExecutionContext;
import com.dremio.sabot.exec.fragment.OutOfBandMessage;
import com.dremio.sabot.op.tablefunction.TableFunction;

/** Empty implementation for table functions */
public class DelegatingTableFunction implements TableFunction {
  protected final TableFunction tableFunction;

  public DelegatingTableFunction(TableFunction tableFunction) {
    this.tableFunction = tableFunction;
  }

  protected static TableFunction getTableFunction(
      FragmentExecutionContext fec,
      OperatorContext context,
      OpProps props,
      TableFunctionConfig functionConfig) {
    return context.getTableFunctionImpl(fec, props, functionConfig);
  }

  @Override
  public VectorAccessible setup(VectorAccessible accessible) throws Exception {
    return tableFunction.setup(accessible);
  }

  @Override
  public void startBatch(int records) throws Exception {
    tableFunction.startBatch(records);
  }

  @Override
  public void startRow(int row) throws Exception {
    tableFunction.startRow(row);
  }

  @Override
  public int processRow(int startOutIndex, int maxRecords) throws Exception {
    return tableFunction.processRow(startOutIndex, maxRecords);
  }

  @Override
  public void closeRow() throws Exception {
    tableFunction.closeRow();
  }

  @Override
  public boolean hasBufferedRemaining() {
    return tableFunction.hasBufferedRemaining();
  }

  @Override
  public void workOnOOB(OutOfBandMessage message) {
    tableFunction.workOnOOB(message);
  }

  @Override
  public long getFirstRowSize() {
    return tableFunction.getFirstRowSize();
  }

  @Override
  public void noMoreToConsume() throws Exception {
    tableFunction.noMoreToConsume();
  }

  @Override
  public void close() throws Exception {
    tableFunction.close();
  }
}
