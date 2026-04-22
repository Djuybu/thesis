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
package com.dremio.exec.planner.normalizer;

import java.util.List;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.rules.ProjectMergeRule;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexOver;
import org.apache.calcite.rex.RexSubQuery;
import org.apache.calcite.sql.SqlFunction;

/**
 * Extends Calcite's ProjectMergeRule, since it incorrectly merges unmergeable projects. This logic
 * is copied over from RelBuilder's project merge logic.
 */
public final class DremioProjectMergeRule extends ProjectMergeRule {

  public DremioProjectMergeRule(Config config) {
    super(config);
  }

  @Override
  public boolean matches(RelOptRuleCall call) {
    final Project topProject = call.rel(0);
    final Project bottomProject = call.rel(1);

    List<RexNode> topProjects = topProject.getProjects();
    List<RexNode> bottomProjects = bottomProject.getProjects();

    if (!shouldMergeProject(topProjects, bottomProjects)) {
      return false;
    }

    return super.matches(call);
  }

  private boolean shouldMergeProject(List<RexNode> nodes, List<RexNode> childNodes) {
    for (RexNode node : childNodes) {
      if (RexOver.containsOver(node)
          || (node instanceof RexCall && ((RexCall) node).getOperator() instanceof SqlFunction)) {
        return false;
      }
    }
    for (RexNode node : nodes) {
      if (RexOver.containsOver(node)) {
        return false;
      }

      // MODIFICATION BEGIN:
      // This can be removed once we bring in CALCITE-5127
      if (containsSubQuery(node)) {
        return false;
      }
      // MODIFICATION END
    }
    return true;
  }

  /** Helper method to recursively check if a RexNode contains a RexSubQuery. */
  private boolean containsSubQuery(RexNode node) {
    if (node instanceof RexSubQuery) {
      return true;
    }
    if (node instanceof RexCall) {
      for (RexNode operand : ((RexCall) node).getOperands()) {
        if (containsSubQuery(operand)) {
          return true;
        }
      }
    }
    return false;
  }

  public interface Config extends ProjectMergeRule.Config {
    Config DEFAULT = ProjectMergeRule.Config.DEFAULT.as(Config.class);

    @Override
    default DremioProjectMergeRule toRule() {
      return new DremioProjectMergeRule(this);
    }
  }
}
