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
package com.dremio.exec.planner.trimmer.pushdown;

import static java.util.stream.Collectors.toSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.rules.TransformationRule;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.util.ImmutableBeans;

/**
 * Base rule for adding a trimming project under a RelNode, so that we can continue with project
 * pushdown. This rule can be extended for any relnode with just the following bits of information:
 *
 * <p>1) What columns are needed for that relnode 2) How to rewrite the relnode given an updated
 * mapping of indexes
 */
public abstract class AddTrimmingProjectRule<R extends RelNode>
    extends RelRule<AddTrimmingProjectRule.Config> implements TransformationRule {
  protected AddTrimmingProjectRule(AddTrimmingProjectRule.Config config) {
    super(config);
  }

  @Override
  public void onMatch(RelOptRuleCall call) {
    Optional<Project> optionalProject;
    R nodeToTrim;
    RelNode input;

    if (config.isHasTopProject()) {
      optionalProject = Optional.of(call.rel(0));
      nodeToTrim = call.rel(1);
      input = call.rel(2);
    } else {
      optionalProject = Optional.empty();
      nodeToTrim = call.rel(0);
      input = call.rel(1);
    }

    RelBuilder relBuilder = call.builder().push(input);

    // Calculate indexes used
    Set<Integer> columnsUsed = extractColumnsUsed(nodeToTrim);
    if (optionalProject.isPresent()) {
      Set<Integer> projectColumns =
          RelOptUtil.InputFinder.bits(optionalProject.get().getProjects(), null).asSet();
      columnsUsed = Stream.concat(columnsUsed.stream(), projectColumns.stream()).collect(toSet());
    }

    if (columnsUsed.size() == input.getRowType().getFieldCount()) {
      // Nothing to trim
      return;
    }

    // Add trimming project
    List<RexNode> trimmingProjectExpressions = new ArrayList<>();
    for (Integer column : columnsUsed) {
      if (column < input.getRowType().getFieldCount()) {
        trimmingProjectExpressions.add(relBuilder.field(column));
      }
    }

    relBuilder.project(trimmingProjectExpressions);

    // Create mapping
    List<Integer> sortedColumnsUsed = columnsUsed.stream().sorted().collect(Collectors.toList());
    Map<Integer, Integer> indexMapping = new HashMap<>();
    for (int i = 0; i < sortedColumnsUsed.size(); i++) {
      indexMapping.put(sortedColumnsUsed.get(i), i);
    }

    // Add rewritten node
    addRewrittenNode(nodeToTrim, relBuilder, indexMapping);

    if (optionalProject.isPresent()) {
      // Add rewritten project
      Project project = optionalProject.get();
      List<RexNode> newProjectExpressions = new ArrayList<>();
      for (RexNode projectExpr : project.getProjects()) {
        RexNode newProjectExpr =
            projectExpr.accept(
                new RexShuttle() {
                  @Override
                  public RexNode visitInputRef(RexInputRef inputRef) {
                    return relBuilder.field(indexMapping.get(inputRef.getIndex()));
                  }
                });
        newProjectExpressions.add(newProjectExpr);
      }

      relBuilder.project(newProjectExpressions, project.getRowType().getFieldNames());
    }

    RelNode transformed = relBuilder.build();

    call.transformTo(transformed);
  }

  public abstract Set<Integer> extractColumnsUsed(R relNode);

  public abstract void addRewrittenNode(
      R relNode, RelBuilder relBuilder, Map<Integer, Integer> indexMapping);

  public interface Config extends RelRule.Config {
    AddTrimmingProjectRule.Config DEFAULT =
        RelRule.Config.EMPTY
            .withDescription(AddTrimmingProjectRule.class.getSimpleName())
            .as(AddTrimmingProjectRule.Config.class);

    @ImmutableBeans.Property
    @ImmutableBeans.BooleanDefault(false)
    boolean isHasTopProject();

    /** Sets {@link #isHasTopProject()} ()}. */
    AddTrimmingProjectRule.Config withHasTopProject(boolean hasTopProject);
  }
}
