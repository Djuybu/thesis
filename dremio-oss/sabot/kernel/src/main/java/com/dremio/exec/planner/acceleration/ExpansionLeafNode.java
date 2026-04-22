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
package com.dremio.exec.planner.acceleration;

import com.dremio.catalog.model.dataset.TableVersionContext;
import com.dremio.exec.planner.acceleration.substitution.SubstitutionUtils;
import com.dremio.exec.planner.acceleration.substitution.SubstitutionUtils.VersionedPath;
import com.dremio.exec.planner.logical.ViewTable;
import com.dremio.service.Pointer;
import com.dremio.service.namespace.NamespaceKey;
import java.util.List;
import org.apache.calcite.plan.CopyWithCluster;
import org.apache.calcite.plan.CopyWithCluster.CopyToCluster;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptCost;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.AbstractRelNode;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelShuttleImpl;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexNode;

/** An expansion node that is a leaf node for purposes of simplified matching. */
public class ExpansionLeafNode extends AbstractRelNode implements CopyToCluster {
  private final NamespaceKey path;
  private final RelNode subTree;
  private final boolean isDefault;
  private final TableVersionContext versionContext;
  private final ViewTable viewTable;
  private final List<RexNode> pushedDownFilters;

  public static final int EXPANSION_MAX_DEPTH = 2;

  public ExpansionLeafNode(
      NamespaceKey path,
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelDataType rowType,
      RelNode subTree,
      boolean isDefault,
      TableVersionContext versionContext,
      ViewTable viewTable,
      List<RexNode> pushedFilters) {
    super(cluster, traitSet);
    this.path = path;
    this.rowType = rowType;
    this.subTree = subTree;
    this.isDefault = isDefault;
    this.versionContext = versionContext;
    this.viewTable = viewTable;
    this.pushedDownFilters = pushedFilters;
  }

  public RelNode getSubTree() {
    return subTree;
  }

  public NamespaceKey getPath() {
    return path;
  }

  public TableVersionContext getVersionContext() {
    return versionContext;
  }

  public ViewTable getViewTable() {
    return viewTable;
  }

  public boolean isDefault() {
    return isDefault;
  }

  public List<RexNode> getPushedDownFilters() {
    return pushedDownFilters;
  }

  @Override
  public RelWriter explainTerms(RelWriter pw) {
    return super.explainTerms(pw)
        .item("path", path.toUnescapedString())
        .itemIf("version", versionContext, versionContext != null)
        .itemIf(
            "Pushed Down Filters",
            this.pushedDownFilters.toString(),
            !this.pushedDownFilters.isEmpty());
  }

  @Override
  public RelOptCost computeSelfCost(RelOptPlanner planner, RelMetadataQuery mq) {
    return subTree == null
        ? planner.getCostFactory().makeZeroCost()
        : mq.getCumulativeCost(subTree);
  }

  @Override
  public double estimateRowCount(RelMetadataQuery mq) {
    return subTree == null ? 0 : subTree.estimateRowCount(mq);
  }

  public static TerminationResult terminateFirst(RelNode node) {
    return terminateFirst(node, 0);
  }

