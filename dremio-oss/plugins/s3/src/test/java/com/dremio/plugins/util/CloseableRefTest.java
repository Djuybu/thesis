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

/** Tests for {@link CloseableRef} */
public class CloseableRefTest {

  @Test
  public void testSingleRef() {
    final AtomicBoolean closedFlag = new AtomicBoolean(false);
    final CloseableRef<AutoCloseable> ref =
        new CloseableRef<>((AutoCloseable) () -> closedFlag.set(true));
    AutoCloseables.close(RuntimeException.class, ref);
    assertTrue(closedFlag.get());
  }

  @Test
  public void testMultiRef() {
    final AtomicBoolean closedFlag = new AtomicBoolean(false);
    final CloseableRef<AutoCloseable> ref =
        new CloseableRef<>((AutoCloseable) () -> closedFlag.set(true));
    IntStream.range(0, 10).forEach(i -> ref.acquireRef());
    IntStream.range(0, 10).forEach(i -> AutoCloseables.close(RuntimeException.class, ref));
    assertFalse(closedFlag.get());

    AutoCloseables.close(RuntimeException.class, ref);
    assertTrue(closedFlag.get());
  }

  @Test(expected = NullPointerException.class)
  public void testGetAfterFinalClose() {
    final CloseableRef<AutoCloseable> ref = new CloseableRef<>((AutoCloseable) () -> {});
    AutoCloseables.close(RuntimeException.class, ref);
    ref.acquireRef();
  }

  @Test
  public void testIdempotentClose() {
    final AtomicInteger closedCount = new AtomicInteger(0);
    final CloseableRef<AutoCloseable> ref =
        new CloseableRef<>((AutoCloseable) () -> closedCount.incrementAndGet());
    assertEquals(closedCount.get(), 0);
    AutoCloseables.close(RuntimeException.class, ref);
    assertEquals(closedCount.get(), 1);
    AutoCloseables.close(RuntimeException.class, ref);
    assertEquals(closedCount.get(), 1);
  }

  @Test
  public void testMultiThreaded() throws InterruptedException {
    final AtomicBoolean closedFlag = new AtomicBoolean(false);
    final CloseableRef<AutoCloseable> ref =
        new CloseableRef<>((AutoCloseable) () -> closedFlag.set(true));

    final int nThreads = 32;

    final ExecutorService executorService = Executors.newFixedThreadPool(nThreads);
    IntStream.range(0, 5 * nThreads)
        .forEach(
            i -> {
              executorService.submit(
                  () -> {
                    ref.acquireRef();
                    ref.acquireRef();
                    ref.acquireRef();
                    ref.acquireRef();
                    ref.acquireRef();

                    AutoCloseables.close(RuntimeException.class, ref);
                    AutoCloseables.close(RuntimeException.class, ref);
                    AutoCloseables.close(RuntimeException.class, ref);
                    AutoCloseables.close(RuntimeException.class, ref);
                    AutoCloseables.close(RuntimeException.class, ref);
                  });
            });

    executorService.shutdown();
    assertTrue(executorService.awaitTermination(10, TimeUnit.SECONDS));
    assertFalse(closedFlag.get());

    AutoCloseables.close(RuntimeException.class, ref);
    assertTrue(closedFlag.get());
  }
}
