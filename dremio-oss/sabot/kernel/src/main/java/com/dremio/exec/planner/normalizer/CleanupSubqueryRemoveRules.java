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

import com.google.common.collect.ImmutableList;
import org.apache.calcite.plan.RelOptRule;

public final class CleanupSubqueryRemoveRules {
  private CleanupSubqueryRemoveRules() {}

  public static final ImmutableList<RelOptRule> RULES =
      ImmutableList.of(
          CleanupCardinalityPreservingSubqueryRemoveRule.Config.CORRELATE.toRule(),
          CleanupCardinalityPreservingSubqueryRemoveRule.Config.JOIN.toRule(),
          RemoveSingleValueAggregateRule.Config.DEFAULT.toRule(),
          new DremioProjectMergeRule(DremioProjectMergeRule.Config.DEFAULT));
}
