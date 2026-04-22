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
package com.dremio.sabot.op.receiver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import com.dremio.common.config.SabotConfig;
import com.dremio.config.DremioConfig;
import com.dremio.exec.planner.fragment.EndpointsIndex;
import com.dremio.exec.proto.CoordExecRPC;
import com.dremio.exec.proto.CoordExecRPC.MinorFragmentIndexEndpoint;
import com.dremio.exec.proto.CoordinationProtos.NodeEndpoint;
import com.dremio.exec.proto.ExecProtos;
import com.dremio.exec.proto.UserBitShared.QueryId;
import com.dremio.exec.record.ArrowRecordBatchLoader;
import com.dremio.exec.record.FragmentWritableBatch;
import com.dremio.exec.record.VectorContainer;
import com.dremio.exec.server.options.OptionValidatorListingImpl;
import com.dremio.exec.server.options.SessionOptionManagerImpl;
import com.dremio.options.OptionManager;
import com.dremio.options.OptionValidatorListing;
import com.dremio.options.OptionValue;
import com.dremio.options.OptionValue.OptionType;
import com.dremio.options.impl.DefaultOptionManager;
import com.dremio.options.impl.OptionManagerWrapper;
import com.dremio.sabot.exec.fragment.FragmentWorkQueue;
import com.dremio.sabot.exec.rpc.AckSender;
import com.dremio.sabot.exec.rpc.TunnelProvider;
import com.dremio.sabot.threads.sharedres.SharedResourceGroup;
import com.dremio.sabot.threads.sharedres.SharedResourceManager;
import com.dremio.service.scheduler.SchedulerService;
import com.dremio.service.spill.DefaultSpillServiceOptions;
import com.dremio.service.spill.SpillService;
import com.dremio.service.spill.SpillServiceImpl;
import com.dremio.test.AllocatorRule;
import com.dremio.test.DremioTest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Provider;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.OutOfMemoryException;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;

public class TestAbstractDataCollector extends DremioTest {

  @Rule public final AllocatorRule allocatorRule = AllocatorRule.defaultAllocator();

  @Test
  public void testReserveMemory() {
    SharedResourceGroup resourceGroup = mock(SharedResourceGroup.class);
    SabotConfig config = mock(SabotConfig.class);
    FragmentWorkQueue workQueue = mock(FragmentWorkQueue.class);
    TunnelProvider tunnelProvider = mock(TunnelProvider.class);

    EndpointsIndex endpointsIndex =
        new EndpointsIndex(
            Arrays.asList(
                NodeEndpoint.newBuilder().setAddress("localhost").setFabricPort(12345).build(),
                NodeEndpoint.newBuilder().setAddress("localhost").setFabricPort(12345).build()));
    List<CoordExecRPC.MinorFragmentIndexEndpoint> list =
        Arrays.asList(
            MinorFragmentIndexEndpoint.newBuilder()
                .setEndpointIndex(0)
                .setMinorFragmentId(0)
                .build(),
            MinorFragmentIndexEndpoint.newBuilder()
                .setEndpointIndex(0)
                .setMinorFragmentId(0)
                .build());

    CoordExecRPC.Collector collector =
        CoordExecRPC.Collector.newBuilder()
            .setIsSpooling(true)
            .setOppositeMajorFragmentId(3)
            .setSupportsOutOfOrder(true)
            .addAllIncomingMinorFragmentIndex(list)
            .build();
    ExecProtos.FragmentHandle handle =
        ExecProtos.FragmentHandle.newBuilder()
            .setMajorFragmentId(2323)
            .setMinorFragmentId(234234)
            .build();
    BufferAllocator allocator =
        allocatorRule.newAllocator("test-abstract-data-collector", 0, 2000000);
    boolean outOfMemory = false;
    final SchedulerService schedulerService = Mockito.mock(SchedulerService.class);
    final SpillService spillService =
        new SpillServiceImpl(
            DremioConfig.create(null, config),
            new DefaultSpillServiceOptions(),
            new Provider<SchedulerService>() {
              @Override
              public SchedulerService get() {
                return schedulerService;
              }
            });
    final OptionManager options =
        new DefaultOptionManager(new OptionValidatorListingImpl(CLASSPATH_SCAN_RESULT));
    try {
      spillService.start();
    } catch (Exception e) {
      fail("Unable to start spill service");
    }
    try {
      AbstractDataCollector dataCollector =
          new AbstractDataCollector(
              resourceGroup,
              true,
              collector,
              10240,
              allocator,
              config,
              options,
              handle,
              workQueue,
              tunnelProvider,
              spillService,
              endpointsIndex) {
            @Override
            protected RawBatchBuffer getBuffer(int minorFragmentId) {
              return null;
            }
          };
    } catch (OutOfMemoryException e) {
      /* Each minor fragment will reserve an arrow buffer with 1024*1024 size. 2*1024*1024 memory is required
       * because there are two minor fragments. Allocator is limited to 2000000, so OutOfMemoryException is
       * expected when it tries to allocate the second arrow buffer, but it should not cause memory leak when
       * allocator is closed.
       */
      // The first allocation should succeed
      assertEquals(allocator.getPeakMemoryAllocation(), 1024 * 1024);

      outOfMemory = true;
    }

    // Verify that it runs out of memory for second allocation.
    assertTrue(outOfMemory);
    /* We are verifying that the first allocated arrow buffer should be released if the second allocation fail,
     * so no memory leak report is expected.
     */
    allocator.close();
  }

