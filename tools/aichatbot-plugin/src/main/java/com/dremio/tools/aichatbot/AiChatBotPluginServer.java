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
package com.dremio.tools.aichatbot;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Standalone AI chat plugin server for Dremio.
 *
 * <p>This service is intentionally separate from DAC backend. It reuses Dremio auth by forwarding
 * Authorization token to Dremio APIs, then calls a local or remote LLM (OpenAI-compatible API is
 * recommended for Ollama, LM Studio, vLLM, etc.).
 */
public final class AiChatBotPluginServer {
  private static final String CONTENT_TYPE = "Content-Type";
  private static final String APPLICATION_JSON = "application/json; charset=utf-8";
  private static final String APPLICATION_JSON_PLAIN = "application/json";
  private static final String DEBUG_LOG_PATH = "/home/djuybu/dremio-oss/.cursor/debug-4811a9.log";
  private static final String DEBUG_SESSION_ID = "4811a9";
  /** Pass-through JSON to a custom gateway (legacy). */
  private static final String MODE_CUSTOM = "custom";
  /** OpenAI Chat Completions compatible body (Ollama, LM Studio, vLLM, ...). */
  private static final String MODE_OPENAI = "openai";

  private AiChatBotPluginServer() {}

  private static String now() {
    return java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS"));
  }

  private static void log(String message) {
    System.out.println("[" + now() + "] " + message);
  }

  private static void logf(String format, Object... args) {
    System.out.print("[" + now() + "] " + String.format(format, args));
  }

  private static void logErr(String message) {
    System.err.println("[" + now() + "] " + message);
  }

  public static void main(String[] args) throws Exception {
    final String dremioBaseUrl =
        Optional.ofNullable(System.getenv("DREMIO_BASE_URL")).orElse("http://localhost:9047");
    final int port =
        Integer.parseInt(Optional.ofNullable(System.getenv("AICHAT_PORT")).orElse("9191"));
    final String aiBackendUrl = Optional.ofNullable(System.getenv("AI_BACKEND_URL")).orElse("");
    final String aiBackendAuthHeader =
        Optional.ofNullable(System.getenv("AI_BACKEND_AUTH_HEADER")).orElse("Authorization");
    final String aiBackendAuthPrefix =
        Optional.ofNullable(System.getenv("AI_BACKEND_AUTH_PREFIX")).orElse("Bearer ");
    final String aiBackendAuthValue = Optional.ofNullable(System.getenv("AI_BACKEND_AUTH")).orElse("");
    final String defaultAiModel =
        Optional.ofNullable(System.getenv("AI_MODEL_DEFAULT")).orElse("llama3.2");
    final String llmMode = resolveLlmMode(System.getenv("AI_LLM_MODE"), aiBackendUrl);
    final String systemPrompt =
        Optional.ofNullable(System.getenv("AI_SYSTEM_PROMPT"))
            .orElse(
                "You are a helpful assistant for Dremio data lakehouse users. "
                    + "Answer clearly. If user context is provided, personalize briefly (do not dump secrets).");
    final int requestTimeoutSeconds =
        Integer.parseInt(Optional.ofNullable(System.getenv("AI_REQUEST_TIMEOUT_SECONDS")).orElse("120"));
    final boolean unwrapAnswer =
        Boolean.parseBoolean(Optional.ofNullable(System.getenv("AI_UNWRAP_OPENAI_CONTENT")).orElse("true"));
    final String mcpHttpBase =
        stripTrailingSlash(Optional.ofNullable(System.getenv("DREMIO_MCP_HTTP_BASE")).orElse("").trim());
    final String mcpDefaultPath =
        normalizeMcpPath(Optional.ofNullable(System.getenv("DREMIO_MCP_HTTP_PATH")).orElse("/mcp"));
    final int mcpProxyTimeoutSeconds =
        Integer.parseInt(Optional.ofNullable(System.getenv("DREMIO_MCP_PROXY_TIMEOUT_SECONDS")).orElse("300"));
    final McpProxyConfig mcpProxyConfig = new McpProxyConfig(mcpHttpBase, mcpDefaultPath, mcpProxyTimeoutSeconds);

    final HttpClient httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    final HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    final int proxyThreads =
        Integer.parseInt(Optional.ofNullable(System.getenv("AICHAT_HTTP_SERVER_THREADS")).orElse("16"));
    final ExecutorService serverExecutor =
        Executors.newFixedThreadPool(
            Math.max(4, proxyThreads),
            new ThreadFactory() {
              @Override
              public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setName("aichat-http-" + t.getId());
                t.setDaemon(true);
                return t;
              }
            });

    final LlmConfig llmConfig =
        new LlmConfig(
            aiBackendUrl,
            aiBackendAuthHeader,
            aiBackendAuthPrefix,
            aiBackendAuthValue,
            defaultAiModel,
            llmMode,
            systemPrompt,
            requestTimeoutSeconds,
            unwrapAnswer);

    server.createContext("/health", exchange -> handleHealth(exchange));
    server.createContext(
        "/aichat/config", exchange -> handleConfig(exchange, llmConfig, dremioBaseUrl, mcpProxyConfig));
    server.createContext(
        "/aichat/context", exchange -> handleContext(exchange, httpClient, dremioBaseUrl));
    server.createContext(
        "/aichat/ask",
        exchange -> handleAsk(exchange, httpClient, dremioBaseUrl, llmConfig));
    server.createContext(
        "/aichat/mcp-proxy",
        exchange -> handleMcpProxy(exchange, httpClient, dremioBaseUrl, mcpProxyConfig));
    server.createContext("/aichat/", exchange -> handleStaticOrOptions(exchange, "aichat/"));
    server.setExecutor(serverExecutor);
    server.start();

