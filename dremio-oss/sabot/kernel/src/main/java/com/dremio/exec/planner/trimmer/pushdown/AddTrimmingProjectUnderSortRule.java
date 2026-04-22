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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Sort;
import org.apache.calcite.tools.RelBuilder;

/**
 * This rule adds a trimming project under a sort node that has a project on top of it. For example,
 * it rewrites
 *
 * <pre>
 * LogicalProject(JOB=[$2])
 *   LogicalSort(sort0=[$1], dir0=[ASC])
 *     LogicalTableScan(table=[[EMP]])
 * </pre>
 *
 * To:
 *
 * <pre>
 *  LogicalProject(JOB=[$1])
 *    LogicalSort(sort0=[$0], dir0=[ASC])
 *     LogicalProject(ENAME=[$1], JOB=[$2])
 *        LogicalTableScan(table=[[EMP]])
 * </pre>
 *
 * This is useful, since we can't transpose the top project with the sort, but we can add a trimming
 * project that can later be pushed down.
 */
public final class AddTrimmingProjectUnderSortRule extends AddTrimmingProjectRule<Sort> {
  private AddTrimmingProjectUnderSortRule(AddTrimmingProjectUnderSortRule.Config config) {
    super(config);
  }

  @Override
  public Set<Integer> extractColumnsUsed(Sort sort) {
    return RelOptUtil.InputFinder.bits(sort.getSortExps(), null).asSet();
  }

  @Override
  public void addRewrittenNode(
      Sort sort, RelBuilder relBuilder, Map<Integer, Integer> indexMapping) {
    List<RelFieldCollation> newRelFieldCollations =
        sort.getCollation().getFieldCollations().stream()
            .map(
                relFieldCollation ->
                    relFieldCollation.withFieldIndex(
                        indexMapping.get(relFieldCollation.getFieldIndex())))
            .collect(Collectors.toList());
    RelCollation relCollation = RelCollations.of(newRelFieldCollations);
    relBuilder.sortLimit(relCollation, sort.offset, sort.fetch);
  }

  public interface Config extends AddTrimmingProjectRule.Config {
    AddTrimmingProjectUnderSortRule.Config DEFAULT =
        RelRule.Config.EMPTY
            .withDescription(AddTrimmingProjectUnderSortRule.class.getSimpleName())
            .withOperandSupplier(
                project ->
                    project
                        .operand(Project.class)
                        .oneInput(
                            sort ->
                                sort.operand(Sort.class)
                                    .oneInput(any -> any.operand(RelNode.class).anyInputs())))
            .as(AddTrimmingProjectRule.Config.class)
            .withHasTopProject(true)
            .as(AddTrimmingProjectUnderSortRule.Config.class);

    @Override
    default AddTrimmingProjectUnderSortRule toRule() {
      return new AddTrimmingProjectUnderSortRule(this);
    }
  }
}
