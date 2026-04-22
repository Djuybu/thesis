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
package com.dremio.sabot.rpc.offloader;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

import com.dremio.common.SuppressForbidden;
import com.dremio.common.concurrent.ContextMigratingExecutorService;
import com.dremio.common.config.SabotConfig;
import com.dremio.sabot.rpc.CoordExecService;
import com.dremio.services.fabric.api.FabricService;
import com.google.inject.Provider;
import java.lang.reflect.Field;
import org.apache.arrow.memory.BufferAllocator;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class TestRpcOffloadPool {

  @Mock private BufferAllocator allocator;

  @Mock
  private ContextMigratingExecutorService.ContextMigratingCloseableExecutorService
      closeableThreadPool;

  @Mock private FabricService fabricService;
  @Mock private Provider<FabricService> fabricServiceProvider;

  private CoordExecService coordExecService;

  @Before
  public void setUp() {
    MockitoAnnotations.openMocks(this);
    when(allocator.newChildAllocator(anyString(), anyLong(), anyLong())).thenReturn(allocator);
    when(fabricServiceProvider.get()).thenReturn(fabricService);
    coordExecService =
        new CoordExecService(
            mock(SabotConfig.class),
            allocator,
            fabricServiceProvider,
            mock(Provider.class),
            mock(Provider.class),
            mock(Provider.class),
            mock(Provider.class),
            mock(Provider.class));
  }

  @SuppressForbidden
  @Test
  public void testRpcOffloadPoolInitialization() throws Exception {
    coordExecService.start();

    Field rpcOffloadPoolField = CoordExecService.class.getDeclaredField("rpcOffloadPool");
    rpcOffloadPoolField.setAccessible(true);
    Object rpcOffloadPool = rpcOffloadPoolField.get(coordExecService);

    assertNotNull(rpcOffloadPool);
    assertTrue(
        rpcOffloadPool
            instanceof ContextMigratingExecutorService.ContextMigratingCloseableExecutorService);
  }

  @SuppressForbidden
  @Test
  public void testRpcOffloadPoolShutdown() throws Exception {
    coordExecService.start();
    // Use reflection to set the rpcOffloadPool to use the mocked closeableThreadPool
    Field rpcOffloadPoolField = CoordExecService.class.getDeclaredField("rpcOffloadPool");
    rpcOffloadPoolField.setAccessible(true);
    rpcOffloadPoolField.set(
        coordExecService,
        new ContextMigratingExecutorService.ContextMigratingCloseableExecutorService<>(
            closeableThreadPool));

    coordExecService.close();
    verify(closeableThreadPool, times(1)).close();
  }
}