    logf("AI Chat plugin server is running at http://localhost:%d%n", port);
    logf("Dremio upstream configured as %s%n", dremioBaseUrl);
    logf("LLM mode: %s (set AI_LLM_MODE=openai|custom)%n", llmMode);
    if (aiBackendUrl.isBlank()) {
      log("AI_BACKEND_URL is not set, /aichat/ask returns mock after Dremio auth.");
    } else {
      logf("AI backend URL: %s%n", aiBackendUrl);
    }
    if (mcpHttpBase.isBlank()) {
      log("DREMIO_MCP_HTTP_BASE is not set; /aichat/mcp-proxy is disabled.");
    } else {
      logf("Dremio MCP HTTP proxy target: %s (default path %s)%n", mcpHttpBase, mcpDefaultPath);
    }
    logf("Open UI: http://localhost:%d/aichat/chat.html%n", port);
  }

  private static String stripTrailingSlash(String s) {
    if (s == null || s.isBlank()) {
      return "";
    }
    String out = s.trim();
    while (out.endsWith("/")) {
      out = out.substring(0, out.length() - 1);
    }
    return out;
  }

  private static String normalizeMcpPath(String path) {
    if (path == null || path.isBlank()) {
      return "/mcp";
    }
    String p = path.trim();
    return p.startsWith("/") ? p : "/" + p;
  }

  private static String resolveLlmMode(String envMode, String aiBackendUrl) {
    if (envMode != null && !envMode.isBlank()) {
      final String m = envMode.trim().toLowerCase(Locale.ROOT);
      if (MODE_CUSTOM.equals(m) || MODE_OPENAI.equals(m)) {
        return m;
      }
    }
    if (aiBackendUrl != null
        && (aiBackendUrl.contains("/v1/chat/completions")
            || aiBackendUrl.contains("/v1/completions"))) {
      return MODE_OPENAI;
    }
    if (aiBackendUrl != null && !aiBackendUrl.isBlank()) {
      return MODE_OPENAI;
    }
    return MODE_OPENAI;
  }

  private static void handleHealth(HttpExchange exchange) throws IOException {
    addCors(exchange);
    if (handleOptions(exchange)) {
      return;
    }
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
      return;
    }
    writeJson(exchange, 200, "{\"status\":\"ok\",\"service\":\"dremio-aichatbot-plugin\"}");
  }

  private static void handleConfig(
      HttpExchange exchange, LlmConfig cfg, String dremioBaseUrl, McpProxyConfig mcpProxy)
      throws IOException {
    addCors(exchange);
    if (handleOptions(exchange)) {
      return;
    }
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
      return;
    }
    final String body =
        "{"
            + "\"dremioBaseUrl\":\""
            + escapeJson(dremioBaseUrl)
            + "\","
            + "\"llmMode\":\""
            + escapeJson(cfg.llmMode)
            + "\","
            + "\"aiBackendConfigured\":"
            + (!cfg.aiBackendUrl.isBlank())
            + ","
            + "\"dremioMcpHttpConfigured\":"
            + (!mcpProxy.baseUrl.isBlank())
            + ","
            + "\"dremioMcpDefaultPath\":\""
            + escapeJson(mcpProxy.defaultPath)
            + "\","
            + "\"defaultModel\":\""
            + escapeJson(cfg.defaultAiModel)
            + "\","
            + "\"requestTimeoutSeconds\":"
            + cfg.requestTimeoutSeconds
            + ","
            + "\"unwrapOpenAiContent\":"
            + cfg.unwrapAnswer
            + ","
            + "\"mcpProxyTimeoutSeconds\":"
            + mcpProxy.timeoutSeconds
            + "}";
    writeJson(exchange, 200, body);
  }

  /**
   * Validates the caller against Dremio OSS, then forwards the request to a dremio-mcp instance
   * (see https://github.com/dremio/dremio-mcp) running with {@code --enable-streaming-http}. The
   * same {@code Authorization} header is forwarded so MCP can verify the bearer token (OAuth /
   * PAT flow as configured in dremio-mcp).
   */
  private static void handleMcpProxy(
      HttpExchange exchange, HttpClient client, String dremioBaseUrl, McpProxyConfig mcp)
      throws IOException {
    addCors(exchange);
    if (handleOptions(exchange)) {
      return;
    }
    final String method = exchange.getRequestMethod();
    if (!"GET".equalsIgnoreCase(method) && !"POST".equalsIgnoreCase(method)) {
      writeJson(exchange, 405, jsonError("Only GET and POST are supported"));
      return;
    }
    if (mcp.baseUrl.isBlank()) {
      log("[MCP Proxy] DREMIO_MCP_HTTP_BASE is not configured");
      writeJson(exchange, 503, jsonError("DREMIO_MCP_HTTP_BASE is not configured"));
      return;
    }
    final String token = getAuthorization(exchange);
    if (token == null) {
      writeJson(exchange, 401, jsonError("Missing Authorization header"));
      return;
    }
    final String rawQuery = exchange.getRequestURI().getRawQuery();
    final String pathParam = parseQueryParameter(rawQuery, "path");
    final String path =
        sanitizeMcpPath(
            pathParam != null && !pathParam.isBlank() ? pathParam : mcp.defaultPath);
    if (path == null) {
      writeJson(exchange, 400, jsonError("Invalid or unsafe path (use ?path=/mcp or similar)"));
      return;
    }
    final String upstreamUrl = mcp.baseUrl + path;
    if (!upstreamUrl.startsWith("http://") && !upstreamUrl.startsWith("https://")) {
      log("[MCP Proxy] Invalid protocol for " + upstreamUrl);
      writeJson(exchange, 400, jsonError("DREMIO_MCP_HTTP_BASE must be an http(s) URL"));
      return;
    }
    try {
      URI.create(upstreamUrl);
    } catch (IllegalArgumentException e) {
      writeJson(exchange, 400, jsonError("Bad MCP URL"));
      return;
    }
    boolean sseResponseStarted = false;
    long sseBytesForwarded = 0L;
    String proxyStage = "init";
    final String accept = exchange.getRequestHeaders().getFirst("Accept");
    final String chatRunId = firstNonBlank(exchange.getRequestHeaders().getFirst("X-Debug-Chat-Run-Id"), "");
    try {
      proxyStage = "request_received";
      log("[MCP Proxy] Forwarding " + method + " request to: " + upstreamUrl);
      // #region agent log
      debugLog(
          "run1",
          "H1",
          "AiChatBotPluginServer.java:296",
          "mcp_proxy_request_received",
          "{\"method\":\""
              + escapeJson(method)
              + "\",\"path\":\""
              + escapeJson(path)
              + "\",\"accept\":\""
              + escapeJson(firstNonBlank(accept, ""))
              + "\",\"chatRunId\":\""
              + escapeJson(chatRunId)
              + "\"}");
      // #endregion
      final HttpResponse<String> login = fetchLoginInfo(client, dremioBaseUrl, token);
      if (login.statusCode() >= 400) {
        log("[MCP Proxy] Invalid Dremio token verified via " + dremioBaseUrl);
        writeJson(exchange, 401, jsonError("Invalid Dremio token"));
        return;
      }
      final String[] mcpHeaders = {"mcp-session-id", "mcp-protocol-version", "last-event-id"};

      if ("POST".equalsIgnoreCase(method)) {
        proxyStage = "post_build_upstream_request";
        // POST: use configured timeout
        final HttpRequest.Builder builder =
            HttpRequest.newBuilder()
                .uri(URI.create(upstreamUrl))
                .timeout(Duration.ofSeconds(mcp.timeoutSeconds))
                .header("Authorization", token);
        if (accept != null && !accept.isBlank()) {
          builder.header("Accept", accept);
        }
        for (String h : mcpHeaders) {
          final String val = exchange.getRequestHeaders().getFirst(h);
          if (val != null && !val.isBlank()) {
            builder.header(h, val);
          }
        }
        final String ct = exchange.getRequestHeaders().getFirst(CONTENT_TYPE);
        if (ct != null && !ct.isBlank()) {
          builder.header(CONTENT_TYPE, ct);
        } else {
          builder.header(CONTENT_TYPE, APPLICATION_JSON_PLAIN);
        }
        final byte[] raw = readRequestBodyBytes(exchange.getRequestBody());
        final String rawBody = new String(raw, StandardCharsets.UTF_8);
        final String rpcMethod = extractJsonString(rawBody, "method");
        final String rpcId = extractJsonRawValue(rawBody, "id");
        // #region agent log
        debugLog(
            "run6",
            "H7",
            "AiChatBotPluginServer.java:350",
            "mcp_proxy_post_request_shape",
            "{\"rpcMethod\":\""
                + escapeJson(firstNonBlank(rpcMethod, ""))
                + "\",\"rpcId\":\""
                + escapeJson(firstNonBlank(rpcId, ""))
                + "\",\"bodyLen\":"
                + raw.length
                + "}");
        // #endregion
        builder.POST(HttpRequest.BodyPublishers.ofByteArray(raw));
        proxyStage = "post_send_upstream";
        final HttpResponse<String> upstream =
            client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        log("[MCP Proxy] POST upstream responded with status: " + upstream.statusCode());
        // #region agent log
        debugLog(
            "run1",
            "H4",
            "AiChatBotPluginServer.java:338",
            "mcp_proxy_post_upstream_status",
            "{\"status\":" + upstream.statusCode() + ",\"bodyLen\":" + upstream.body().length() + "}");
        // #endregion
        final String respCt =
            upstream.headers().firstValue(CONTENT_TYPE).orElse(APPLICATION_JSON_PLAIN);
        // #region agent log
        debugLog(
            "run2",
            "H3",
            "AiChatBotPluginServer.java:353",
            "mcp_proxy_post_before_write_downstream",
            "{\"status\":" + upstream.statusCode() + ",\"respCt\":\"" + escapeJson(respCt) + "\"}");
        // #endregion
        proxyStage = "post_write_downstream";
        writeProxyRaw(
            exchange,
            upstream.statusCode(),
            upstream.body().getBytes(StandardCharsets.UTF_8),
            respCt,
            upstream.headers());
        // #region agent log
        debugLog(
            "run2",
            "H3",
            "AiChatBotPluginServer.java:367",
            "mcp_proxy_post_downstream_write_done",
            "{\"status\":" + upstream.statusCode() + "}");
        // #endregion
      } else {
        // GET — likely a long-lived SSE stream: NO per-request timeout so the connection
        // is not cut off after mcpProxyTimeoutSeconds. Streaming is piped directly.
        final HttpRequest.Builder builder =
            HttpRequest.newBuilder()
                .uri(URI.create(upstreamUrl))
                .header("Authorization", token);
        if (accept != null && !accept.isBlank()) {
          builder.header("Accept", accept);
        }
        for (String h : mcpHeaders) {
          final String val = exchange.getRequestHeaders().getFirst(h);
          if (val != null && !val.isBlank()) {
            builder.header(h, val);
          }
        }
        builder.GET();
        log("[MCP Proxy] Opening SSE stream (no timeout) to: " + upstreamUrl);
        final HttpResponse<java.io.InputStream> upstream =
            client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        log("[MCP Proxy] SSE stream opened with status: " + upstream.statusCode());
        // #region agent log
        debugLog(
            "run1",
            "H1",
            "AiChatBotPluginServer.java:371",
            "mcp_proxy_sse_opened",
            "{\"status\":" + upstream.statusCode() + "}");
        // #endregion
        final String respCt =
            upstream.headers().firstValue(CONTENT_TYPE).orElse("text/event-stream");
        addCors(exchange);
        exchange.getResponseHeaders().set(CONTENT_TYPE, respCt);
        for (String h : MCP_PASSTHROUGH_HEADERS) {
          upstream.headers().firstValue(h).ifPresent(v -> exchange.getResponseHeaders().set(h, v));
        }
        // 0 = chunked / unknown length
        exchange.sendResponseHeaders(upstream.statusCode(), 0);
        sseResponseStarted = true;
        try (java.io.InputStream in = upstream.body();
             OutputStream out = exchange.getResponseBody()) {
          final byte[] buf = new byte[4096];
          int n;
          while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            out.flush();
            sseBytesForwarded += n;
          }
        }
        // #region agent log
        debugLog(
            "run1",
            "H1",
            "AiChatBotPluginServer.java:397",
            "mcp_proxy_sse_loop_finished",
            "{\"bytesForwarded\":" + sseBytesForwarded + "}");
        // #endregion
        log("[MCP Proxy] SSE stream closed for: " + upstreamUrl);
        exchange.close();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      writeJson(exchange, 500, jsonError("Interrupted"));
    } catch (Exception e) {
      // #region agent log
      debugLog(
          "run1",
          "H2",
          "AiChatBotPluginServer.java:406",
          "mcp_proxy_exception",
          "{\"errorClass\":\""
              + escapeJson(e.getClass().getName())
              + "\",\"error\":\""
              + escapeJson(sanitize(e.getMessage()))
              + "\",\"stage\":\""
              + escapeJson(proxyStage)
              + "\",\"upstreamUrl\":\""
              + escapeJson(upstreamUrl)
              + "\"}");
      // #endregion
      logErr("[MCP Proxy] Error forwarding request: " + e.getMessage());
      if (isUpstreamUnavailableException(e)) {
        // #region agent log
        debugLog(
            "run5",
            "H6",
            "AiChatBotPluginServer.java:414",
            "mcp_proxy_upstream_unavailable",
            "{\"upstreamUrl\":\""
                + escapeJson(upstreamUrl)
                + "\",\"stage\":\""
                + escapeJson(proxyStage)
                + "\",\"errorClass\":\""
                + escapeJson(e.getClass().getName())
                + "\"}");
        // #endregion
        writeJson(
            exchange,
            503,
            jsonError("MCP upstream unavailable at " + upstreamUrl + ": " + sanitize(e.getMessage())));
        return;
      }
      if (isClientDisconnectException(e)) {
        log("[MCP Proxy] Client disconnected; suppressing downstream error response");
        // #region agent log
        debugLog(
            "post-fix",
            "H5",
            "AiChatBotPluginServer.java:424",
            "mcp_proxy_client_disconnect_suppressed",
            "{\"method\":\""
                + escapeJson(method)
                + "\",\"stage\":\""
                + escapeJson(proxyStage)
                + "\",\"bytesForwarded\":"
                + sseBytesForwarded
                + "}");
        // #endregion
        exchange.close();
        return;
      }
      if ("GET".equalsIgnoreCase(method) && sseResponseStarted && isClientDisconnectException(e)) {
        log("[MCP Proxy] SSE client disconnected; suppressing error response");
        // #region agent log
        debugLog(
            "post-fix",
            "H2",
            "AiChatBotPluginServer.java:418",
            "mcp_proxy_sse_client_disconnect_suppressed",
            "{\"bytesForwarded\":" + sseBytesForwarded + "}");
        // #endregion
        exchange.close();
        return;
      }
      writeJson(exchange, 502, jsonError("MCP upstream error: " + sanitize(e.getMessage())));
    }
  }

  private static boolean isClientDisconnectException(Throwable t) {
    Throwable current = t;
    while (current != null) {
      final String msg = sanitize(current.getMessage()).toLowerCase(Locale.ROOT);
      if (msg.contains("broken pipe")
          || msg.contains("connection reset")
          || msg.contains("connection abort")
          || msg.contains("stream is closed")) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static boolean isUpstreamUnavailableException(Throwable t) {
    Throwable current = t;
    while (current != null) {
      if (current instanceof ConnectException || current instanceof HttpConnectTimeoutException) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static void debugLog(
      String runId, String hypothesisId, String location, String message, String dataJson) {
    // instrumentation disabled after debugging
  }

  private static String parseQueryParameter(String query, String key) {
    if (query == null || query.isBlank()) {
      return null;
    }
    for (String pair : query.split("&")) {
      final int eq = pair.indexOf('=');
      if (eq <= 0) {
        continue;
      }
      final String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
      if (key.equals(k)) {
        return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      }
    }
    return null;
  }

  /** Allow only safe URL paths for the MCP proxy (no traversal, limited charset). */
  private static String sanitizeMcpPath(String path) {
    if (path == null || path.isBlank()) {
      return null;
    }
    String p = path.trim();
    if (!p.startsWith("/")) {
      p = "/" + p;
    }
    if (p.contains("..") || p.contains("//")) {
      return null;
    }
    if (p.length() > 512) {
      return null;
    }
    for (int i = 0; i < p.length(); i++) {
      final char c = p.charAt(i);
      final boolean ok =
          (c >= 'a' && c <= 'z')
              || (c >= 'A' && c <= 'Z')
              || (c >= '0' && c <= '9')
              || c == '/'
              || c == '-'
              || c == '_'
              || c == '.'
              || c == '~'
              || c == '%';
      if (!ok) {
        return null;
      }
    }
    return p;
  }

  private static void handleContext(HttpExchange exchange, HttpClient client, String dremioBaseUrl)
      throws IOException {
    addCors(exchange);
    if (handleOptions(exchange)) {
      return;
    }
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
      return;
    }

    final String token = getAuthorization(exchange);
    if (token == null) {
      writeJson(exchange, 401, "{\"error\":\"Missing Authorization header\"}");
      return;
    }

    try {
      final String requestedUser = exchange.getRequestHeaders().getFirst("X-Dremio-Username");
      if (requestedUser != null && !requestedUser.isBlank()) {
        final HttpResponse<String> userResponse =
            fetchUserByUsername(client, dremioBaseUrl, token, requestedUser);
        writeJson(exchange, userResponse.statusCode(), userResponse.body());
        return;
      }

      final HttpRequest loginCheckRequest =
          HttpRequest.newBuilder()
              .uri(URI.create(dremioBaseUrl + "/apiv2/login"))
              .header("Authorization", token)
              .header("Accept", "application/json")
              .GET()
              .build();
      final HttpResponse<String> loginCheckResponse =
          client.send(loginCheckRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (loginCheckResponse.statusCode() >= 400) {
        writeJson(
            exchange, loginCheckResponse.statusCode(), jsonError("Token is not valid in Dremio"));
        return;
      }
      writeJson(
          exchange,
          200,
          "{\"authValid\":true,\"source\":\"dremio:/apiv2/login\",\"message\":\"Authenticated token accepted by Dremio. Send X-Dremio-Username to retrieve user profile.\"}");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      writeJson(exchange, 500, jsonError("Interrupted while calling Dremio"));
    } catch (Exception e) {
      writeJson(exchange, 500, jsonError("Failed to call Dremio: " + sanitize(e.getMessage())));
    }
  }

  private static void handleAsk(
      HttpExchange exchange, HttpClient client, String dremioBaseUrl, LlmConfig cfg)
      throws IOException {
    addCors(exchange);
    if (handleOptions(exchange)) {
      return;
    }
    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
      writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
      return;
    }

    final String token = getAuthorization(exchange);
    if (token == null) {
      writeJson(exchange, 401, "{\"error\":\"Missing Authorization header\"}");
      return;
    }

    final String body = readRequestBody(exchange.getRequestBody());
    final String prompt = extractPrompt(body);
    final String requestedModel = extractStringValue(body, "model");
    final String model =
        requestedModel == null || requestedModel.isBlank() ? cfg.defaultAiModel : requestedModel;
    final String overrideSystem = extractStringValue(body, "system");
    final Double temperature = extractJsonNumber(body, "temperature");
    final Integer maxTokens = extractJsonInt(body, "max_tokens");

    if (prompt == null || prompt.isBlank()) {
      writeJson(exchange, 400, "{\"error\":\"Field 'prompt' is required\"}");
      return;
    }

    try {
      final HttpResponse<String> loginCheckResponse = fetchLoginInfo(client, dremioBaseUrl, token);
      if (loginCheckResponse.statusCode() >= 400) {
        writeJson(exchange, 401, jsonError("Invalid Dremio token"));
        return;
      }

      final String username =
          firstNonBlank(
              extractStringValue(loginCheckResponse.body(), "userName"),
              extractStringValue(loginCheckResponse.body(), "username"),
              exchange.getRequestHeaders().getFirst("X-Dremio-Username"));

      String userContext = "";
      if (username != null && !username.isBlank()) {
        final HttpResponse<String> userResponse =
            fetchUserByUsername(client, dremioBaseUrl, token, username);
        if (userResponse.statusCode() < 400) {
          userContext = userResponse.body();
        }
      }

      if (cfg.aiBackendUrl == null || cfg.aiBackendUrl.isBlank()) {
        final String answer =
            "{\"answer\":\"AI backend is not configured. Set AI_BACKEND_URL for local LLM (e.g. Ollama http://127.0.0.1:11434/v1/chat/completions).\","
                + "\"promptEcho\":\""
                + escapeJson(prompt)
                + "\",\"model\":\""
                + escapeJson(model)
                + "\",\"userName\":\""
                + escapeJson(firstNonBlank(username, ""))
                + "\",\"llmMode\":\""
                + escapeJson(cfg.llmMode)
                + "\"}";
        writeJson(exchange, 200, answer);
        return;
      }

      if (MODE_CUSTOM.equalsIgnoreCase(cfg.llmMode)) {
        final String aiRequestBody =
            "{"
                + "\"prompt\":\""
                + escapeJson(prompt)
                + "\","
                + "\"model\":\""
                + escapeJson(model)
                + "\","
                + "\"dremioUserName\":\""
                + escapeJson(firstNonBlank(username, ""))
                + "\","
                + "\"dremioUserContext\":"
                + (userContext.isBlank() ? "null" : "\"" + escapeJson(userContext) + "\"")
                + "}";
        sendLlmRequest(exchange, client, cfg, aiRequestBody);
        return;
      }

      final String systemBlock =
          (overrideSystem != null && !overrideSystem.isBlank() ? overrideSystem : cfg.systemPrompt)
              + (userContext.isBlank()
                  ? ""
                  : "\n\n--- Dremio user profile (JSON, do not repeat verbatim if sensitive) ---\n"
                      + userContext);
      final String openAiBody =
          buildOpenAiChatCompletionsBody(model, systemBlock, prompt, temperature, maxTokens);
      sendLlmRequest(exchange, client, cfg, openAiBody);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      writeJson(exchange, 500, jsonError("Interrupted while processing request"));
    } catch (Exception e) {
      writeJson(
          exchange, 500, jsonError("Failed to process chat request: " + sanitize(e.getMessage())));
    }
  }

  private static void sendLlmRequest(
      HttpExchange exchange, HttpClient client, LlmConfig cfg, String requestBody)
      throws IOException, InterruptedException {
    final HttpRequest.Builder aiRequestBuilder =
        HttpRequest.newBuilder()
            .uri(URI.create(cfg.aiBackendUrl))
            .header("Accept", APPLICATION_JSON_PLAIN)
            .header(CONTENT_TYPE, APPLICATION_JSON_PLAIN)
            .timeout(Duration.ofSeconds(cfg.requestTimeoutSeconds))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
    if (cfg.aiBackendAuthValue != null && !cfg.aiBackendAuthValue.isBlank()) {
      aiRequestBuilder.header(cfg.aiBackendAuthHeader, cfg.aiBackendAuthPrefix + cfg.aiBackendAuthValue);
    }

    final HttpResponse<String> aiResponse =
        client.send(aiRequestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    final boolean openAiMode = MODE_OPENAI.equalsIgnoreCase(cfg.llmMode);
    if (!openAiMode
        || !cfg.unwrapAnswer
        || aiResponse.statusCode() >= 400) {
      addCors(exchange);
      writeJson(exchange, aiResponse.statusCode(), aiResponse.body());
      return;
    }

    final String content = tryExtractOpenAiMessageContent(aiResponse.body());
    if (content == null) {
      addCors(exchange);
      writeJson(exchange, aiResponse.statusCode(), aiResponse.body());
      return;
    }

    final String wrapped =
        "{"
            + "\"answer\":\""
            + escapeJson(content)
            + "\","
            + "\"llmRaw\":\""
            + escapeJson(aiResponse.body())
            + "\"}";
    addCors(exchange);
    writeJson(exchange, 200, wrapped);
  }

  /**
   * Best-effort extract {@code choices[0].message.content} from OpenAI-style JSON without a parser
   * library. Returns null if not found.
   */
  private static String tryExtractOpenAiMessageContent(String json) {
    if (json == null) {
      return null;
    }
    final String key = "\"content\"";
    int idx = json.indexOf("\"message\"");
    if (idx < 0) {
      idx = 0;
    }
    int c = json.indexOf(key, idx);
    if (c < 0) {
      return null;
    }
    int colon = json.indexOf(':', c + key.length());
    if (colon < 0) {
      return null;
    }
    int i = colon + 1;
    while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
      i++;
    }
    if (i >= json.length()) {
      return null;
    }
    if (json.charAt(i) == '"') {
      return readJsonString(json, i);
    }
    return null;
  }

  private static String readJsonString(String json, int startQuote) {
    final StringBuilder sb = new StringBuilder();
    int i = startQuote + 1;
    while (i < json.length()) {
      final char ch = json.charAt(i);
      if (ch == '\\' && i + 1 < json.length()) {
        final char n = json.charAt(i + 1);
        switch (n) {
          case '"':
            sb.append('"');
            break;
          case '\\':
            sb.append('\\');
            break;
          case '/':
            sb.append('/');
            break;
          case 'b':
            sb.append('\b');
            break;
          case 'f':
            sb.append('\f');
            break;
          case 'n':
            sb.append('\n');
            break;
          case 'r':
            sb.append('\r');
            break;
          case 't':
            sb.append('\t');
            break;
          case 'u':
            if (i + 5 < json.length()) {
              final String hex = json.substring(i + 2, i + 6);
              try {
                sb.append((char) Integer.parseInt(hex, 16));
              } catch (NumberFormatException e) {
                sb.append('?');
              }
              i += 4;
            }
            break;
          default:
            sb.append(n);
        }
        i += 2;
        continue;
      }
      if (ch == '"') {
        break;
      }
      sb.append(ch);
      i++;
    }
    return sb.toString();
  }

  private static String buildOpenAiChatCompletionsBody(
      String model,
      String systemContent,
      String userContent,
      Double temperature,
      Integer maxTokens) {
    final StringBuilder sb = new StringBuilder();
    sb.append('{');
    sb.append("\"model\":\"").append(escapeJson(model)).append("\",");
    sb.append("\"messages\":[");
    sb.append("{\"role\":\"system\",\"content\":\"").append(escapeJson(systemContent)).append("\"},");
    sb.append("{\"role\":\"user\",\"content\":\"").append(escapeJson(userContent)).append("\"}");
    sb.append("],");
    sb.append("\"stream\":false");
    if (temperature != null) {
      sb.append(",\"temperature\":").append(temperature);
    }
    if (maxTokens != null) {
      sb.append(",\"max_tokens\":").append(maxTokens);
    }
    sb.append('}');
    return sb.toString();
  }

  private static Double extractJsonNumber(String json, String key) {
    if (json == null) {
      return null;
    }
    final String marker = "\"" + key + "\"";
    int p = json.indexOf(marker);
    if (p < 0) {
      return null;
    }
    int colon = json.indexOf(':', p + marker.length());
    if (colon < 0) {
      return null;
    }
    int i = colon + 1;
    while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
      i++;
    }
    int start = i;
    while (i < json.length() && (Character.isDigit(json.charAt(i)) || ".-+eE".indexOf(json.charAt(i)) >= 0)) {
      i++;
    }
    if (start == i) {
      return null;
    }
    try {
      return Double.parseDouble(json.substring(start, i));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static Integer extractJsonInt(String json, String key) {
    final Double d = extractJsonNumber(json, key);
    if (d == null) {
      return null;
    }
    return d.intValue();
  }

  private static String extractJsonString(String json, String key) {
    if (json == null) {
      return null;
    }
    final String marker = "\"" + key + "\"";
    int p = json.indexOf(marker);
    if (p < 0) {
      return null;
    }
    int colon = json.indexOf(':', p + marker.length());
    if (colon < 0) {
      return null;
    }
    int i = colon + 1;
    while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
      i++;
    }
    if (i >= json.length() || json.charAt(i) != '"') {
      return null;
    }
    i++;
    final StringBuilder sb = new StringBuilder();
    while (i < json.length()) {
      char ch = json.charAt(i++);
      if (ch == '\\') {
        if (i < json.length()) {
          sb.append(json.charAt(i++));
        }
        continue;
      }
      if (ch == '"') {
        return sb.toString();
      }
      sb.append(ch);
    }
    return null;
  }

  private static String extractJsonRawValue(String json, String key) {
    if (json == null) {
      return null;
    }
    final String marker = "\"" + key + "\"";
    int p = json.indexOf(marker);
    if (p < 0) {
      return null;
    }
    int colon = json.indexOf(':', p + marker.length());
    if (colon < 0) {
      return null;
    }
    int i = colon + 1;
    while (i < json.length() && Character.isWhitespace(json.charAt(i))) {
      i++;
    }
    int start = i;
    while (i < json.length() && ",}\r\n\t ".indexOf(json.charAt(i)) < 0) {
      i++;
    }
    if (i <= start) {
      return null;
    }
    return json.substring(start, i);
  }

  private static void handleStaticOrOptions(HttpExchange exchange, String prefix) throws IOException {
    addCors(exchange);
    if (handleOptions(exchange)) {
      return;
    }
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      writeJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
      return;
    }
    final String path = exchange.getRequestURI().getPath();
    String resourcePath = path.startsWith("/") ? path.substring(1) : path;
    if (!resourcePath.startsWith(prefix)) {
      writeJson(exchange, 404, "{\"error\":\"Not found\"}");
      return;
    }
    if ("aichat/chat.html".equals(resourcePath) || "/aichat/chat.html".equals(path)) {
      serveResource(exchange, "public/chat.html", "text/html; charset=utf-8");
      return;
    }
    writeJson(exchange, 404, "{\"error\":\"Not found\"}");
  }

  private static void serveResource(HttpExchange exchange, String classpathResource, String contentType)
      throws IOException {
    try (InputStream in =
        AiChatBotPluginServer.class.getClassLoader().getResourceAsStream(classpathResource)) {
      if (in == null) {
        writeJson(exchange, 404, "{\"error\":\"Resource missing: " + escapeJson(classpathResource) + "\"}");
        return;
      }
      final byte[] bytes = in.readAllBytes();
      exchange.getResponseHeaders().set(CONTENT_TYPE, contentType);
      exchange.sendResponseHeaders(200, bytes.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(bytes);
      }
    }
  }

  private static boolean handleOptions(HttpExchange exchange) throws IOException {
    if (!"OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
      return false;
    }
    exchange.sendResponseHeaders(204, -1);
    exchange.close();
    return true;
  }

  private static void addCors(HttpExchange exchange) {
    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
    exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Dremio-Username");
    exchange.getResponseHeaders().set("Access-Control-Max-Age", "86400");
  }

  private static String getAuthorization(HttpExchange exchange) {
    return exchange.getRequestHeaders().getFirst("Authorization");
  }

  private static String readRequestBody(InputStream inputStream) throws IOException {
    return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
  }

  private static byte[] readRequestBodyBytes(InputStream inputStream) throws IOException {
    return inputStream.readAllBytes();
  }

  private static void writeRaw(HttpExchange exchange, int statusCode, byte[] body, String contentType)
      throws IOException {
    addCors(exchange);
    final String ct =
        contentType == null || contentType.isBlank() ? APPLICATION_JSON_PLAIN : contentType;
    exchange.getResponseHeaders().set(CONTENT_TYPE, ct);
    exchange.sendResponseHeaders(statusCode, body.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(body);
    }
    exchange.close();
  }

  /** Variant used by MCP proxy: also mirrors upstream headers that the MCP client depends on. */
  private static final java.util.Set<String> MCP_PASSTHROUGH_HEADERS =
      java.util.Set.of("mcp-session-id", "mcp-protocol-version", "cache-control", "x-accel-buffering");

  private static void writeProxyRaw(
      HttpExchange exchange,
      int statusCode,
      byte[] body,
      String contentType,
      java.net.http.HttpHeaders upstreamHeaders)
      throws IOException {
    addCors(exchange);
    final String ct =
        contentType == null || contentType.isBlank() ? APPLICATION_JSON_PLAIN : contentType;
    exchange.getResponseHeaders().set(CONTENT_TYPE, ct);
    // Forward MCP-specific response headers so the client can maintain session state
    for (String h : MCP_PASSTHROUGH_HEADERS) {
      upstreamHeaders.firstValue(h).ifPresent(v -> exchange.getResponseHeaders().set(h, v));
    }
    exchange.sendResponseHeaders(statusCode, body.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(body);
    }
    exchange.close();
  }

  private static String extractPrompt(String json) {
    return extractStringValue(json, "prompt");
  }

  private static String extractStringValue(String json, String key) {
    if (json == null) {
      return null;
    }
    final String marker = "\"" + key + "\"";
    final int markerIndex = json.indexOf(marker);
    if (markerIndex < 0) {
      return null;
    }
    final int colonIndex = json.indexOf(':', markerIndex + marker.length());
    if (colonIndex < 0) {
      return null;
    }
    final int firstQuoteIndex = json.indexOf('"', colonIndex + 1);
    if (firstQuoteIndex < 0) {
      return null;
    }
    return readJsonString(json, firstQuoteIndex);
  }

  private static HttpResponse<String> fetchLoginInfo(
      HttpClient client, String dremioBaseUrl, String token) throws IOException, InterruptedException {
    final HttpRequest loginCheckRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(dremioBaseUrl + "/apiv2/login"))
            .header("Authorization", token)
            .header("Accept", APPLICATION_JSON_PLAIN)
            .GET()
            .build();
    return client.send(loginCheckRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static HttpResponse<String> fetchUserByUsername(
      HttpClient client, String dremioBaseUrl, String token, String userName)
      throws IOException, InterruptedException {
    final String encodedUser = URLEncoder.encode(userName, StandardCharsets.UTF_8);
    final HttpRequest userRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(dremioBaseUrl + "/apiv2/user/" + encodedUser))
            .header("Authorization", token)
            .header("Accept", APPLICATION_JSON_PLAIN)
            .GET()
            .build();
    return client.send(userRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static void writeJson(HttpExchange exchange, int statusCode, String body) throws IOException {
    addCors(exchange);
    final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set(CONTENT_TYPE, APPLICATION_JSON);
    exchange.sendResponseHeaders(statusCode, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static String jsonError(String message) {
    return "{\"error\":\"" + escapeJson(sanitize(message)) + "\"}";
  }

  private static String sanitize(String value) {
    return value == null ? "" : value;
  }

  static String escapeJson(String value) {
    if (value == null) {
      return "";
    }
    final StringBuilder sb = new StringBuilder();
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      switch (c) {
        case '\\':
          sb.append("\\\\");
          break;
        case '"':
          sb.append("\\\"");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\t':
          sb.append("\\t");
          break;
        case '\b':
          sb.append("\\b");
          break;
        case '\f':
          sb.append("\\f");
          break;
        default:
          if (c < 0x20) {
            sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
      }
    }
    return sb.toString();
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private static final class McpProxyConfig {
    final String baseUrl;
    final String defaultPath;
    final int timeoutSeconds;

    McpProxyConfig(String baseUrl, String defaultPath, int timeoutSeconds) {
      this.baseUrl = baseUrl == null ? "" : baseUrl;
      this.defaultPath = defaultPath == null || defaultPath.isBlank() ? "/mcp" : defaultPath;
      this.timeoutSeconds = timeoutSeconds;
    }
  }

  private static final class LlmConfig {
    final String aiBackendUrl;
    final String aiBackendAuthHeader;
    final String aiBackendAuthPrefix;
    final String aiBackendAuthValue;
    final String defaultAiModel;
    final String llmMode;
    final String systemPrompt;
    final int requestTimeoutSeconds;
    final boolean unwrapAnswer;

    LlmConfig(
        String aiBackendUrl,
        String aiBackendAuthHeader,
        String aiBackendAuthPrefix,
        String aiBackendAuthValue,
        String defaultAiModel,
        String llmMode,
        String systemPrompt,
        int requestTimeoutSeconds,
        boolean unwrapAnswer) {
      this.aiBackendUrl = aiBackendUrl == null ? "" : aiBackendUrl;
      this.aiBackendAuthHeader = aiBackendAuthHeader;
      this.aiBackendAuthPrefix = aiBackendAuthPrefix;
      this.aiBackendAuthValue = aiBackendAuthValue == null ? "" : aiBackendAuthValue;
      this.defaultAiModel = defaultAiModel;
      this.llmMode = llmMode;
      this.systemPrompt = systemPrompt;
      this.requestTimeoutSeconds = requestTimeoutSeconds;
      this.unwrapAnswer = unwrapAnswer;
    }
  }
}
