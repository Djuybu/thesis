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
package com.dremio.exec.planner.decorrelation;

import com.dremio.exec.planner.decorrelation.calcite.DremioDecorrelatorWrapper;
import com.dremio.exec.planner.decorrelation.pullup.PullupDecorrelator;
import com.dremio.exec.planner.logical.DremioRelFactories;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.tools.RelBuilderFactory;

public class DecorrelatorFactory {
  private static final boolean ALLOW_PULLUP_AS_FALLBACK = false;

  public static MonadicDecorrelator create(RelNode relNode, DecorrelationOptions.Options options) {
    ImmutableList.Builder<MonadicDecorrelator> decorrelators = new Builder<>();
    // Use the users provided decorrelator type first
    decorrelators.add(createPipeline(relNode, options));

    // Then just try all the other options
    for (DecorrelatorType decorrelatorType : DecorrelatorType.values()) {
      if (decorrelatorType != DecorrelatorType.PULLUP) {
        decorrelators.add(createPipeline(relNode, options.withDecorrelatorType(decorrelatorType)));
      } else {
        if (ALLOW_PULLUP_AS_FALLBACK) {
          decorrelators.add(
              createPipeline(relNode, options.withDecorrelatorType(decorrelatorType)));
        }
      }
    }

    // Also use it last for consistency in error reporting
    decorrelators.add(createPipeline(relNode, options));

    return new FirstSuccessMonadicDecorrelator(decorrelators.build());
  }

  public static MonadicDecorrelator createPipeline(
      RelNode relNode, DecorrelationOptions.Options options) {
    RelBuilderFactory relBuilderFactory =
        options.isRelPlanning()
            ? DremioRelFactories.LOGICAL_BUILDER
            : DremioRelFactories.CALCITE_LOGICAL_BUILDER;
    RelBuilder relBuilder = relBuilderFactory.create(relNode.getCluster(), null);

    Decorrelator step0 = createBaseDecorrelator(relNode, relBuilder, options);
    Decorrelator step1 = new EarlyReturnDecorrelator(step0);
    Decorrelator step2 = new DecorrelatorWithFlattenSupport(step1, relBuilder);
    Decorrelator step3 =
        new DecorrelatorWithPreAndPostProcessing(
            step2,
            relBuilderFactory,
            options.isMakeCorrelateIdsDistinct(),
            options.isRelPlanning());
    MonadicDecorrelatorWrapper step4 = new MonadicDecorrelatorWrapper(step3);
    MonadicDecorrelator step5 = new MonadicDecorrelatorWithWorkarounds(step4, relBuilder);
    return step5;
  }

  private static Decorrelator createBaseDecorrelator(
      RelNode relNode, RelBuilder relBuilder, DecorrelationOptions.Options options) {
    switch (options.getDecorrelatorType()) {
      case CALCITE_WITH_FORCE_VALUE_GENERATION:
        return DremioDecorrelatorWrapper.create(relNode, relBuilder, true, options.isRelPlanning());
      case CALCITE_WITHOUT_FORCE_VALUE_GENERATION:
        return DremioDecorrelatorWrapper.create(
            relNode, relBuilder, false, options.isRelPlanning());
      case PULLUP:
        return PullupDecorrelator.create(
            options.isRelPlanning()
                ? DremioRelFactories.LOGICAL_BUILDER
                : DremioRelFactories.CALCITE_LOGICAL_BUILDER);
      default:
        throw new IllegalArgumentException();
    }
  }
}
