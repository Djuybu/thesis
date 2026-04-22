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
package com.dremio.exec.planner.physical.visitor;

import static com.dremio.exec.planner.ResultWriterUtils.buildCreateTableEntryForResults;

import com.dremio.exec.planner.logical.CreateTableEntry;
import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.physical.WriterCommitterPrel;
import com.dremio.exec.planner.physical.WriterPrel;
import com.dremio.exec.planner.sql.handlers.SqlHandlerConfig;
import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.calcite.rel.RelNode;

public final class WriterPathUpdater {

  private WriterPathUpdater() {}

  public static Prel update(Prel prel, SqlHandlerConfig config) {
    return prel.accept(new Visitor(config), null);
  }

  private static class Visitor extends BasePrelVisitor<Prel, CreateTableEntry, RuntimeException> {

    private final SqlHandlerConfig sqlConfig;

    private Visitor(SqlHandlerConfig config) {
      sqlConfig = config;
    }

    @Override
    public Prel visitWriterCommitter(WriterCommitterPrel prel, CreateTableEntry tableEntry)
        throws RuntimeException {
      Preconditions.checkArgument(null == tableEntry);

      // Rebuild the CreateTableEntry.  Assume it is for results.
      final CreateTableEntry createTableEntry = buildCreateTableEntryForResults(sqlConfig);

      Prel input = ((Prel) prel.getInput(0)).accept(this, createTableEntry);

      WriterCommitterPrel committerPrel =
          new WriterCommitterPrel(
              prel.getCluster(),
              prel.getTraitSet(),
              input,
              createTableEntry.getPlugin(),
              null,
              createTableEntry.getLocation(),
              createTableEntry.getUserName(),
              createTableEntry,
              Optional.empty(),
              prel.isPartialRefresh(),
              prel.isReadSignatureEnabled(),
              null);
      return committerPrel;
    }

    @Override
    public Prel visitPrel(Prel prel, CreateTableEntry tableEntry) throws RuntimeException {

      List<RelNode> newInputs = new ArrayList<>();
      for (Prel input : prel) {
        newInputs.add(input.accept(this, tableEntry));
      }

      return (Prel) prel.copy(prel.getTraitSet(), newInputs);
    }

    @Override
    public Prel visitWriter(WriterPrel prel, CreateTableEntry tableEntry) throws RuntimeException {
      return new WriterPrel(
          prel.getCluster(),
          prel.getTraitSet(),
          prel.getInput(),
          tableEntry,
          prel.getExpectedInboundRowType());
    }
  }
}
