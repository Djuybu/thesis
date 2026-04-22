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
package com.dremio.exec.planner.plancache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dremio.exec.planner.physical.Prel;
import com.dremio.exec.planner.sql.handlers.SqlHandlerConfig;
import com.google.common.collect.ImmutableList;
import org.junit.Assert;
import org.junit.Test;

public class FallThroughPlanCacheTest {

  @Test
  public void testPutCachedPlan() {
    Subject subject = new Subject();

    when(subject.planCache1.putCachedPlan(any(), any(), any())).thenReturn(true);
    when(subject.planCache2.putCachedPlan(any(), any(), any())).thenReturn(true);

    SqlHandlerConfig config = mock(SqlHandlerConfig.class);
    PlanCacheKey cachedKey = mock(PlanCacheKey.class);
    Prel prel = mock(Prel.class);

    assertTrue(subject.fallThroughPlanCache.putCachedPlan(config, cachedKey, prel));
    verify(subject.planCache1, times(1)).putCachedPlan(config, cachedKey, prel);
    verifyNoInteractions(subject.planCache2);
  }

  @Test
  public void testPutCachedPlanNotSupportedFallThrough() {
    Subject subject = new Subject();

    when(subject.planCache2.putCachedPlan(any(), any(), any())).thenReturn(false);
    when(subject.planCache2.putCachedPlan(any(), any(), any())).thenReturn(true);

    SqlHandlerConfig config = mock(SqlHandlerConfig.class);
    PlanCacheKey cachedKey = mock(PlanCacheKey.class);
    Prel prel = mock(Prel.class);

    assertTrue(subject.fallThroughPlanCache.putCachedPlan(config, cachedKey, prel));
    verify(subject.planCache1, times(1)).putCachedPlan(config, cachedKey, prel);
    verify(subject.planCache2, times(1)).putCachedPlan(config, cachedKey, prel);
  }

  @Test
  public void testPutCachedPlanNoneSupported() {
    Subject subject = new Subject();

    when(subject.planCache1.putCachedPlan(any(), any(), any())).thenReturn(false);
    when(subject.planCache1.putCachedPlan(any(), any(), any())).thenReturn(false);

    SqlHandlerConfig config = mock(SqlHandlerConfig.class);
    PlanCacheKey cachedKey = mock(PlanCacheKey.class);
    Prel prel = mock(Prel.class);

    assertFalse(subject.fallThroughPlanCache.putCachedPlan(config, cachedKey, prel));
    verify(subject.planCache1, times(1)).putCachedPlan(config, cachedKey, prel);
    verify(subject.planCache2, times(1)).putCachedPlan(config, cachedKey, prel);
  }

  @Test
  public void testPutCachedPlanFailure() {
    Subject subject = new Subject();

    when(subject.planCache1.putCachedPlan(any(), any(), any()))
        .thenThrow(new RuntimeException("test"));
    when(subject.planCache2.putCachedPlan(any(), any(), any())).thenReturn(true);

    SqlHandlerConfig config = mock(SqlHandlerConfig.class);
    PlanCacheKey cachedKey = mock(PlanCacheKey.class);
    Prel prel = mock(Prel.class);

    assertTrue(subject.fallThroughPlanCache.putCachedPlan(config, cachedKey, prel));
    verify(subject.planCache1, times(1)).putCachedPlan(config, cachedKey, prel);
    verify(subject.planCache2, times(1)).putCachedPlan(config, cachedKey, prel);
  }

  @Test
  public void testGetIfPresentAndValidAbsent() {
    Subject subject = new Subject();
    when(subject.planCache1.getIfPresentAndValid(any(), any())).thenReturn(null);
    when(subject.planCache2.getIfPresentAndValid(any(), any())).thenReturn(null);

    SqlHandlerConfig config = mock(SqlHandlerConfig.class);
    PlanCacheKey cachedKey = mock(PlanCacheKey.class);

    Assert.assertNull(subject.fallThroughPlanCache.getIfPresentAndValid(config, cachedKey));

    verify(subject.planCache1, times(1)).getIfPresentAndValid(config, cachedKey);
    verify(subject.planCache2, times(1)).getIfPresentAndValid(config, cachedKey);
  }

  @Test
  public void testGetIfPresentAndValidPresent() {
    Subject subject = new Subject();
    PlanCacheEntry entry = mock(PlanCacheEntry.class);
    when(subject.planCache1.getIfPresentAndValid(any(), any())).thenReturn(entry);
    when(subject.planCache2.getIfPresentAndValid(any(), any())).thenReturn(null);

    SqlHandlerConfig config = mock(SqlHandlerConfig.class);
    PlanCacheKey cachedKey = mock(PlanCacheKey.class);

    assertEquals(entry, subject.fallThroughPlanCache.getIfPresentAndValid(config, cachedKey));

    verify(subject.planCache1, times(1)).getIfPresentAndValid(config, cachedKey);
    verifyNoInteractions(subject.planCache2);
  }

  @Test
  public void testGetIfPresentAndValidPassThrough() {
    Subject subject = new Subject();
    PlanCacheEntry entry = mock(PlanCacheEntry.class);
    when(subject.planCache1.getIfPresentAndValid(any(), any())).thenReturn(null);
    when(subject.planCache2.getIfPresentAndValid(any(), any())).thenReturn(entry);

    SqlHandlerConfig config = mock(SqlHandlerConfig.class);
    PlanCacheKey cachedKey = mock(PlanCacheKey.class);

    assertEquals(entry, subject.fallThroughPlanCache.getIfPresentAndValid(config, cachedKey));

    verify(subject.planCache1, times(1)).getIfPresentAndValid(config, cachedKey);
    verify(subject.planCache2, times(1)).getIfPresentAndValid(config, cachedKey);
  }

  private static class Subject {
    PlanCache planCache1 = mock(PlanCache.class);
    PlanCache planCache2 = mock(PlanCache.class);

    FallThroughPlanCache fallThroughPlanCache =
        new FallThroughPlanCache(ImmutableList.of(planCache1, planCache2));
  }
}
