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
package com.dremio.exec.planner.decorrelation.calcite;

import com.dremio.exec.planner.decorrelation.Decorrelator;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.tools.RelBuilder;

/**
 * Wrapper class to turn:
 *
 * @see DremioRelDecorrelator into a
 * @see Decorrelator
 */
public final class DremioDecorrelatorWrapper implements Decorrelator {
  private final DremioRelDecorrelator dremioRelDecorrelator;

  public DremioDecorrelatorWrapper(DremioRelDecorrelator dremioRelDecorrelator) {
    this.dremioRelDecorrelator = dremioRelDecorrelator;
  }

  @Override
  public RelNode decorrelate(RelNode relNode) {
    RelNode decorrelatedRel = dremioRelDecorrelator.removeCorrelationViaRule(relNode);
    if (!dremioRelDecorrelator.cm.getMapCorToCorRel().isEmpty()) {
      decorrelatedRel = dremioRelDecorrelator.decorrelate(decorrelatedRel);
    }

    return decorrelatedRel;
  }

  public static DremioDecorrelatorWrapper create(
      RelNode relNode, RelBuilder relBuilder, boolean forceValueGeneration, boolean isRelPlanning) {
    DremioRelDecorrelator dremioRelDecorrelator =
        DremioRelDecorrelator.create(relNode, relBuilder, forceValueGeneration, isRelPlanning);
    return new DremioDecorrelatorWrapper(dremioRelDecorrelator);
  }
}
