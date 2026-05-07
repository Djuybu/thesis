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
 * Served at {@code /aichat/*} when no aichatbot-plugin base URL is configured, so the SPA fallback
 * ({@code /*}) does not return HTML for API paths.
 */
public final class AiChatbotPluginUnavailableServlet extends HttpServlet {

  private static final String JSON =
      "{\"error\":\"AI chatbot plugin is not configured. Set services.coordinator.web.aichatbot.plugin.base_url in dremio.conf, or environment variable DREMIO_AICHATBOT_PLUGIN_BASE_URL, or system property dremio.aichatbot.plugin.base_url (e.g. http://127.0.0.1:9191), then restart Dremio.\"}";

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
