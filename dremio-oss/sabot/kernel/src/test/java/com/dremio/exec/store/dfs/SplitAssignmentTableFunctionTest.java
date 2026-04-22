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
package com.dremio.exec.store.dfs;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.dremio.common.expression.CompleteType;
import com.dremio.config.DremioConfig;
import com.dremio.exec.ExecConstants;
import com.dremio.exec.ExecTest;
import com.dremio.exec.catalog.StoragePluginId;
import com.dremio.exec.physical.base.OpProps;
import com.dremio.exec.physical.config.MinorFragmentEndpoint;
import com.dremio.exec.physical.config.TableFunctionConfig;
import com.dremio.exec.physical.config.TableFunctionContext;
import com.dremio.exec.planner.physical.HashPrelUtil;
import com.dremio.exec.proto.CoordinationProtos;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.VectorContainer;
import com.dremio.exec.store.RecordReader;
import com.dremio.exec.store.SplitIdentity;
import com.dremio.exec.store.StoragePlugin;
import com.dremio.exec.store.cache.BlockLocationsCacheManager;
import com.dremio.exec.store.iceberg.IcebergUtils;
import com.dremio.exec.store.iceberg.SupportsFsCreation;
import com.dremio.exec.store.iceberg.SupportsIcebergRootPointer;
import com.dremio.io.file.FileSystem;
import com.dremio.options.OptionManager;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.context.OperatorStats;
import com.dremio.sabot.exec.fragment.FragmentExecutionContext;
import com.dremio.service.namespace.dataset.proto.PartitionProtobuf;
import com.dremio.service.namespace.proto.EntityId;
import com.dremio.service.namespace.source.proto.SourceConfig;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.complex.StructVector;
import org.apache.arrow.vector.complex.impl.NullableStructWriter;
import org.apache.arrow.vector.types.pojo.Field;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/** Test for SplitAssignmentTableFunction */
public class SplitAssignmentTableFunctionTest extends ExecTest {

  VectorContainer incoming;
  VectorContainer outgoing;
  ArrowBuf tmpBuf;

  @Before
  public void setup() {
    incoming = new VectorContainer(allocator);
    outgoing = new VectorContainer(allocator); // closed by the table function
    incoming.addOrGet(Field.nullable(RecordReader.SPLIT_IDENTITY, CompleteType.STRUCT.getType()));
    incoming.buildSchema();
    tmpBuf = allocator.buffer(4096);
  }

  @After
  public void tearDown() {
    incoming.close();
    tmpBuf.close();
  }

  @Test
  public void testSplitsAssignmentWithBlockLocations() throws Exception {
    SplitAssignmentTableFunction splitAssignmentTableFunction =
        spy(getSplitAssignmentTableFunction());
    // create splitidentities
    // 100 len blocks of the file located on two hosts 10.10.10.10/20 alternatively
    // splits also 100 sized

    PartitionProtobuf.BlockLocationsList blockLocationsList =
        PartitionProtobuf.BlockLocationsList.newBuilder()
            .addBlockLocations(
                PartitionProtobuf.BlockLocations.newBuilder()
                    .addHosts("10.10.10.10")
                    .setOffset(0)
                    .setSize(100)
                    .build())
            .addBlockLocations(
                PartitionProtobuf.BlockLocations.newBuilder()
                    .addHosts("10.10.10.20")
                    .setOffset(100)
                    .setSize(100)
                    .build())
            .addBlockLocations(
                PartitionProtobuf.BlockLocations.newBuilder()
                    .addHosts("10.10.10.10")
                    .setOffset(200)
                    .setSize(100)
                    .build())
            .addBlockLocations(
                PartitionProtobuf.BlockLocations.newBuilder()
                    .addHosts("10.10.10.20")
                    .setOffset(300)
                    .setSize(100)
                    .build())
            .addBlockLocations(
                PartitionProtobuf.BlockLocations.newBuilder()
                    .addHosts("10.10.10.10")
                    .setOffset(400)
                    .setSize(100)
                    .build())
            .addBlockLocations(
                PartitionProtobuf.BlockLocations.newBuilder()
                    .addHosts("10.10.10.20")
                    .setOffset(500)
                    .setSize(100)
                    .build())
            .addBlockLocations(
                PartitionProtobuf.BlockLocations.newBuilder()
                    .addHosts("10.10.10.10")
                    .setOffset(600)
                    .setSize(100)
                    .build())
            .addBlockLocations(
                PartitionProtobuf.BlockLocations.newBuilder()
                    .addHosts("10.10.10.20")
                    .setOffset(700)
                    .setSize(100)
                    .build())
            .build();

    doReturn(blockLocationsList)
        .when(splitAssignmentTableFunction)
        .getFileBlockLocations(anyString(), anyLong());

    List<SplitIdentity> splitIdentities =
        IntStream.range(0, 8)
            .mapToObj(i -> new SplitIdentity("/path/to/file", i * 100, 100, 1000))
            .collect(Collectors.toList());
    int numRecords = splitIdentities.size();
    StructVector splitVector =
        incoming.getValueAccessorById(StructVector.class, 0).getValueVector();
    NullableStructWriter writer = splitVector.getWriter();
    for (int i = 0; i < numRecords; ++i) {
      IcebergUtils.writeSplitIdentity(writer, i, splitIdentities.get(i), tmpBuf);
    }
    incoming.setAllCount(numRecords);

    splitAssignmentTableFunction.startRow(0);
    splitAssignmentTableFunction.processRow(0, Integer.MAX_VALUE);

    // verify the split assignment
    IntVector hashVector = outgoing.getValueAccessorById(IntVector.class, 1).getValueVector();
    Map<Integer, Integer> fragmentSplitCount = new HashMap<>();
    for (int i = 0; i < numRecords; ++i) {
      fragmentSplitCount.merge(hashVector.get(i), 1, Integer::sum);
    }

    // all fragments are assigned
    Assert.assertEquals(4, fragmentSplitCount.size());
    // all are equally distributed
    for (int i = 1; i < fragmentSplitCount.size(); ++i) {
      Assert.assertEquals(fragmentSplitCount.get(i), fragmentSplitCount.get(i - 1));
    }

    splitAssignmentTableFunction.close();
  }

