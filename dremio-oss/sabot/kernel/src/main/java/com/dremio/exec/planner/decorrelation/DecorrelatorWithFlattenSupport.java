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

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.tools.RelBuilder;

/** Adds support for decorrelating a FLATTEN query to any decorrelator */
public final class DecorrelatorWithFlattenSupport implements Decorrelator {
  private final Decorrelator decorrelator;
  private final RelBuilder relBuilder;

  public DecorrelatorWithFlattenSupport(Decorrelator decorrelator, RelBuilder relBuilder) {
    this.decorrelator = decorrelator;
    this.relBuilder = relBuilder;
  }

  @Override
  public RelNode decorrelate(RelNode relNode) {
    relNode = FlattenDecorrelator.decorrelate(relNode, relBuilder);
    return decorrelator.decorrelate(relNode);
  }
}
