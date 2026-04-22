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

import org.apache.calcite.rel.RelHomogeneousShuttle;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Correlate;

/**
 * Adds early return to any decorrelator so that it doesn't bother running on non correlated
 * queries.
 */
public class EarlyReturnDecorrelator implements Decorrelator {
  private final Decorrelator decorrelator;

  public EarlyReturnDecorrelator(Decorrelator decorrelator) {
    this.decorrelator = decorrelator;
  }

  @Override
  public RelNode decorrelate(RelNode relNode) {
    if (countCorrelate(relNode) == 0) {
      return relNode;
    }

    return decorrelator.decorrelate(relNode);
  }

  public static int countCorrelate(RelNode rel) {
    final int[] count = {0};
    rel.accept(
        new RelHomogeneousShuttle() {
          @Override
          public RelNode visit(RelNode other) {
            if (other instanceof Correlate) {
              count[0]++;
            }

            return super.visit(other);
          }
        });

    return count[0];
  }
}
