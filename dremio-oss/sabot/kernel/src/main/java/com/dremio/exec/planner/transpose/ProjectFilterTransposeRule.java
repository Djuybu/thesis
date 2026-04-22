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

import com.google.common.base.Preconditions;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.rules.TransformationRule;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexOver;
import org.apache.calcite.rex.RexShuttle;

public final class ProjectFilterTransposeRule extends RelRule<ProjectFilterTransposeRule.Config>
    implements TransformationRule {
  private ProjectFilterTransposeRule(Config config) {
    super(config);
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    Project project = call.rel(0);
    Filter filter = call.rel(1);
    InputRefAdjuster inputRefAdjuster =
        new InputRefAdjuster(project, call.builder().getRexBuilder());
    RexNode newCondition = filter.getCondition().accept(inputRefAdjuster);
    if (inputRefAdjuster.failed) {
      return;
    }

    if (RexOver.containsOver(project.getProjects(), null)) {
      return;
    }

    RelNode transposed =
        call.builder()
            .push(filter.getInput())
            .project(project.getProjects(), project.getRowType().getFieldNames(), true)
            .filter(newCondition)
            .build();

    call.transformTo(transposed);
  }

  private static final class InputRefAdjuster extends RexShuttle {
    private final Project project;
    private final RexBuilder rexBuilder;
    private boolean failed;

    private InputRefAdjuster(Project project, RexBuilder rexBuilder) {
      this.project = Preconditions.checkNotNull(project);
      this.rexBuilder = Preconditions.checkNotNull(rexBuilder);
      this.failed = false;
    }

    @Override
    public RexNode visitInputRef(RexInputRef inputRef) {
      // Find the index of the project that exposes this index
      // LogicalProject(a=[$0], c=[$2], b=[$1])"
      //   LogicalFilter(condition=[ROW_INT_POLICY_UDF_1_<my_table>($1)])"
      //     LogicalTableScan(table=[[foo]])"
      // LogicalFilter(condition=[ROW_INT_POLICY_UDF_1_<my_table>($2)])
      //   LogicalProject(a=[$0], c=[$2], b=[$1])
      //     LogicalTableScan(table=[[foo]])
      for (int i = 0; i < project.getProjects().size(); i++) {
        RexNode projectExpr = project.getProjects().get(i);
        if (projectExpr instanceof RexInputRef) {
          RexInputRef rexInputRef = (RexInputRef) projectExpr;
          if (rexInputRef.getIndex() == inputRef.getIndex()) {
            return rexBuilder.makeInputRef(project, i);
          }
        }
      }

      this.failed = true;
      return inputRef;
    }
  }

  public interface Config extends RelRule.Config {
    Config DEFAULT =
        RelRule.Config.EMPTY
            .withDescription("ProjectFilterTransposeRule")
            .withOperandSupplier(
                op ->
                    op.operand(Project.class)
                        .oneInput(filter -> filter.operand(Filter.class).anyInputs()))
            .as(Config.class);

    @Override
    default ProjectFilterTransposeRule toRule() {
      return new ProjectFilterTransposeRule(this);
    }
  }
}
