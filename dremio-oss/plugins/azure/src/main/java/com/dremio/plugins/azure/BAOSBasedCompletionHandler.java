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

package com.dremio.plugins.azure;

import java.io.ByteArrayOutputStream;
import java.nio.file.AccessDeniedException;
import org.asynchttpclient.AsyncCompletionHandlerBase;
import org.asynchttpclient.HttpResponseBodyPart;
import org.asynchttpclient.HttpResponseStatus;
import org.asynchttpclient.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.HttpStatusCode;

/** Process response via {@link ByteArrayOutputStream} */
class BAOSBasedCompletionHandler extends AsyncCompletionHandlerBase {
  private boolean requestFailed = false;
  private final ByteArrayOutputStream baos;
  private static final Logger logger = LoggerFactory.getLogger(BAOSBasedCompletionHandler.class);

  public BAOSBasedCompletionHandler(final ByteArrayOutputStream baos) {
    this.baos = baos;
  }

  @Override
  public State onStatusReceived(final HttpResponseStatus status) throws Exception {
    requestFailed = (status.getStatusCode() >= 400);
    return super.onStatusReceived(status);
  }

  @Override
  public Response onCompleted(final Response response) throws AccessDeniedException {
    if (requestFailed) {
      logger.error(
          "Error response received {} {}", response.getStatusCode(), response.getResponseBody());

      if (response.getStatusCode() == HttpStatusCode.FORBIDDEN) {
        throw new AccessDeniedException(response.getResponseBody());
      }
      throw new RuntimeException(response.getResponseBody());
    }
    return response;
  }

  @Override
  public State onBodyPartReceived(final HttpResponseBodyPart content) throws Exception {
    if (requestFailed) {
      return super.onBodyPartReceived(content);
    }
    baos.write(content.getBodyPartBytes());
    return State.CONTINUE;
  }
}
