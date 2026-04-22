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

import com.dremio.exec.planner.logical.Rel;
import com.dremio.exec.planner.logical.WindowRel;
import com.dremio.exec.planner.physical.Prel;
import java.util.List;
import org.apache.calcite.plan.Convention;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Window;
import org.apache.calcite.rel.core.Window.Group;
import org.apache.calcite.rel.core.Window.RexWinAggCall;
import org.apache.calcite.rel.logical.LogicalWindow;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.tools.RelBuilder;

/**
 * This class exists as a stop gap solution for the fact that RelBuilder does not have a
 * .window(...) method that is traitset agnostic
 */
public final class WindowRelBuilder {
  private WindowRelBuilder() {}

  public static RelBuilder window(
      RelBuilder relBuilder, List<RexLiteral> constants, List<Group> groups) {
    RelNode input = relBuilder.build(); // Get the current RelNode

    // Calculate the output RelDataType
    RelDataType inputRowType = input.getRowType();
    RelDataTypeFactory.Builder typeBuilder = relBuilder.getTypeFactory().builder();
    typeBuilder.addAll(inputRowType.getFieldList()); // Add input fields

    int i = 0;
    // Add fields for each window function in all groups
    for (Window.Group group : groups) {
      // Add fields for each RexWinAggCall in all groups
      for (RexWinAggCall aggCall : group.aggCalls) {
        String aggCallName = "aggCall" + i++; // Generate a unique name
        typeBuilder.add(aggCallName, aggCall.getType()); // Add window function output
      }
    }

    RelDataType outputRowType = typeBuilder.build();

    // Create the Window RelNode
    Window window;
    if (input.getConvention() == Convention.NONE) {
      window = LogicalWindow.create(input.getTraitSet(), input, constants, outputRowType, groups);
    } else if (input instanceof Rel) {
      window =
          WindowRel.create(
              input.getCluster(), input.getTraitSet(), input, constants, outputRowType, groups);
    } else if (input instanceof Prel) {
      throw new UnsupportedOperationException("Don't know how to create a WindowPrel...");
    } else {
      throw new UnsupportedOperationException("Unknown convention");
    }

    return relBuilder.push(window);
  }
}
