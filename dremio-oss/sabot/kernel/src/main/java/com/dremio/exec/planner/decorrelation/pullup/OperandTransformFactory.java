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
package com.dremio.exec.planner.decorrelation.pullup;

import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Filter;
import org.apache.calcite.rel.core.Project;

public final class OperandTransformFactory {
  private OperandTransformFactory() {}

  public static final RelRule.OperandTransform CORRELATED_FILTER =
      input -> input.operand(Filter.class).predicate(Utils::isCorrelatedFilter).anyInputs();
  public static final RelRule.OperandTransform BLOCKING_PROJECT =
      input ->
          input
              .operand(Project.class)
              .predicate(project -> !Utils.hasAdjustCountOperator(project))
              .oneInput(CORRELATED_FILTER);

  public static final RelRule.OperandTransform ADJUST_COUNT_PROJECT =
      input ->
          input
              .operand(Project.class)
              .predicate(Utils::hasAdjustCountOperator)
              .oneInput(CORRELATED_FILTER);
  public static final RelRule.OperandTransform ANY_RELNODE =
      input -> input.operand(RelNode.class).anyInputs();

  public static SingleRel single(Class<? extends RelNode> c) {
    return new SingleRel(c);
  }

  public static BiRel bi(Class<? extends RelNode> c) {
    return new BiRel(c);
  }

  public static final class SingleRel {
    private final Class<? extends RelNode> c;

    public SingleRel(Class<? extends RelNode> c) {
      this.c = c;
    }

    public RelRule.OperandTransform correlatedFilter() {
      return transform(c, CORRELATED_FILTER);
    }

    public RelRule.OperandTransform blockingProject() {
      return transform(c, BLOCKING_PROJECT);
    }

    private static RelRule.OperandTransform transform(
        Class<? extends RelNode> c, RelRule.OperandTransform transform) {
      return operandBuilder -> operandBuilder.operand(c).oneInput(transform);
    }
  }

  public static final class BiRel {
    private final Class<? extends RelNode> c;

    public BiRel(Class<? extends RelNode> c) {
      this.c = c;
    }

    public Left left() {
      return new Left(c);
    }

    public Right right() {
      return new Right(c);
    }

    public static final class Left {
      private final Class<? extends RelNode> c;

      private Left(Class<? extends RelNode> c) {
        this.c = c;
      }

      public RelRule.OperandTransform correlatedFilter() {
        return transform(c, CORRELATED_FILTER);
      }

      public RelRule.OperandTransform blockingProject() {
        return transform(c, BLOCKING_PROJECT);
      }

      private static RelRule.OperandTransform transform(
          Class<? extends RelNode> c, RelRule.OperandTransform leftTransform) {
        return operandBuilder -> operandBuilder.operand(c).inputs(leftTransform, ANY_RELNODE);
      }
    }

    public static final class Right {
      private final Class<? extends RelNode> c;

      private Right(Class<? extends RelNode> c) {
        this.c = c;
      }

      public RelRule.OperandTransform correlatedFilter() {
        return transform(c, CORRELATED_FILTER);
      }

      public RelRule.OperandTransform blockingProject() {
        return transform(c, BLOCKING_PROJECT);
      }

      private static RelRule.OperandTransform transform(
          Class<? extends RelNode> c, RelRule.OperandTransform rightTransform) {
        return operandBuilder -> operandBuilder.operand(c).inputs(ANY_RELNODE, rightTransform);
      }
    }
  }
}
