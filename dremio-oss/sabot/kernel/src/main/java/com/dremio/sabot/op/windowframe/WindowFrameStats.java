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

package com.dremio.sabot.op.windowframe;

import com.dremio.exec.proto.UserBitShared.MetricDef.AggregationType;
import com.dremio.exec.proto.UserBitShared.MetricDef.DisplayType;
import com.dremio.sabot.exec.context.MetricDef;

/**
 * Stats for {@link WindowFrameOperator}
 *
 * <p>VERY IMPORTANT Please add new stats at the end of Metric table and be careful about changing
 * the order of metrics and/or removing metrics. Changes may result in incorrectly rendering old
 * profiles.
 */
public class WindowFrameStats {

  public enum Metric implements MetricDef {
    SETUP_MILLIS(
        DisplayType.DISPLAY_BY_DEFAULT,
        AggregationType.SUM,
        "Time taken to setup the window frame operator"),
    CONSUME_MILLIS(
        DisplayType.DISPLAY_BY_DEFAULT, AggregationType.SUM, "Time taken to consume input data"),
    OUTPUT_MILLIS(
        DisplayType.DISPLAY_BY_DEFAULT, AggregationType.SUM, "Time taken to produce output data");

    private final DisplayType displayType;
    private final AggregationType aggregationType;
    private final String displayCode;

    Metric() {
      this(DisplayType.DISPLAY_NEVER, AggregationType.SUM, "");
    }

    Metric(DisplayType displayType, AggregationType aggregationType, String displayCode) {
      this.displayType = displayType;
      this.aggregationType = aggregationType;
      this.displayCode = displayCode;
    }

    @Override
    public int metricId() {
      return ordinal();
    }

    @Override
    public DisplayType getDisplayType() {
      return this.displayType;
    }

    @Override
    public AggregationType getAggregationType() {
      return this.aggregationType;
    }

    @Override
    public String getDisplayCode() {
      return this.displayCode;
    }
  }
}
