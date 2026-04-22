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
package com.dremio.exec.planner.plancache.distributable;

import com.dremio.exec.planner.physical.AdaptiveHashExchangePrel;
import com.dremio.exec.planner.physical.BridgeExchangePrel;
import com.dremio.exec.planner.physical.BroadcastExchangePrel;
import com.dremio.exec.planner.physical.FilterPrel;
import com.dremio.exec.planner.physical.HashAggPrel;
import com.dremio.exec.planner.physical.HashJoinPrel;
import com.dremio.exec.planner.physical.HashToMergeExchangePrel;
import com.dremio.exec.planner.physical.HashToRandomExchangePrel;
import com.dremio.exec.planner.physical.MergeJoinPrel;
import com.dremio.exec.planner.physical.NestedLoopJoinPrel;
import com.dremio.exec.planner.physical.OrderedPartitionExchangePrel;
import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.physical.ProjectPrel;
import com.dremio.exec.planner.physical.ResultWriterCommiterPrel;
import com.dremio.exec.planner.physical.ResultWriterPrel;
import com.dremio.exec.planner.physical.RoundRobinExchangePrel;
import com.dremio.exec.planner.physical.ScreenPrel;
import com.dremio.exec.planner.physical.SingleMergeExchangePrel;
import com.dremio.exec.planner.physical.SortPrel;
import com.dremio.exec.planner.physical.StreamAggPrel;
import com.dremio.exec.planner.physical.TableFunctionPrel;
import com.dremio.exec.planner.physical.UnionExchangePrel;
import com.dremio.exec.planner.physical.UnorderedDeMuxExchangePrel;
import com.dremio.exec.planner.physical.UnorderedMuxExchangePrel;
import com.dremio.exec.planner.physical.ValuesPrel;
import com.dremio.exec.store.iceberg.IcebergManifestListPrel;

public class DistributablePlanCacheUtils {

  public static boolean isSupported(Prel prel) {
    for (Prel sub : prel) {
      if (!isPrelSupported(sub)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isPrelSupported(Prel prel) {
    if (prel instanceof IcebergManifestListPrel) {
      IcebergManifestListPrel icebergManifestListPrel = (IcebergManifestListPrel) prel;
      return true;
    } else if (prel.getClass() == TableFunctionPrel.class) {
      TableFunctionPrel tableFunctionPrel = (TableFunctionPrel) prel;
      return true; // TODO do a better check
    } else {
      return prel instanceof StreamAggPrel
          || prel instanceof HashAggPrel
          || prel instanceof HashJoinPrel
          || prel instanceof MergeJoinPrel
          || prel instanceof NestedLoopJoinPrel
          || prel instanceof FilterPrel
          || prel instanceof ProjectPrel
          || prel instanceof ResultWriterCommiterPrel
          || prel instanceof ResultWriterPrel
          || prel instanceof ScreenPrel
          || prel instanceof SortPrel
          || prel instanceof ValuesPrel
          || prel instanceof AdaptiveHashExchangePrel
          || prel instanceof BridgeExchangePrel
          || prel instanceof BroadcastExchangePrel
          || prel instanceof HashToMergeExchangePrel
          || prel instanceof HashToRandomExchangePrel
          || prel instanceof OrderedPartitionExchangePrel
          || prel instanceof RoundRobinExchangePrel
          || prel instanceof SingleMergeExchangePrel
          || prel instanceof UnionExchangePrel
          || prel instanceof UnorderedDeMuxExchangePrel
          || prel instanceof UnorderedMuxExchangePrel;
    }
  }
}
