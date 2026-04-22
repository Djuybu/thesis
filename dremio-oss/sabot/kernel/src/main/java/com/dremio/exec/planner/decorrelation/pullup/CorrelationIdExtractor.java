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

import java.util.HashSet;
import java.util.Set;
import org.apache.calcite.rex.RexCorrelVariable;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexVisitorImpl;

public final class CorrelationIdExtractor extends RexVisitorImpl<Void> {

  private final Set<Integer> correlationIds;

  private CorrelationIdExtractor() {
    super(true); // Pass true to allow deep traversal
    this.correlationIds = new HashSet<>();
  }

  @Override
  public Void visitCorrelVariable(RexCorrelVariable correlVariable) {
    // Add the correlation ID to the set
    correlationIds.add(correlVariable.id.getId());
    return null;
  }

  public Set<Integer> getCorrelationIds() {
    return correlationIds;
  }

  public static Set<Integer> extract(RexNode node) {
    CorrelationIdExtractor extractor = new CorrelationIdExtractor();
    node.accept(extractor);
    return extractor.getCorrelationIds();
  }
}