  private SharedResourceGroup getSharedResourceGrp() {
    SharedResourceManager resourceManager =
        SharedResourceManager.newBuilder().addGroup("test").build();
    return resourceManager.getGroup("test");
  }

  @Test
  public void testUnlimitedBatchLimits() {
    final OptionValidatorListing optionValidatorListing =
        new OptionValidatorListingImpl(CLASSPATH_SCAN_RESULT);
    final OptionManager options =
        OptionManagerWrapper.Builder.newBuilder()
            .withOptionManager(new DefaultOptionManager(optionValidatorListing))
            .withOptionManager(new SessionOptionManagerImpl(optionValidatorListing))
            .build();

    options.setOption(OptionValue.createLong(OptionType.SESSION, "exec.buffer.batch.limit", 3));
    testBatchesAccepted(options, 3, 10, 5);

    options.setOption(OptionValue.createLong(OptionType.SESSION, "exec.buffer.batch.limit", 100));
    options.setOption(OptionValue.createLong(OptionType.SESSION, "exec.buffer.size", 1));
    testBatchesAccepted(options, 3, 3, 5);

    options.setOption(OptionValue.createLong(OptionType.SESSION, "exec.buffer.batch.limit", 100));
    options.setOption(OptionValue.createLong(OptionType.SESSION, "exec.buffer.size", 6));
    options.setOption(OptionValue.createLong(OptionType.SESSION, "exec.buffer.byte.limit", 2049));
    testBatchesAccepted(options, 2, 3, 5);
  }

  private void testBatchesAccepted(
      OptionManager options, long target, int numFragments, long numBatches) {
    SharedResourceGroup resourceGroup = getSharedResourceGrp();
    SabotConfig config = mock(SabotConfig.class);
    FragmentWorkQueue workQueue = mock(FragmentWorkQueue.class);
    TunnelProvider tunnelProvider = mock(TunnelProvider.class);

    EndpointsIndex endpointsIndex =
        new EndpointsIndex(
            Arrays.asList(
                NodeEndpoint.newBuilder().setAddress("localhost").setFabricPort(12345).build(),
                NodeEndpoint.newBuilder().setAddress("localhost").setFabricPort(12345).build()));
    List<CoordExecRPC.MinorFragmentIndexEndpoint> list =
        Arrays.asList(
            MinorFragmentIndexEndpoint.newBuilder()
                .setEndpointIndex(0)
                .setMinorFragmentId(0)
                .build(),
            MinorFragmentIndexEndpoint.newBuilder()
                .setEndpointIndex(0)
                .setMinorFragmentId(0)
                .build());

    CoordExecRPC.Collector collector =
        CoordExecRPC.Collector.newBuilder()
            .setIsSpooling(false)
            .setOppositeMajorFragmentId(3)
            .setSupportsOutOfOrder(true)
            .addAllIncomingMinorFragmentIndex(list)
            .build();
    ExecProtos.FragmentHandle handle =
        ExecProtos.FragmentHandle.newBuilder()
            .setMajorFragmentId(2323)
            .setMinorFragmentId(234234)
            .build();
    BufferAllocator allocator =
        allocatorRule.newAllocator("test-abstract-data-collector", 0, 2000000);

    final SchedulerService schedulerService = Mockito.mock(SchedulerService.class);
    AbstractDataCollector dataCollector =
        new AbstractDataCollector(
            resourceGroup,
            false,
            collector,
            numFragments,
            allocator,
            config,
            options,
            handle,
            workQueue,
            tunnelProvider,
            null,
            endpointsIndex) {
          @Override
          protected RawBatchBuffer getBuffer(int minorFragmentId) {
            return buffers[0];
          }
        };

    AtomicInteger ackedSends = new AtomicInteger(0);
    VectorContainer container = new VectorContainer(allocator);
    container.setRecordCount(5);
    container.buildSchema();

    FragmentWritableBatch fragmentWritableBatch =
        FragmentWritableBatch.create(QueryId.getDefaultInstance(), 0, 0, 0, container, 0);
    container.zeroVectors();

    List<ArrowBuf> bufs = new ArrayList<>();

    for (long x = 0; x < numBatches; x++) {
      ArrowBuf buffer = allocator.buffer(1024);
      buffer.writeBytes(new byte[1024]);
      bufs.add(buffer);

      ArrowRecordBatchLoader loader = new ArrowRecordBatchLoader(container);
      RawFragmentBatch rawFragmentBatch =
          new RawFragmentBatch(
              fragmentWritableBatch.getHeader(),
              buffer,
              new AckSender() {
                @Override
                public void sendOk() {
                  ackedSends.incrementAndGet();
                }
              });

      dataCollector.batchArrived(234234, rawFragmentBatch);
    }

    assertEquals(target, ackedSends.get());

    // CLEANUP
    try {
      dataCollector.getBuffer(234234).close();
    } catch (Exception e) {
      fail("Unexpected Exception: " + e.toString());
    }

    bufs.forEach(ArrowBuf::close);

    /* We are verifying that the first allocated arrow buffer should be released if the second allocation fail,
     * so no memory leak report is expected.
     */
    allocator.close();
  }
}