  /**
   * Terminate the first expansion node that you arrive at.
   *
   * @param node input node
   * @param depth how many levels of Expansion node to skip
   * @return termination results
   */
  public static TerminationResult terminateFirst(RelNode node, int depth) {
    final Pointer<Integer> skip = new Pointer<>(depth);
    final Pointer<Long> terminationCount = new Pointer<>(0L);
    final Pointer<VersionedPath> firstPath = new Pointer<>();
    RelNode newTree =
        node.accept(
            new RelShuttleImpl() {
              @Override
              public RelNode visit(RelNode other) {
                if (other instanceof ExpansionLeafNode) {
                  terminationCount.value = 1L;
                  ExpansionLeafNode leaf = (ExpansionLeafNode) other;
                  firstPath.value =
                      SubstitutionUtils.VersionedPath.of(
                          leaf.getPath().getPathComponents(), leaf.getVersionContext());
                  return other;
                }

                if (other instanceof ExpansionNode) {
                  if (skip.value > 0) {
                    skip.value--;
                    return super.visit(((ExpansionNode) other).getInput());
                  }
                  terminationCount.value++;
                  ExpansionNode e = (ExpansionNode) other;
                  if (firstPath.value == null) {
                    firstPath.value = SubstitutionUtils.VersionedPath.of(e);
                  }

                  return new ExpansionLeafNode(
                      e.getPath(),
                      node.getCluster(),
                      node.getTraitSet(),
                      e.getRowType(),
                      null,
                      e.isDefault(),
                      e.getVersionContext(),
                      null,
                      e.getPushedDownFilters());
                }
                return super.visit(other);
              }
            });

    return new TerminationResult(newTree, terminationCount.value, firstPath.value);
  }

  /**
   * Replace the ExpansonLeafNode with the expansion subtree
   *
   * @param node
   * @return
   */
  public static RelNode expand(RelNode node) {
    return node.accept(
        new RelShuttleImpl() {
          @Override
          public RelNode visit(RelNode other) {
            if (other instanceof ExpansionLeafNode) {
              ExpansionLeafNode leaf = (ExpansionLeafNode) other;
              // contextSensitivity doesn't matter here, as the ExpansionNode will be removed
              // as part of post substitution processing
              return ExpansionNode.wrap(
                  leaf.path,
                  leaf.getSubTree(),
                  leaf.getRowType(),
                  false,
                  leaf.getVersionContext(),
                  leaf.getViewTable());
            }
            return super.visit(other);
          }
        });
  }

  @Override
  public RelNode copyWith(CopyWithCluster copier) {
    return new ExpansionLeafNode(
        path,
        copier.getCluster(),
        copier.copyOf(getTraitSet()),
        rowType,
        subTree == null ? null : subTree.accept(copier),
        isDefault,
        versionContext,
        viewTable,
        pushedDownFilters);
  }

  /** Holds the result of ExpansionNode termination */
  public static class TerminationResult {
    private final RelNode result;
    private final long terminationCount;
    private final SubstitutionUtils.VersionedPath firstPath;

    public TerminationResult(
        RelNode result, long terminationCount, SubstitutionUtils.VersionedPath firstPath) {
      super();
      this.result = result;
      this.terminationCount = terminationCount;
      this.firstPath = firstPath;
    }

    public RelNode getResult() {
      return result;
    }

    public long getTerminationCount() {
      return terminationCount;
    }

    public SubstitutionUtils.VersionedPath getFirstPath() {
      return firstPath;
    }
  }

  /**
   * Terminate expansion nodes that match the provided path. For all others, remove them.
   *
   * @param pathFilter
   * @param node
   * @return
   */
  public static RelNode terminateIf(SubstitutionUtils.VersionedPath pathFilter, RelNode node) {
    return node.accept(
        new RelShuttleImpl() {
          @Override
          public RelNode visit(RelNode other) {
            if (other instanceof ExpansionNode) {
              ExpansionNode e = (ExpansionNode) other;
              if (SubstitutionUtils.VersionedPath.of(e).equals(pathFilter)) {
                return new ExpansionLeafNode(
                    e.getPath(),
                    node.getCluster(),
                    node.getTraitSet(),
                    other.getRowType(),
                    e.getInput(),
                    e.isDefault(),
                    e.getVersionContext(),
                    e.getViewTable(),
                    e.getPushedDownFilters());
              } else {
                ExpansionNode currentNode = (ExpansionNode) visitChild(other, 0, e.getInput());
                if (currentNode == other) {
                  return other;
                } else {
                  return currentNode.getInput();
                }
              }
            }
            return super.visit(other);
          }
        });
  }
}
