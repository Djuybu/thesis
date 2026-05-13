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

import com.google.common.base.Strings;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Reverse-proxies {@code /aichat/*} to the Dremio SQL Agent gateway (dremio-sql-agent), preserving
 * client headers needed for Dremio auth and MCP (including SSE streams for GET).
 */
public final class ChatbotGatewayProxyServlet extends HttpServlet {

  private static final Set<String> HOP_BY_HOP =
      Set.of(
          "connection",
          "keep-alive",
          "proxy-authenticate",
          "proxy-authorization",
          "te",
          "trailers",
          "transfer-encoding",
          "upgrade",
          "host");

  private static final Set<String> ALLOWED_REQUEST_HEADERS =
      Set.of(
          "authorization",
          "content-type",
          "accept",
          "x-dremio-username",
          "mcp-session-id",
          "mcp-protocol-version",
          "last-event-id",
          "x-debug-chat-run-id");

  private static final int CONNECT_TIMEOUT_SEC = 15;
  private static final int BUFFER = 8192;

  /**
   * Default wait for buffered POST/PUT to the gateway (e.g. /aichat/ask + LangChain + MCP).
   * Override with servlet init-param {@code bufferTimeoutSeconds} or {@code
   * -Ddremio.chatbot.gateway.proxy.buffer_timeout_seconds=...} (seconds, clamped 60–864000). The
   * legacy property {@code dremio.aichatbot.plugin.proxy.buffer_timeout_seconds} is still honoured
   * for backward compatibility.
   */
  private static final int DEFAULT_BUFFER_TIMEOUT_SECONDS = 86_400;

  private static final String BUFFER_TIMEOUT_SYS_PROP =
      "dremio.chatbot.gateway.proxy.buffer_timeout_seconds";
  private static final String LEGACY_BUFFER_TIMEOUT_SYS_PROP =
      "dremio.aichatbot.plugin.proxy.buffer_timeout_seconds";

  private transient String baseUrl;
  private transient HttpClient httpClient;
  private transient Duration bufferedRequestTimeout =
      Duration.ofSeconds(DEFAULT_BUFFER_TIMEOUT_SECONDS);

  @Override
  public void init() throws ServletException {
    final String p = getServletConfig().getInitParameter("targetBaseUrl");
    if (Strings.isNullOrEmpty(p)) {
      throw new ServletException("init-param targetBaseUrl is required");
    }
    this.baseUrl = ChatbotGatewayBaseUrlResolver.stripTrailingSlash(p.trim());
    this.bufferedRequestTimeout = Duration.ofSeconds(resolveBufferedTimeoutSeconds());
    this.httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SEC))
            .build();
  }

  private int resolveBufferedTimeoutSeconds() throws ServletException {
    String raw = getServletConfig().getInitParameter("bufferTimeoutSeconds");
    if (raw == null || raw.isBlank()) {
      raw = System.getProperty(BUFFER_TIMEOUT_SYS_PROP);
    }
    if (raw == null || raw.isBlank()) {
      raw = System.getProperty(LEGACY_BUFFER_TIMEOUT_SYS_PROP);
    }
    if (raw == null || raw.isBlank()) {
      return DEFAULT_BUFFER_TIMEOUT_SECONDS;
    }
    try {
      final int s = Integer.parseInt(raw.trim());
      return Math.max(60, Math.min(s, 864_000));
    } catch (NumberFormatException e) {
      throw new ServletException("Invalid bufferTimeoutSeconds: " + raw, e);
    }
  }

  @Override
  protected void service(HttpServletRequest req, HttpServletResponse resp)
      throws IOException, ServletException {
    final String method = req.getMethod();
    if ("OPTIONS".equalsIgnoreCase(method)) {
      try {
        forwardOptions(req, resp);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ServletException("Interrupted while proxying OPTIONS", e);
      }
      return;
    }

    final String target = buildTargetUrl(req);
    try {
      if ("GET".equalsIgnoreCase(method)) {
        proxyStreamingGet(req, resp, target);
      } else {
        proxyBuffered(req, resp, target, method);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ServletException("Interrupted while proxying to chatbot gateway", e);
    }
  }

  private String buildTargetUrl(HttpServletRequest req) {
    final String uri = req.getRequestURI();
    final String query = req.getQueryString();
    return baseUrl + uri + (query != null && !query.isEmpty() ? "?" + query : "");
  }

  private void forwardOptions(HttpServletRequest req, HttpServletResponse resp)
      throws IOException, InterruptedException {
    final String target = buildTargetUrl(req);
    final HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(URI.create(target))
            .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
            .timeout(Duration.ofSeconds(30));
    copyRequestHeaders(req, builder);
    final HttpResponse<byte[]> upstream =
        httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    copyResponseHeaders(resp, upstream.headers(), upstream.statusCode());
    final byte[] body = upstream.body();
    if (body != null && body.length > 0) {
      resp.getOutputStream().write(body);
    }
  }

  private void proxyStreamingGet(HttpServletRequest req, HttpServletResponse resp, String target)
      throws IOException, InterruptedException {
    final HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(target)).GET();
    copyRequestHeaders(req, builder);
    final HttpResponse<InputStream> upstream =
        httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    writeStreamingResponse(resp, upstream);
  }

  private void proxyBuffered(
      HttpServletRequest req, HttpServletResponse resp, String target, String method)
      throws IOException, InterruptedException {
    final HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(target));
    builder.timeout(bufferedRequestTimeout);
    copyRequestHeaders(req, builder);
    if (hasRequestBody(method)) {
      final byte[] raw = req.getInputStream().readAllBytes();
      builder.method(method, HttpRequest.BodyPublishers.ofByteArray(raw));
    } else {
      builder.method(method, HttpRequest.BodyPublishers.noBody());
    }
    final HttpResponse<byte[]> upstream =
        httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    copyResponseHeaders(resp, upstream.headers(), upstream.statusCode());
    final byte[] body = upstream.body();
    if (body != null && body.length > 0) {
      resp.getOutputStream().write(body);
    }
  }

  private static boolean hasRequestBody(String method) {
    return "POST".equalsIgnoreCase(method)
        || "PUT".equalsIgnoreCase(method)
        || "PATCH".equalsIgnoreCase(method);
  }

  private static void copyRequestHeaders(HttpServletRequest req, HttpRequest.Builder builder) {
    final Enumeration<String> names = req.getHeaderNames();
    if (names == null) {
      return;
    }
    final Set<String> seen = new HashSet<>();
    while (names.hasMoreElements()) {
      final String name = names.nextElement();
      if (name == null) {
        continue;
      }
      final String lower = name.toLowerCase(Locale.ROOT);
      if (HOP_BY_HOP.contains(lower)) {
        continue;
      }
      if (!ALLOWED_REQUEST_HEADERS.contains(lower)) {
        continue;
      }
      if (!seen.add(lower)) {
        continue;
      }
      final Enumeration<String> values = req.getHeaders(name);
      if (values == null) {
        continue;
      }
      while (values.hasMoreElements()) {
        final String v = values.nextElement();
        if (v != null) {
          builder.header(name, v);
        }
      }
    }
  }

  private static void copyResponseHeaders(
      HttpServletResponse resp, java.net.http.HttpHeaders headers, int status) {
    resp.setStatus(status);
    headers
        .map()
        .forEach(
            (name, values) -> {
              if (values == null || values.isEmpty()) {
                return;
              }
              final String ln = name.toLowerCase(Locale.ROOT);
              if (HOP_BY_HOP.contains(ln) || "content-length".equals(ln)) {
                return;
              }
              for (String v : values) {
                resp.addHeader(name, v);
              }
            });
  }

  private static void writeStreamingResponse(
      HttpServletResponse resp, HttpResponse<InputStream> upstream) throws IOException {
    copyResponseHeaders(resp, upstream.headers(), upstream.statusCode());
    final InputStream in = upstream.body();
    if (in == null) {
      return;
    }
    try (InputStream input = in;
        OutputStream out = resp.getOutputStream()) {
      final byte[] buf = new byte[BUFFER];
      int n;
      while ((n = input.read(buf)) != -1) {
        out.write(buf, 0, n);
        out.flush();
      }
    }
  }
}
