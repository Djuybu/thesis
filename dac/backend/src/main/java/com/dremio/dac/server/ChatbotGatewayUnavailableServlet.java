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
package com.dremio.dac.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Served at {@code /aichat/*} when no gateway base URL is configured, so the SPA fallback ({@code
 * /*}) does not return HTML for API paths.
 */
public final class ChatbotGatewayUnavailableServlet extends HttpServlet {

  private static final String JSON =
      "{\"error\":\"AI SQL Agent gateway is not configured. Set services.coordinator.web.chatbot.gateway.base_url in dremio.conf, or environment variable DREMIO_CHATBOT_GATEWAY_BASE_URL, or system property dremio.chatbot.gateway.base_url (e.g. http://127.0.0.1:9292), then restart Dremio. The legacy keys services.coordinator.web.aichatbot.plugin.base_url / DREMIO_AICHATBOT_PLUGIN_BASE_URL are still accepted.\"}";

  @Override
  protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
    if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
      resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
      return;
    }
    resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
    resp.setContentType("application/json; charset=utf-8");
    final byte[] body = JSON.getBytes(StandardCharsets.UTF_8);
    resp.setContentLength(body.length);
    resp.getOutputStream().write(body);
  }
}