  @Test
  public void testSplitsAssignmentWithNoBlockLocations() throws Exception {
    SplitAssignmentTableFunction splitAssignmentTableFunction =
        spy(getSplitAssignmentTableFunction());

    doReturn(null).when(splitAssignmentTableFunction).getFileBlockLocations(anyString(), anyLong());

    // create splitidentities
    List<SplitIdentity> splitIdentities =
        IntStream.range(0, 1024)
            .mapToObj(i -> new SplitIdentity("path", i * 100, 100, 102400))
            .collect(Collectors.toList());
    int numRecords = splitIdentities.size();
    StructVector splitVector =
        incoming.getValueAccessorById(StructVector.class, 0).getValueVector();
    NullableStructWriter writer = splitVector.getWriter();
    for (int i = 0; i < numRecords; ++i) {
      IcebergUtils.writeSplitIdentity(writer, i, splitIdentities.get(i), tmpBuf);
    }
    incoming.setAllCount(numRecords);

    splitAssignmentTableFunction.startRow(0);
    splitAssignmentTableFunction.processRow(0, Integer.MAX_VALUE);

    // verify the split assignment
    IntVector hashVector = outgoing.getValueAccessorById(IntVector.class, 1).getValueVector();
    Map<Integer, Integer> fragmentSplitCount = new HashMap<>();
    for (int i = 0; i < numRecords; ++i) {
      fragmentSplitCount.merge(hashVector.get(i), 1, Integer::sum);
    }

    // all fragments are assigned
    Assert.assertEquals(4, fragmentSplitCount.size());
    int expectedSplitsPerMinorFrag = numRecords / fragmentSplitCount.size();
    for (int i = 0; i < fragmentSplitCount.size(); ++i) {
      // all are approximately equally distributed
      Assert.assertTrue(
          Math.abs(fragmentSplitCount.get(i) - expectedSplitsPerMinorFrag)
              < 0.05 * expectedSplitsPerMinorFrag);
    }

    splitAssignmentTableFunction.close();
  }

