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
package com.dremio.exec.planner.transpose;

import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.rules.ProjectRemoveRule;

/**
 * This rule only exists because ProjectRemoveRule special cases when it's a project on project and
 * uses project.getInput() instanceof Project which has a bug when wrapped in a HepRelVertex. The
 * solution is to use call.rel(1) with the appropriate config.
 */
public final class ProjectProjectRemoveRule extends ProjectRemoveRule {

  private ProjectProjectRemoveRule(Config config) {
    super(config);
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    Project project = call.rel(0);
    assert isTrivial(project);
    Project childProject = call.rel(1);
    // Rename columns of child projection if desired field names are given.
    RelNode stripped =
        childProject.copy(
            childProject.getTraitSet(),
            childProject.getInput(),
            childProject.getProjects(),
            project.getRowType());
    RelNode child = call.getPlanner().register(stripped, project);
    call.transformTo(child);
  }

  public interface Config extends ProjectRemoveRule.Config {
    Config DEFAULT =
        EMPTY
            .withDescription("ProjectProjectRemoveRule")
            .withOperandSupplier(
                top ->
                    top.operand(Project.class)
                        // Use a predicate to detect non-matches early.
                        // This keeps the rule queue short.
                        .predicate(ProjectRemoveRule::isTrivial)
                        .oneInput(bottom -> bottom.operand(Project.class).anyInputs()))
            .as(Config.class);

    @Override
    default ProjectProjectRemoveRule toRule() {
      return new ProjectProjectRemoveRule(this);
    }
  }
}
