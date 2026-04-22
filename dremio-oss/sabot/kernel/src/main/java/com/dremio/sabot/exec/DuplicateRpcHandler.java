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
package com.dremio.sabot.exec;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;

public class DuplicateRpcHandler<S extends StreamObserver<T>, T> {
  private S latestStreamObserver;
  private S originalStreamObserver;
  private RpcProgress rpcProgress;

  public DuplicateRpcHandler() {
    this.rpcProgress = RpcProgress.IN_PROGRESS;
  }

  public synchronized void replaceLatestStreamObserver(S incomingStreamObserver) {
    this.latestStreamObserver = incomingStreamObserver;
    if (originalStreamObserver == null) {
      this.originalStreamObserver = incomingStreamObserver;
    }
  }

  public synchronized void markAndSendAck(
      T ackMessage, Throwable throwable, S incomingStreamObserver) {
    if (originalStreamObserver == incomingStreamObserver) {
      if (rpcProgress.equals(RpcProgress.IN_PROGRESS)) {
        if (throwable != null) {
          rpcProgress = RpcProgress.ERROR;
        } else {
          rpcProgress = RpcProgress.COMPLETED;
        }
      }
    }
    sendAck(ackMessage, throwable);
  }

  private void sendAck(T ackMessage, Throwable throwable) {
    switch (rpcProgress) {
      case COMPLETED:
        {
          latestStreamObserver.onNext(ackMessage);
          latestStreamObserver.onCompleted();
          break;
        }
      case ERROR:
        {
          latestStreamObserver.onError(Status.INTERNAL.withCause(throwable).asRuntimeException());
          break;
        }
      default:
        {
          // operation is in progress. don't send ack just yet
          break;
        }
    }
  }

  private enum RpcProgress {
    IN_PROGRESS,
    ERROR,
    COMPLETED,
  }
}
