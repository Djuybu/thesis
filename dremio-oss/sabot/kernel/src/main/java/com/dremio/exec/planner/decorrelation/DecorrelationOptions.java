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

public class DecorrelationOptions {
  // Staged interface for configuring decorrelator type
  public interface DecorrelatorTypeStage {
    IsRelPlanningStage decorrelatorType(DecorrelatorType decorrelatorType);
  }

  public interface IsRelPlanningStage {
    MakeCorrelateIdsDistinctStage isRelPlanning(boolean isRelPlanning);
  }

  public interface MakeCorrelateIdsDistinctStage {
    BuildStage makeCorrelateIdsDistinct(boolean makeCorrelateIdsDistinct);
  }

  public interface BuildStage {
    Options build();
  }

  public static final class Options {
    private final DecorrelatorType decorrelatorType;
    private final boolean isRelPlanning;
    private final boolean makeCorrelateIdsDistinct;

    private Options(
        DecorrelatorType decorrelatorType,
        boolean isRelPlanning,
        boolean makeCorrelateIdsDistinct) {
      this.decorrelatorType = decorrelatorType;
      this.isRelPlanning = isRelPlanning;
      this.makeCorrelateIdsDistinct = makeCorrelateIdsDistinct;
    }

    public DecorrelatorType getDecorrelatorType() {
      return decorrelatorType;
    }

    public boolean isRelPlanning() {
      return isRelPlanning;
    }

    public boolean isMakeCorrelateIdsDistinct() {
      return makeCorrelateIdsDistinct;
    }

    // With Methods
    public Options withDecorrelatorType(DecorrelatorType decorrelatorType) {
      return new Options(decorrelatorType, isRelPlanning, makeCorrelateIdsDistinct);
    }

    public Options withMakeCorrelateIdsDistinct(boolean makeCorrelateIdsDistinct) {
      return new Options(decorrelatorType, isRelPlanning, makeCorrelateIdsDistinct);
    }

    public Options withIsRelPlanning(boolean isRelPlanning) {
      return new Options(decorrelatorType, isRelPlanning, makeCorrelateIdsDistinct);
    }

    // Entry point for the builder
    public static DecorrelatorTypeStage builder() {
      return new Builder();
    }

    // Builder implementing all stages
    public static class Builder
        implements DecorrelatorTypeStage,
            IsRelPlanningStage,
            MakeCorrelateIdsDistinctStage,
            BuildStage {
      private DecorrelatorType decorrelatorType;
      private boolean isRelPlanning;
      private boolean makeCorrelateIdsDistinct;

      @Override
      public IsRelPlanningStage decorrelatorType(DecorrelatorType decorrelatorType) {
        this.decorrelatorType = decorrelatorType;
        return this;
      }

      @Override
      public MakeCorrelateIdsDistinctStage isRelPlanning(boolean isRelPlanning) {
        this.isRelPlanning = isRelPlanning;
        return this;
      }

      @Override
      public BuildStage makeCorrelateIdsDistinct(boolean makeCorrelateIdsDistinct) {
        this.makeCorrelateIdsDistinct = makeCorrelateIdsDistinct;
        return this;
      }

      @Override
      public Options build() {
        return new Options(decorrelatorType, isRelPlanning, makeCorrelateIdsDistinct);
      }
    }
  }
}