  @Test
  public void testSplitsAssignmentWithPartLocalAndPartRemoteAssignment() throws Exception {
    SplitAssignmentTableFunction splitAssignmentTableFunction =
        spy(getSplitAssignmentTableFunction());

    // blocks distributed in 4 hosts (2 of them in target endpoints)
    // block size = 50 (split size is 100)
    // each block is duplicated in two hosts;
    String[] hosts = new String[] {"10.10.10.10", "10.10.10.20", "10.10.10.30", "10.10.10.40"};
    List<PartitionProtobuf.BlockLocations> blockLocations =
        IntStream.range(0, 1000)
            .mapToObj(
                i ->
                    PartitionProtobuf.BlockLocations.newBuilder()
                        .addHosts(hosts[i % 4])
                        .addHosts(hosts[(i + 1) % 4])
                        .setOffset(i * 50)
                        .setSize(50)
                        .build())
            .collect(Collectors.toList());

    PartitionProtobuf.BlockLocationsList blockLocationsList =
        PartitionProtobuf.BlockLocationsList.newBuilder()
            .addAllBlockLocations(blockLocations)
            .build();

    doReturn(blockLocationsList)
        .when(splitAssignmentTableFunction)
        .getFileBlockLocations(anyString(), anyLong());

    // create splitidentities
    List<SplitIdentity> splitIdentities =
        IntStream.range(0, 500)
            .mapToObj(i -> new SplitIdentity("/path/to/file", i * 100, 100, 50000))
            .collect(Collectors.toList());
    int numRecords = splitIdentities.size();
    StructVector splitVector =
        incoming.getValueAccessorById(StructVector.class, 0).getValueVector();
    NullableStructWriter writer = splitVector.getWriter();
    for (int i = 0; i < numRecords; ++i) {
      IcebergUtils.writeSplitIdentity(writer, i, splitIdentities.get(i), tmpBuf);
    }
    incoming.setAllCount(numRecords);

    splitAssignmentTableFunction.startRow(0);
    splitAssignmentTableFunction.processRow(0, Integer.MAX_VALUE);

    // verify the split assignment
    IntVector hashVector = outgoing.getValueAccessorById(IntVector.class, 1).getValueVector();
    Map<Integer, Integer> fragmentSplitCount = new HashMap<>();
    for (int i = 0; i < numRecords; ++i) {
      fragmentSplitCount.merge(hashVector.get(i), 1, Integer::sum);
    }

    // all fragments are assigned
    Assert.assertEquals(4, fragmentSplitCount.size());
    // all are equally distributed
    int expectedSplitsPerMinorFrag = numRecords / fragmentSplitCount.size();
    for (int i = 0; i < fragmentSplitCount.size(); ++i) {
      Assert.assertEquals(fragmentSplitCount.get(i).intValue(), expectedSplitsPerMinorFrag);
    }

    splitAssignmentTableFunction.close();
  }

  @Test
  public void testCreateFSWithUserNameAndUserId() throws Exception {
    // Setup
    final String testUserName = "testUser";
    final String testUserId = "test-user-id";
    final String testFilePath = "/test/file/path";
    final long testFileSize = 1024L;

    // Setup
    FileSystem fileSystem = mock(FileSystem.class);

    StoragePlugin plugin =
        mock(
            StoragePlugin.class,
            withSettings()
                .extraInterfaces(SupportsFsCreation.class, SupportsIcebergRootPointer.class));
    when(((SupportsFsCreation) plugin).createFS(any(SupportsFsCreation.Builder.class)))
        .thenReturn(fileSystem);

    StoragePluginId pluginId = mock(StoragePluginId.class);
    SourceConfig sourceConfig = mock(SourceConfig.class);
    when(sourceConfig.getName()).thenReturn("testPlugin");
    when(sourceConfig.getId()).thenReturn(new EntityId("testPluginId"));
    when(pluginId.getConfig()).thenReturn(sourceConfig);

    OpProps opProps = mock(OpProps.class);
    when(opProps.getUserName()).thenReturn(testUserName);
    when(opProps.getUserId()).thenReturn(testUserId);

    TableFunctionContext functionContext = mock(TableFunctionContext.class);
    when(functionContext.getPluginId()).thenReturn(pluginId);

    TableFunctionConfig tableFunctionConfig = mock(TableFunctionConfig.class);
    when(tableFunctionConfig.getFunctionContext()).thenReturn(functionContext);

    OperatorContext operatorContext = mock(OperatorContext.class);
    OperatorStats operatorStats = mock(OperatorStats.class);
    when(operatorContext.getStats()).thenReturn(operatorStats);

    DremioConfig dremioConfig = mock(DremioConfig.class);
    when(operatorContext.getDremioConfig()).thenReturn(dremioConfig);

    OptionManager optionManager = mock(OptionManager.class);
    when(operatorContext.getOptions()).thenReturn(optionManager);
    when(optionManager.getOption(ExecConstants.ASSIGNMENT_CREATOR_BALANCE_FACTOR)).thenReturn(1.5);

    CoordinationProtos.NodeEndpoint endpoint =
        CoordinationProtos.NodeEndpoint.newBuilder()
            .setAddress("localhost")
            .setFabricPort(12345)
            .build();
    MinorFragmentEndpoint minorFragmentEndpoint = new MinorFragmentEndpoint(0, endpoint);
    when(operatorContext.getMinorFragmentEndpoints())
        .thenReturn(Collections.singletonList(minorFragmentEndpoint));

    FragmentExecutionContext fragmentExecutionContext = mock(FragmentExecutionContext.class);
    when(fragmentExecutionContext.getStoragePlugin(pluginId)).thenReturn(plugin);

    BlockLocationsCacheManager cacheManager = mock(BlockLocationsCacheManager.class);
    PartitionProtobuf.BlockLocationsList blockLocationsList =
        mock(PartitionProtobuf.BlockLocationsList.class);
    when(cacheManager.createIfAbsent(testFilePath, testFileSize)).thenReturn(blockLocationsList);

    SplitAssignmentTableFunction tableFunction =
        new SplitAssignmentTableFunction(
            fragmentExecutionContext, operatorContext, opProps, tableFunctionConfig);

    // Act
    tableFunction.getFs(testFilePath);

    // Capture the Builder argument passed to createFS
    ArgumentCaptor<SupportsFsCreation.Builder> builderCaptor =
        ArgumentCaptor.forClass(SupportsFsCreation.Builder.class);
    verify((SupportsFsCreation) plugin).createFS(builderCaptor.capture());

    SupportsFsCreation.Builder capturedBuilder = builderCaptor.getValue();

    // Assert
    // Verify userName and userId were correctly passed
    assertEquals("userName should match", testUserName, capturedBuilder.userName());
    assertEquals("userId should match", testUserId, capturedBuilder.userId());
    assertEquals("filePath should match", testFilePath, capturedBuilder.filePath());
  }

