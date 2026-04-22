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
package com.dremio.exec;

import static org.mockito.Mockito.mock;

import com.dremio.common.AutoCloseables;
import com.dremio.common.JULBridge;
import com.dremio.common.utils.protos.QueryWritableBatch;
import com.dremio.exec.expr.fn.FunctionImplementationRegistry;
import com.dremio.exec.ops.QueryContext;
import com.dremio.exec.proto.GeneralRPCProtos.Ack;
import com.dremio.exec.proto.UserBitShared.QueryResult;
import com.dremio.exec.rpc.Acks;
import com.dremio.exec.rpc.RpcException;
import com.dremio.exec.rpc.RpcOutcomeListener;
import com.dremio.options.OptionManager;
import com.dremio.sabot.rpc.user.UserRPCServer.UserClientConnection;
import com.dremio.sabot.rpc.user.UserSession;
import com.dremio.test.DremioTest;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocatorFactory;
import org.junit.After;
import org.junit.Before;

public class ExecTest extends DremioTest {

  private BufferAllocator rootAllocator;
  protected BufferAllocator allocator;

  protected static FunctionImplementationRegistry FUNCTIONS() {
    return FunctionImplementationRegistry.create(
        DEFAULT_SABOT_CONFIG, CLASSPATH_SCAN_RESULT, mock(OptionManager.class), false);
  }

  protected static FunctionImplementationRegistry DECIMAL_FUNCTIONS() {
    return FunctionImplementationRegistry.create(
        DEFAULT_SABOT_CONFIG, CLASSPATH_SCAN_RESULT, mock(OptionManager.class), true);
  }

  static {
    JULBridge.configure();
  }

  @Before
  public void initAllocators() {
    rootAllocator = RootAllocatorFactory.newRoot(DEFAULT_SABOT_CONFIG);
    allocator =
        rootAllocator.newChildAllocator(testName.getMethodName(), 0, rootAllocator.getLimit());
  }

  @After
  public void clear() throws Exception {
    AutoCloseables.close(allocator, rootAllocator);
  }

  /**
   * @return pre-created BufferAllocator for the currently running test method
   */
  protected BufferAllocator getTestAllocator() {
    return allocator;
  }

  public static UserClientConnection mockUserClientConnection(QueryContext context) {
    final UserSession session = context != null ? context.getSession() : mock(UserSession.class);
    return new UserClientConnection() {

      @Override
      public void addTerminationListener(
          GenericFutureListener<? extends Future<? super Void>> listener) {}

      @Override
      public void removeTerminationListener(
          GenericFutureListener<? extends Future<? super Void>> listener) {}

      @Override
      public UserSession getSession() {
        return session;
      }

      @Override
      public void sendResult(RpcOutcomeListener<Ack> listener, QueryResult result) {
        listener.success(Acks.OK, null);
      }

      @Override
      public void sendData(RpcOutcomeListener<Ack> listener, QueryWritableBatch result) {
        try {
          AutoCloseables.close((AutoCloseable[]) result.getBuffers());
          listener.success(Acks.OK, null);
        } catch (Exception ex) {
          listener.failed(new RpcException(ex));
        }
      }
    };
  }
}
