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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.dremio.exec.physical.base.PhysicalOperator;
import com.dremio.exec.planner.fragment.DistributionAffinity;
import com.dremio.exec.planner.physical.BridgeExchangePrel;
import com.dremio.exec.planner.physical.BridgeReaderPrel;
import com.dremio.exec.planner.physical.LeafPrel;
import com.dremio.exec.planner.physical.PhysicalPlanCreator;
import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.physical.UnionExchangePrel;
import com.dremio.exec.record.BatchSchema;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.apache.calcite.plan.ConventionTraitDef;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.AbstractRelNode;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ExcessiveExchangeIdentifierTest {

  @Mock RelOptCluster cluster;
  @Mock RelTraitSet traits;
  @Mock RelMetadataQuery metadataQuery;

  @Test
  public void testExchangeNotRemovedAboveBridgeReader() {
    when(traits.getTrait(ConventionTraitDef.INSTANCE)).thenReturn(Prel.PHYSICAL);
    when(cluster.getMetadataQuery()).thenReturn(metadataQuery);
    when(metadataQuery.getRowCount(any())).thenReturn(1D);

    BridgeReaderPrel bridgeReader = new BridgeReaderPrel(cluster, traits, null, 1, "bx0");
    UnionExchangePrel exchange = new UnionExchangePrel(cluster, traits, bridgeReader);

    Prel prel = ExcessiveExchangeIdentifier.removeExcessiveEchanges(exchange, 100000L);
    assertThat(prel).isInstanceOf(UnionExchangePrel.class);
    assertThat(prel.getInput(0)).isInstanceOf(BridgeReaderPrel.class);
  }

  @Test
  public void testBridgeExchangeNotRemoved() {
    when(cluster.getMetadataQuery()).thenReturn(metadataQuery);
    when(metadataQuery.getRowCount(any())).thenReturn(1D);

    SimpleLeafPrel input = SimpleLeafPrel.builder(cluster, traits).build();
    BridgeExchangePrel exchange = new BridgeExchangePrel(cluster, traits, input, "bx0");

    Prel prel = ExcessiveExchangeIdentifier.removeExcessiveEchanges(exchange, 100000L);
    assertThat(prel).isInstanceOf(BridgeExchangePrel.class);
    assertThat(prel.getInput(0)).isInstanceOf(SimpleLeafPrel.class);
  }

  @Test
  public void testExchangeNotRemovedWhenStrictAffinity() {
    when(traits.getTrait(ConventionTraitDef.INSTANCE)).thenReturn(Prel.PHYSICAL);
    when(cluster.getMetadataQuery()).thenReturn(metadataQuery);
    when(metadataQuery.getRowCount(any())).thenReturn(1D);

    SimpleLeafPrel input =
        SimpleLeafPrel.builder(cluster, traits).affinity(DistributionAffinity.HARD).build();
    UnionExchangePrel exchange = new UnionExchangePrel(cluster, traits, input);

    Prel prel = ExcessiveExchangeIdentifier.removeExcessiveEchanges(exchange, 100000L);
    assertThat(prel).isInstanceOf(UnionExchangePrel.class);
    assertThat(prel.getInput(0)).isInstanceOf(SimpleLeafPrel.class);
  }

  @Test
  public void testExchangeNotRemovedWhenOneSideIsNotSingleton() {
    when(traits.getTrait(ConventionTraitDef.INSTANCE)).thenReturn(Prel.PHYSICAL);
    when(cluster.getMetadataQuery()).thenReturn(metadataQuery);
    when(metadataQuery.getRowCount(any())).thenReturn(200000D);

    // Set large row count to force non-singleton
    SimpleLeafPrel input = SimpleLeafPrel.builder(cluster, traits).minWidth(2).rows(200000).build();
    UnionExchangePrel exchange = new UnionExchangePrel(cluster, traits, input);

    Prel prel = ExcessiveExchangeIdentifier.removeExcessiveEchanges(exchange, 100000L);
    assertThat(prel).isInstanceOf(UnionExchangePrel.class);
    assertThat(prel.getInput(0)).isInstanceOf(SimpleLeafPrel.class);
  }

  @Test
  public void testExchangeRemoval() {
    when(traits.getTrait(ConventionTraitDef.INSTANCE)).thenReturn(Prel.PHYSICAL);
    when(cluster.getMetadataQuery()).thenReturn(metadataQuery);
    when(metadataQuery.getRowCount(any())).thenReturn(1D);

    SimpleLeafPrel input = SimpleLeafPrel.builder(cluster, traits).rows(1).build();
    UnionExchangePrel exchange = new UnionExchangePrel(cluster, traits, input);

    Prel prel = ExcessiveExchangeIdentifier.removeExcessiveEchanges(exchange, 100000L);
    assertThat(prel).isInstanceOf(SimpleLeafPrel.class);
  }

  private static final class SimpleLeafPrel extends AbstractRelNode implements LeafPrel {

    private DistributionAffinity affinity;
    private int maxWidth;
    private int minWidth;
    private int rows;

    public SimpleLeafPrel(
        RelOptCluster cluster,
        RelTraitSet traits,
        DistributionAffinity affinity,
        int maxWidth,
        int minWidth,
        int rows) {
      super(cluster, traits);
      this.affinity = affinity;
      this.maxWidth = maxWidth;
      this.minWidth = minWidth;
      this.rows = rows;
    }

    public static Builder builder(RelOptCluster cluster, RelTraitSet traits) {
      return new Builder().cluster(cluster).traits(traits);
    }

    @Override
    public DistributionAffinity getDistributionAffinity() {
      return affinity;
    }

    @Override
    public int getMaxParallelizationWidth() {
      return maxWidth;
    }

    @Override
    public int getMinParallelizationWidth() {
      return minWidth;
    }

    @Override
    public PhysicalOperator getPhysicalOperator(PhysicalPlanCreator creator) {
      return null;
    }

    @Override
    public <T, X, E extends Throwable> T accept(PrelVisitor<T, X, E> logicalVisitor, X value)
        throws E {
      return logicalVisitor.visitLeaf(this, value);
    }

    @Override
    public BatchSchema.SelectionVectorMode[] getSupportedEncodings() {
      return new BatchSchema.SelectionVectorMode[0];
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
    public double getCostForParallelization() {
      return rows;
    }

    @Override
    public int getEstimatedSize() {
      return rows;
    }

    @Override
    public Iterator<Prel> iterator() {
      return Collections.emptyIterator();
    }

    @Override
    public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
      return new SimpleLeafPrel(getCluster(), getTraitSet(), affinity, maxWidth, minWidth, rows);
    }

    private static final class Builder {

      private RelOptCluster cluster;
      private RelTraitSet traits;
      private DistributionAffinity affinity = DistributionAffinity.NONE;
      private int maxWidth = Integer.MAX_VALUE;
      private int minWidth = 1;
      private int rows = 1;

      public Builder cluster(RelOptCluster cluster) {
        this.cluster = cluster;
        return this;
      }

      public Builder traits(RelTraitSet traits) {
        this.traits = traits;
        return this;
      }

      public Builder affinity(DistributionAffinity affinity) {
        this.affinity = affinity;
        return this;
      }

      public Builder maxWidth(int maxWidth) {
        this.maxWidth = maxWidth;
        return this;
      }

      public Builder minWidth(int minWidth) {
        this.minWidth = minWidth;
        return this;
      }

      public Builder rows(int rows) {
        this.rows = rows;
        return this;
      }

      public SimpleLeafPrel build() {
        return new SimpleLeafPrel(cluster, traits, affinity, maxWidth, minWidth, rows);
      }
    }
  }
}
