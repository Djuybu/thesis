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

import com.dremio.exec.physical.base.PhysicalOperator;
import com.dremio.exec.planner.common.WriterRelBase;
import com.dremio.exec.planner.logical.CreateTableEntry;
import com.dremio.exec.planner.logical.rel.ResultWriterRel;
import com.dremio.exec.planner.physical.visitor.PrelVisitor;
import com.dremio.exec.planner.sql.CalciteArrowHelper;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.RecordWriter;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.metadata.RelMetadataQuery;

public class ResultWriterPrel extends ResultWriterRel implements Prel {

  public ResultWriterPrel(RelOptCluster cluster, RelTraitSet traits, RelNode child) {
    super(cluster, traits, child);
    rowType =
        CalciteArrowHelper.wrap(RecordWriter.SCHEMA)
            .toCalciteRecordType(getCluster().getTypeFactory(), true);
  }

  @Override
  public ResultWriterPrel copy(RelTraitSet traitSet, List<RelNode> inputs) {
    return new ResultWriterPrel(getCluster(), traitSet, sole(inputs));
  }

  @Override
  public BatchSchema.SelectionVectorMode getEncoding() {
    return BatchSchema.SelectionVectorMode.NONE;
  }

  @Override
  public boolean needsFinalColumnReordering() {
    return false;
  }

  @Override
  public PhysicalOperator getPhysicalOperator(PhysicalPlanCreator creator) throws IOException {
    PhysicalOperator child = ((Prel) this.getInput()).getPhysicalOperator(creator);
    CreateTableEntry createTableEntry = creator.getCreateTableEntryForResults();
    return createTableEntry.getWriter(
        creator.props(this, creator.getContext().getQueryUserName(), RecordWriter.SCHEMA), child);
  }

  @Override
  public <T, X, E extends Throwable> T accept(PrelVisitor<T, X, E> logicalVisitor, X value)
      throws E {
    return logicalVisitor.visitPrel(this, value);
  }

  @Override
  public Iterator<Prel> iterator() {
    return PrelUtil.iter(getInput());
  }

  @Override
  public BatchSchema.SelectionVectorMode[] getSupportedEncodings() {
    return BatchSchema.SelectionVectorMode.DEFAULT;
  }

  @Override
  public double estimateRowCount(RelMetadataQuery mq) {
    return WriterRelBase.estimateRowCount(this, mq, input.getRowType());
  }
}
