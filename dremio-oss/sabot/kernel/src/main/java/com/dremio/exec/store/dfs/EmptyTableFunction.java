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

import com.dremio.exec.physical.config.TableFunctionConfig;
import com.dremio.exec.record.VectorAccessible;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.fragment.OutOfBandMessage;

/** Empty implementation for table functions */
public class EmptyTableFunction extends AbstractTableFunction {

  public EmptyTableFunction(OperatorContext context, TableFunctionConfig functionConfig) {
    super(context, functionConfig);
  }

  @Override
  public VectorAccessible setup(VectorAccessible accessible) throws Exception {
    return super.setup(accessible);
  }

  @Override
  public void startBatch(int records) throws Exception {
    super.startBatch(records);
  }

  @Override
  public void startRow(int row) throws Exception {}

  @Override
  public int processRow(int startOutIndex, int maxRecords) throws Exception {
    return 0;
  }

  @Override
  public void closeRow() throws Exception {}

  @Override
  public boolean hasBufferedRemaining() {
    return super.hasBufferedRemaining();
  }

  @Override
  public void workOnOOB(OutOfBandMessage message) {
    super.workOnOOB(message);
  }

  @Override
  public long getFirstRowSize() {
    return super.getFirstRowSize();
  }

  @Override
  public void noMoreToConsume() throws Exception {
    super.noMoreToConsume();
  }
}
