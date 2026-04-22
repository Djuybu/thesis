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

import java.util.ArrayList;
import java.util.List;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.plan.hep.HepRelVertex;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Union;
import org.apache.calcite.rel.rules.TransformationRule;

/**
 * Transposes a UNION with a project underneath it. Note that we can not pull this off for other
 * SetOps like MINUS and INTERSECT. Consider Two Sets:
 *
 * <p>Set A 123-123 123-456 123-789
 *
 * <p>Set B 321-123 321-456 321-789
 *
 * <p>let MASK(x) return the last 3 digits
 *
 * <p>Here is UNION transposition
 *
 * <p>UNION(all = true) MASK SET A MASK SET B => {123, 456, 789, 123, 456, 789}
 *
 * <p>MASK UNION(all = true) SET A SET B
 *
 * <p>=> {123, 456, 789, 123, 456, 789}
 *
 * <p>Here is a MINUS transposition:
 *
 * <p>MINUS MASK SET A MASK SET B
 *
 * <p>=> empty set
 *
 * <p>MASK MINUS SET A SET B => {123, 456, 789}
 *
 * <p>Here is a INTERSECT transposition:
 *
 * <p>INTERSECT(all = true) MASK SET A MASK SET B => {123, 456, 789}
 *
 * <p>MASK INTERSECT(all = true) SET A SET B => empty set
 */
public final class UnionProjectTransposeRule extends RelRule<UnionProjectTransposeRule.Config>
    implements TransformationRule {

  private UnionProjectTransposeRule(Config config) {
    super(config);
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    Union union = call.rel(0);
    Project project = call.rel(1);

    // Remove the projects from each input
    List<RelNode> newInputs = new ArrayList<>();
    for (RelNode input : union.getInputs()) {
      input = unwrapHepRelVertex(input);
      if (!(input instanceof Project)) {
        return;
      }

      Project childProject = (Project) input;
      boolean unifiedColumnMask = childProject.getProjects().equals(project.getProjects());
      if (!unifiedColumnMask) {
        return;
      }

      newInputs.add(childProject.getInput());
    }

    // Replace the union with a single Project on top
    RelNode newUnion = union.copy(union.getTraitSet(), newInputs);
    RelNode transposed =
        call.builder()
            .push(newUnion)
            .project(project.getProjects(), project.getRowType().getFieldNames())
            .build();

    call.transformTo(transposed);
  }

  private static RelNode unwrapHepRelVertex(RelNode relNode) {
    if (relNode instanceof HepRelVertex) {
      return ((HepRelVertex) relNode).getCurrentRel();
    }

    return relNode;
  }

  public interface Config extends RelRule.Config {
    Config DEFAULT =
        EMPTY
            .withDescription("UnionProjectTransposeRule")
            .withOperandSupplier(
                setOp ->
                    setOp
                        .operand(Union.class)
                        // We can only pull over a union all, since projects cant be pulled over
                        // distinct operators
                        .predicate(union -> union.all)
                        .inputs(project -> project.operand(Project.class).anyInputs()))
            .as(Config.class);

    @Override
    default UnionProjectTransposeRule toRule() {
      return new UnionProjectTransposeRule(this);
    }
  }
}