  private SplitAssignmentTableFunction getSplitAssignmentTableFunction() throws Exception {
    OperatorContext operatorContext = mock(OperatorContext.class);
    OptionManager optionManager = mock(OptionManager.class);
    when(operatorContext.getOptions()).thenReturn(optionManager);
    when(optionManager.getOption(ExecConstants.ASSIGNMENT_CREATOR_BALANCE_FACTOR)).thenReturn(1.5);
    when(operatorContext.createOutputVectorContainer()).thenReturn(outgoing);
    OperatorStats operatorStats = mock(OperatorStats.class);
    when(operatorContext.getStats()).thenReturn(operatorStats);
    TableFunctionConfig tableFunctionConfig = mock(TableFunctionConfig.class);
    BatchSchema outputSchema =
        BatchSchema.newBuilder()
            .addField(Field.nullable(RecordReader.SPLIT_IDENTITY, CompleteType.STRUCT.getType()))
            .addField(Field.nullable(HashPrelUtil.HASH_EXPR_NAME, CompleteType.INT.getType()))
            .build();
    when(tableFunctionConfig.getOutputSchema()).thenReturn(outputSchema);
    TableFunctionContext tableFunctionContext = mock(TableFunctionContext.class);
    when(tableFunctionConfig.getFunctionContext()).thenReturn(tableFunctionContext);
    StoragePluginId storagePluginId = mock(StoragePluginId.class);
    when(tableFunctionContext.getPluginId()).thenReturn(storagePluginId);
    FragmentExecutionContext fec = mock(FragmentExecutionContext.class);
    OpProps opProps = mock(OpProps.class);
    when(fec.getStoragePlugin(storagePluginId)).thenReturn(mock(FileSystemPlugin.class));

    // Two nodes with two minor fragment in each - total 4 minor fragments
    CoordinationProtos.NodeEndpoint endpoint1 =
        CoordinationProtos.NodeEndpoint.newBuilder()
            .setAddress("10.10.10.10")
            .setFabricPort(9000)
            .build();
    CoordinationProtos.NodeEndpoint endpoint2 =
        CoordinationProtos.NodeEndpoint.newBuilder()
            .setAddress("10.10.10.10")
            .setFabricPort(9000)
            .build();
    CoordinationProtos.NodeEndpoint endpoint3 =
        CoordinationProtos.NodeEndpoint.newBuilder()
            .setAddress("10.10.10.20")
            .setFabricPort(9000)
            .build();
    CoordinationProtos.NodeEndpoint endpoint4 =
        CoordinationProtos.NodeEndpoint.newBuilder()
            .setAddress("10.10.10.20")
            .setFabricPort(9000)
            .build();

    List<CoordinationProtos.NodeEndpoint> endpoints =
        Arrays.asList(endpoint1, endpoint2, endpoint3, endpoint4);
    List<MinorFragmentEndpoint> minorFragmentEndpoints =
        IntStream.range(0, endpoints.size())
            .mapToObj(i -> new MinorFragmentEndpoint(i, endpoints.get(i)))
            .collect(Collectors.toList());

    when(operatorContext.getMinorFragmentEndpoints()).thenReturn(minorFragmentEndpoints);

    // create SplitAssignmentTableFunction
    SplitAssignmentTableFunction splitAssignmentTableFunction =
        new SplitAssignmentTableFunction(fec, operatorContext, opProps, tableFunctionConfig);
    splitAssignmentTableFunction.setup(incoming);
    return splitAssignmentTableFunction;
  }
}
