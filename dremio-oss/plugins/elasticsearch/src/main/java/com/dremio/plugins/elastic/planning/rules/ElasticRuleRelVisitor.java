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
package com.dremio.plugins.elastic.planning.rules;

import com.dremio.exec.planner.common.MoreRelOptUtil.SubsetRemover;
import com.dremio.plugins.elastic.planning.rels.ElasticsearchFilter;
import com.dremio.plugins.elastic.planning.rels.ElasticsearchPrel;
import com.dremio.plugins.elastic.planning.rels.ElasticsearchProject;
import com.dremio.plugins.elastic.planning.rels.ElasticsearchSample;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelVisitor;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexNode;

/** Visits the elasticsearch subtree and collapses the tree. */
public abstract class ElasticRuleRelVisitor extends RelVisitor {
  private RexNode filterExprs = null;
  private List<RexNode> projectExprs = null;
  private RelDataType projectDataType = null;
  private RelNode child = null;
  private List<ElasticsearchPrel> parents = new ArrayList<>();
  private boolean continueToChildren = true;
  private final RelNode input;

  public ElasticRuleRelVisitor(RelNode input) {
    this.input = input;
  }

  public abstract void processFilter(ElasticsearchFilter filter);

  public abstract void processProject(ElasticsearchProject project);

  public abstract void processSample(ElasticsearchSample node);

  public ElasticRuleRelVisitor go() {
    go(getInput().accept(new SubsetRemover(false)));
    assert getChild() != null;
    return this;
  }

  public List<RexNode> getProjectExprs() {
    return projectExprs;
  }

  @Override
  public void visit(RelNode node, int ordinal, RelNode parent) {
    if (node instanceof ElasticsearchFilter) {
      processFilter((ElasticsearchFilter) node);
    } else if (node instanceof ElasticsearchProject) {
      processProject((ElasticsearchProject) node);
    } else if (node instanceof ElasticsearchSample) {
      processSample((ElasticsearchSample) node);
    } else {
      setChild(node);
      setContinueToChildren(false);
    }

    if (isContinueToChildren()) {
      super.visit(node, ordinal, parent);
    }
  }

  public ElasticsearchPrel getConvertedTree() {
    ElasticsearchPrel subTree = (ElasticsearchPrel) this.getChild();

    if (getFilterExprs() != null) {
      subTree =
          new ElasticsearchFilter(
              subTree.getCluster(),
              subTree.getTraitSet(),
              subTree,
              getFilterExprs(),
              subTree.getPluginId());
    }

    if (getProjectExprs() != null) {
      subTree =
          new ElasticsearchProject(
              subTree.getCluster(),
              subTree.getTraitSet(),
              subTree,
              getProjectExprs(),
              getProjectDataType(),
              subTree.getPluginId());
    }

    if (getParents() != null && !getParents().isEmpty()) {
      ListIterator<ElasticsearchPrel> iterator = getParents().listIterator(getParents().size());
      while (iterator.hasPrevious()) {
        final ElasticsearchPrel parent = iterator.previous();
        subTree =
            (ElasticsearchPrel)
                parent.copy(parent.getTraitSet(), Collections.singletonList((RelNode) subTree));
      }
    }

    return subTree;
  }

  protected RexNode getFilterExprs() {
    return filterExprs;
  }

  protected void setFilterExprs(RexNode filterExprs) {
    this.filterExprs = filterExprs;
  }

  protected void setProjectExprs(List<RexNode> projectExprs) {
    this.projectExprs = projectExprs;
  }

  protected RelDataType getProjectDataType() {
    return projectDataType;
  }

  protected void setProjectDataType(RelDataType projectDataType) {
    this.projectDataType = projectDataType;
  }

  protected RelNode getChild() {
    return child;
  }

  protected void setChild(RelNode child) {
    this.child = child;
  }

  protected List<ElasticsearchPrel> getParents() {
    return parents;
  }

  protected void setParents(List<ElasticsearchPrel> parents) {
    this.parents = parents;
  }

  protected boolean isContinueToChildren() {
    return continueToChildren;
  }

  protected void setContinueToChildren(boolean continueToChildren) {
    this.continueToChildren = continueToChildren;
  }

  protected RelNode getInput() {
    return input;
  }
}
