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

import static com.dremio.exec.planner.physical.PrelUtil.getSettings;
import static com.dremio.exec.planner.sql.CalciteArrowHelper.fromCalciteRowTypeJson;

import com.dremio.common.JSONOptions;
import com.dremio.exec.physical.base.PhysicalOperator;
import com.dremio.exec.physical.config.Values;
import com.dremio.exec.planner.common.MoreRexUtil;
import com.dremio.exec.planner.fragment.DistributionAffinity;
import com.dremio.exec.planner.physical.visitor.PrelVisitor;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.BatchSchema.SelectionVectorMode;
import com.dremio.options.Options;
import com.dremio.options.TypeValidators.LongValidator;
import com.dremio.options.TypeValidators.PositiveLongValidator;
import com.fasterxml.jackson.core.JsonLocation;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.AbstractRelNode;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexLiteral;

@Options
public class ValuesPrel extends AbstractRelNode implements LeafPrel {

  public static final LongValidator RESERVE =
      new PositiveLongValidator("planner.op.values.reserve_bytes", Long.MAX_VALUE, DEFAULT_RESERVE);
  public static final LongValidator LIMIT =
      new PositiveLongValidator("planner.op.values.limit_bytes", Long.MAX_VALUE, DEFAULT_LIMIT);

  private final List<? extends List<RexLiteral>> tuples;
  private final JSONOptions jsonOptions;

  public ValuesPrel(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelDataType rowType,
      List<? extends List<RexLiteral>> tuples) {
    this(
        cluster,
        traitSet,
        rowType,
        tuples,
        new JSONOptions(
            MoreRexUtil.convertToJsonNode(
                rowType, tuples, getSettings(cluster).isValueCastEnabled()),
            JsonLocation.NA));
  }

  private ValuesPrel(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelDataType rowType,
      List<? extends List<RexLiteral>> tuples,
      JSONOptions jsonOptions) {
    super(cluster, traitSet);
    this.rowType = rowType;
    this.tuples = tuples;
    this.jsonOptions = jsonOptions;
  }

  @Override
  public RelWriter explainTerms(RelWriter pw) {
    if (jsonOptions.isOpaque()) {
      return super.explainTerms(pw).item("Key", jsonOptions.hashCode());
    } else {
      return super.explainTerms(pw).item("Values", jsonOptions.asNode());
    }
  }

  @Override
  public double estimateRowCount(RelMetadataQuery mq) {
    return tuples.size();
  }

  @Override
  public Iterator<Prel> iterator() {
    return Collections.emptyIterator();
  }

  @Override
  public PhysicalOperator getPhysicalOperator(PhysicalPlanCreator creator) throws IOException {
    BatchSchema schema =
        fromCalciteRowTypeJson(this.getRowType(), getSettings(getCluster()).isValueCastEnabled());
    return new Values(creator.props(this, null, schema, RESERVE, LIMIT), schema, jsonOptions);
  }

  @Override
  public Prel copy(RelTraitSet traitSet, List<RelNode> inputs) {
    Preconditions.checkArgument(inputs.isEmpty());
    return new ValuesPrel(getCluster(), traitSet, rowType, tuples, jsonOptions);
  }

  @Override
  protected Object clone() throws CloneNotSupportedException {
    return super.clone();
  }

  @Override
  public <T, X, E extends Throwable> T accept(PrelVisitor<T, X, E> logicalVisitor, X value)
      throws E {
    return logicalVisitor.visitLeaf(this, value);
  }

  @Override
  public SelectionVectorMode[] getSupportedEncodings() {
    return SelectionVectorMode.DEFAULT;
  }

  @Override
  public SelectionVectorMode getEncoding() {
    return SelectionVectorMode.NONE;
  }

  @Override
  public boolean needsFinalColumnReordering() {
    return false;
  }

  @Override
  public int getMaxParallelizationWidth() {
    return 1;
  }

  @Override
  public int getMinParallelizationWidth() {
    return 1;
  }

  @Override
  public DistributionAffinity getDistributionAffinity() {
    return DistributionAffinity.NONE;
  }

  public List<? extends List<RexLiteral>> getTuples() {
    return tuples;
  }
}
