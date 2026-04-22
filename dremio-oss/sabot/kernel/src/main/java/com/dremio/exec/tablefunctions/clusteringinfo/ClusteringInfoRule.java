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

import com.dremio.exec.planner.logical.Rel;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.convert.ConverterRule;

/** Rule to convert {@link ClusteringInfoCrel} nodes to {@link ClusteringInfoDrel} nodes. */
public final class ClusteringInfoRule extends ConverterRule {
  public static final ClusteringInfoRule INSTANCE = new ClusteringInfoRule();

  public ClusteringInfoRule() {
    super(ClusteringInfoCrel.class, Convention.NONE, Rel.LOGICAL, "ClusteringInfoRule");
  }

  @Override
  public RelNode convert(RelNode rel) {
    final ClusteringInfoCrel node = (ClusteringInfoCrel) rel;
    return new ClusteringInfoDrel(
        node.getCluster(),
        node.getTraitSet().replace(Rel.LOGICAL),
        node.getContext(),
        node.getClusteringInfoMetadata());
  }
}
