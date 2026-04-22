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

import static com.dremio.exec.planner.ResultWriterUtils.generateStoreTablePath;
import static com.dremio.exec.planner.physical.WriterCommitterPrel.LIMIT;
import static com.dremio.exec.planner.physical.WriterCommitterPrel.RESERVE;

import com.dremio.exec.physical.base.OpProps;
import com.dremio.exec.physical.base.PhysicalOperator;
import com.dremio.exec.physical.config.WriterCommitterPOP;
import com.dremio.exec.planner.logical.CreateTableEntry;
import com.dremio.exec.planner.physical.visitor.PrelVisitor;
import com.dremio.exec.planner.sql.handlers.SqlHandlerConfig;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.RecordWriter;
import com.dremio.service.namespace.NamespaceKey;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;

public class ResultWriterCommiterPrel extends SinglePrel {

  public ResultWriterCommiterPrel(RelOptCluster cluster, RelTraitSet traits, RelNode child) {
    super(cluster, traits, child);
  }

  @Override
  public ResultWriterCommiterPrel copy(RelTraitSet traitSet, List<RelNode> inputs) {
    return new ResultWriterCommiterPrel(getCluster(), traitSet, sole(inputs));
  }

  @Override
  public PhysicalOperator getPhysicalOperator(PhysicalPlanCreator creator) throws IOException {
    SqlHandlerConfig sqlHandlerConfig = creator.getSqlHandlerConfig();

    CreateTableEntry createTableEntry = creator.getCreateTableEntryForResults();
    OpProps props =
        creator.props(this, createTableEntry.getUserName(), RecordWriter.SCHEMA, RESERVE, LIMIT);
    NamespaceKey target = generateStoreTablePath(sqlHandlerConfig);
    return new WriterCommitterPOP(
        props,
        null, // Resolved to temp location
        createTableEntry.getLocation(), // target location
        null,
        createTableEntry.getDatasetPath(),
        Optional.empty(), // dataset config
        getChildPhysicalOperator(creator),
        createTableEntry.getPlugin(),
        null, // SourceTable Plugin
        false, // Partition Refresh
        false, // Read Signature Enabled
        createTableEntry.getOptions().getTableFormatOptions(),
        null, // SourceTablePluginId
        createTableEntry.getUserId());
  }

  @Override
  public Iterator<Prel> iterator() {
    return PrelUtil.iter(getInput());
  }

  @Override
  public <T, X, E extends Throwable> T accept(PrelVisitor<T, X, E> logicalVisitor, X value)
      throws E {
    return logicalVisitor.visitPrel(this, value);
  }

  @Override
  public BatchSchema.SelectionVectorMode[] getSupportedEncodings() {
    return BatchSchema.SelectionVectorMode.DEFAULT;
  }

  @Override
  public BatchSchema.SelectionVectorMode getEncoding() {
    return BatchSchema.SelectionVectorMode.NONE;
  }

  @Override
  public boolean needsFinalColumnReordering() {
    return true;
  }

  protected PhysicalOperator getChildPhysicalOperator(PhysicalPlanCreator creator)
      throws IOException {
    Prel child = (Prel) this.getInput();
    return child.getPhysicalOperator(creator);
  }
}
