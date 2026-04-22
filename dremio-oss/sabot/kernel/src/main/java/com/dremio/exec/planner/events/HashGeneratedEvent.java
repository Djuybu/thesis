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
package com.dremio.exec.planner.events;

import org.apache.calcite.rel.RelNode;

/**
 * An event that passes the hash of the query tree after view and UDF expansion but before any
 * reflections are substituted.
 */
public class HashGeneratedEvent implements PlannerEvent {
  private final String hash;

  private final RelNode query;

  public HashGeneratedEvent(String hash, RelNode query) {
    this.hash = hash;
    this.query = query;
  }

  public String getHash() {
    return hash;
  }

  public RelNode getQuery() {
    return query;
  }
}
