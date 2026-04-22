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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.Window;
import org.apache.calcite.tools.RelBuilder;

/**
 * This rule detects a project on top of a window and trims out any window functions not referenced
 * by the project.
 *
 * <p>For example it rewrites:
 *
 * <pre>
 * LogicalProject($f1=[$2])
 *   LogicalWindow(window1, window2)
 *     LogicalProject($0)
 *       LogicalTableScan(table=[[EMP]])
 * </pre>
 *
 * to:
 *
 * <pre>
 * LogicalProject($f1=[$1])
 *   LogicalWindow(window2)
 *     LogicalProject($0)
 *       LogicalTableScan(table=[[EMP]])
 * </pre>
 *
 * Notice that window1 was trimmed off.
 */
public final class TrimWindowRule extends TrimRelNodeRule<Window> {
  private TrimWindowRule(TrimWindowRule.Config config) {
    super(config);
  }

  @Override
  public Set<Integer> pushTrimmedRelNode(
      Set<Integer> projectRefs, Window window, RelNode input, RelBuilder relBuilder) {
    List<Window.Group> windowGroups = new ArrayList<>();
    Set<Integer> removedIndexes = new HashSet<>();

    for (int i = 0; i < window.groups.size(); i++) {
      Window.Group group = window.groups.get(i);
      int index = i + input.getRowType().getFieldCount();
      boolean isReferenced = projectRefs.contains(index);
      if (isReferenced) {
        windowGroups.add(group);
      } else {
        removedIndexes.add(index);
      }
    }

    WindowRelBuilder.window(relBuilder, window.constants, windowGroups);

    return removedIndexes;
  }

  public interface Config extends TrimRelNodeRule.Config {
    TrimWindowRule.Config DEFAULT =
        TrimRelNodeRule.Config.DEFAULT
            .withDescription(TrimWindowRule.class.getSimpleName())
            .withOperandSupplier(
                op ->
                    op.operand(Project.class)
                        .oneInput(
                            window ->
                                window
                                    .operand(Window.class)
                                    .oneInput(any -> any.operand(RelNode.class).anyInputs())))
            .as(TrimWindowRule.Config.class);

    @Override
    default TrimWindowRule toRule() {
      return new TrimWindowRule(this);
    }
  }
}
