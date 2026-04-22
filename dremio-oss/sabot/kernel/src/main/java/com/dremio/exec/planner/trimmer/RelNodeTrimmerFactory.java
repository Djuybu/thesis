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
package com.dremio.exec.planner.trimmer;

import com.dremio.exec.planner.trimmer.calcite.CalciteRelNodeTrimmer;
import com.dremio.exec.planner.trimmer.calcite.DremioFieldTrimmerParameters;
import com.dremio.exec.planner.trimmer.pushdown.ProjectPushdownRelNodeTrimmer;

public final class RelNodeTrimmerFactory {
  private RelNodeTrimmerFactory() {}

  private static MonadicRelNodeTrimmer createCalciteTrimmer(
      DremioFieldTrimmerParameters parameters) {
    return MonadicRelNodeTrimmerWrapper.wrap(new CalciteRelNodeTrimmer(parameters));
  }

  private static MonadicRelNodeTrimmer createPushdownTrimmer() {
    return MonadicRelNodeTrimmerWrapper.wrap(ProjectPushdownRelNodeTrimmer.INSTANCE);
  }

  public static MonadicRelNodeTrimmer create(
      TrimmerType type, DremioFieldTrimmerParameters parameters) {
    MonadicRelNodeTrimmer trimmer;
    switch (type) {
      case CALCITE:
        trimmer = createCalciteTrimmer(parameters);
        break;

      case PUSHDOWN:
        trimmer = createPushdownTrimmer();
        break;

      case COMPOSITE:
        trimmer = new CompositeTrimmer(createCalciteTrimmer(parameters), createPushdownTrimmer());
        break;

      default:
        throw new UnsupportedOperationException("Unknown trimmer type: " + type);
    }

    return trimmer;
  }
}
