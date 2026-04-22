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
package com.dremio.plugins.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.dremio.common.AutoCloseables;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.Test;

/** Tests for {@link CloseableResource} */
public class CloseableResourceTest {

  @Test
  public void testSingleRef() {
    final AtomicBoolean closedFlag = new AtomicBoolean(false);
    final CloseableResource<Object> res =
        new CloseableResource<>(new Object(), o -> closedFlag.set(true));
    AutoCloseables.close(RuntimeException.class, res);
    assertTrue(closedFlag.get());
  }

  @Test
  public void testMultiRef() {
    final AtomicBoolean closedFlag = new AtomicBoolean(false);
    final CloseableResource<Object> res =
        new CloseableResource<>(new Object(), o -> closedFlag.set(true));
    IntStream.range(0, 10).forEach(i -> res.getResource());
    IntStream.range(0, 10).forEach(i -> AutoCloseables.close(RuntimeException.class, res));
    assertFalse(closedFlag.get());

    AutoCloseables.close(RuntimeException.class, res);
    assertTrue(closedFlag.get());
  }

  @Test(expected = NullPointerException.class)
  public void testGetAfterFinalClose() {
    final CloseableResource<Object> res = new CloseableResource<>(new Object(), o -> {});
    AutoCloseables.close(RuntimeException.class, res);
    res.getResource();
  }

  @Test
  public void testIdempotentClose() {
    final AtomicInteger closedCount = new AtomicInteger(0);
    final CloseableResource<Object> res =
        new CloseableResource<>(new Object(), o -> closedCount.incrementAndGet());
    assertEquals(closedCount.get(), 0);
    AutoCloseables.close(RuntimeException.class, res);
    assertEquals(closedCount.get(), 1);
    AutoCloseables.close(RuntimeException.class, res);
    assertEquals(closedCount.get(), 1);
  }

  @Test
  public void testMultiThreaded() throws InterruptedException {
    final AtomicBoolean closedFlag = new AtomicBoolean(false);
    final CloseableResource<Object> res =
        new CloseableResource<>(new Object(), o -> closedFlag.set(true));

    final int nThreads = 32;

    final ExecutorService executorService = Executors.newFixedThreadPool(nThreads);
    IntStream.range(0, 5 * nThreads)
        .forEach(
            i -> {
              executorService.submit(
                  () -> {
                    res.getResource();
                    res.getResource();
                    res.getResource();
                    res.getResource();
                    res.getResource();

                    AutoCloseables.close(RuntimeException.class, res);
                    AutoCloseables.close(RuntimeException.class, res);
                    AutoCloseables.close(RuntimeException.class, res);
                    AutoCloseables.close(RuntimeException.class, res);
                    AutoCloseables.close(RuntimeException.class, res);
                  });
            });

    executorService.shutdown();
    assertTrue(executorService.awaitTermination(10, TimeUnit.SECONDS));
    assertFalse(closedFlag.get());

    AutoCloseables.close(RuntimeException.class, res);
    assertTrue(closedFlag.get());
  }
}
