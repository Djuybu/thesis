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
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

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
  private static final int MAX_HISTORY_MESSAGES_PER_SESSION = 200;
  private static final int MAX_HISTORY_MESSAGES_FOR_PROMPT = 20;

  /** Pass-through JSON to a custom gateway (legacy). */
  private static final String MODE_CUSTOM = "custom";

  /** OpenAI Chat Completions compatible body (Ollama, LM Studio, vLLM, ...). */
  private static final String MODE_OPENAI = "openai";

  private AiChatBotPluginServer() {}

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
    final String aiBackendAuthValue =
        Optional.ofNullable(System.getenv("AI_BACKEND_AUTH")).orElse("");
    final String defaultAiModel =
        Optional.ofNullable(System.getenv("AI_MODEL_DEFAULT")).orElse("llama3.2");
    final String llmMode = resolveLlmMode(System.getenv("AI_LLM_MODE"), aiBackendUrl);
    final String systemPrompt =
        Optional.ofNullable(System.getenv("AI_SYSTEM_PROMPT"))
            .orElse(
                "You are a helpful assistant for Dremio data lakehouse users. "
                    + "Answer clearly. If user context is provided, personalize briefly (do not dump secrets).");
    final int requestTimeoutSeconds =
        Integer.parseInt(
            Optional.ofNullable(System.getenv("AI_REQUEST_TIMEOUT_SECONDS")).orElse("120"));
    final boolean unwrapAnswer =
        Boolean.parseBoolean(
            Optional.ofNullable(System.getenv("AI_UNWRAP_OPENAI_CONTENT")).orElse("true"));
    final String mcpHttpBase =
        stripTrailingSlash(
            Optional.ofNullable(System.getenv("DREMIO_MCP_HTTP_BASE")).orElse("").trim());
    final String mcpDefaultPath =
        normalizeMcpPath(Optional.ofNullable(System.getenv("DREMIO_MCP_HTTP_PATH")).orElse("/mcp"));
    final int mcpProxyTimeoutSeconds =
        Integer.parseInt(
            Optional.ofNullable(System.getenv("DREMIO_MCP_PROXY_TIMEOUT_SECONDS")).orElse("300"));
    final McpProxyConfig mcpProxyConfig =
        new McpProxyConfig(mcpHttpBase, mcpDefaultPath, mcpProxyTimeoutSeconds);

    final HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    final HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
    final ChatHistoryStore historyStore =
        new ChatHistoryStore(MAX_HISTORY_MESSAGES_PER_SESSION);

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
        "/aichat/config",
        exchange -> handleConfig(exchange, llmConfig, dremioBaseUrl, mcpProxyConfig));
    server.createContext(
        "/aichat/context", exchange -> handleContext(exchange, httpClient, dremioBaseUrl));
    server.createContext(
        "/aichat/history",
        exchange -> handleHistory(exchange, httpClient, dremioBaseUrl, historyStore));
    server.createContext(
        "/aichat/ask",
        exchange -> handleAsk(exchange, httpClient, dremioBaseUrl, llmConfig, historyStore));
    server.createContext(
        "/aichat/mcp-proxy",
        exchange -> handleMcpProxy(exchange, httpClient, dremioBaseUrl, mcpProxyConfig));
    server.createContext("/aichat/", exchange -> handleStaticOrOptions(exchange, "aichat/"));
    server.setExecutor(null);
    server.start();

    System.out.printf("AI Chat plugin server is running at http://localhost:%d%n", port);
    System.out.printf("Dremio upstream configured as %s%n", dremioBaseUrl);
    System.out.printf("LLM mode: %s (set AI_LLM_MODE=openai|custom)%n", llmMode);
    if (aiBackendUrl.isBlank()) {
      System.out.println("AI_BACKEND_URL is not set, /aichat/ask returns mock after Dremio auth.");
    } else {
      System.out.printf("AI backend URL: %s%n", aiBackendUrl);
    }
    if (mcpHttpBase.isBlank()) {
      System.out.println("DREMIO_MCP_HTTP_BASE is not set; /aichat/mcp-proxy is disabled.");
    } else {
      System.out.printf(
          "Dremio MCP HTTP proxy target: %s (default path %s)%n", mcpHttpBase, mcpDefaultPath);
    }
    System.out.printf("Open UI: http://localhost:%d/aichat/chat.html%n", port);
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
   * same {@code Authorization} header is forwarded so MCP can verify the bearer token (OAuth / PAT
   * flow as configured in dremio-mcp).
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
        sanitizeMcpPath(pathParam != null && !pathParam.isBlank() ? pathParam : mcp.defaultPath);
    if (path == null) {
      writeJson(exchange, 400, jsonError("Invalid or unsafe path (use ?path=/mcp or similar)"));
      return;
    }
    final String upstreamUrl = mcp.baseUrl + path;
    if (!upstreamUrl.startsWith("http://") && !upstreamUrl.startsWith("https://")) {
      writeJson(exchange, 400, jsonError("DREMIO_MCP_HTTP_BASE must be an http(s) URL"));
      return;
    }
    try {
      URI.create(upstreamUrl);
    } catch (IllegalArgumentException e) {
      writeJson(exchange, 400, jsonError("Bad MCP URL"));
      return;
    }
    try {
      final HttpResponse<String> login = fetchLoginInfo(client, dremioBaseUrl, token);
      if (login.statusCode() >= 400) {
        writeJson(exchange, 401, jsonError("Invalid Dremio token"));
        return;
      }
      final HttpRequest.Builder builder =
          HttpRequest.newBuilder()
              .uri(URI.create(upstreamUrl))
              .timeout(Duration.ofSeconds(mcp.timeoutSeconds))
              .header("Authorization", token);
      final String accept = exchange.getRequestHeaders().getFirst("Accept");
      if (accept != null && !accept.isBlank()) {
        builder.header("Accept", accept);
      }
      if ("POST".equalsIgnoreCase(method)) {
        final String ct = exchange.getRequestHeaders().getFirst(CONTENT_TYPE);
        if (ct != null && !ct.isBlank()) {
          builder.header(CONTENT_TYPE, ct);
        } else {
          builder.header(CONTENT_TYPE, APPLICATION_JSON_PLAIN);
        }
        final byte[] raw = readRequestBodyBytes(exchange.getRequestBody());
        builder.POST(HttpRequest.BodyPublishers.ofByteArray(raw));
      } else {
        builder.GET();
      }
      final HttpResponse<String> upstream =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      final String respCt =
          upstream.headers().firstValue(CONTENT_TYPE).orElse(APPLICATION_JSON_PLAIN);
      writeRaw(
          exchange,
          upstream.statusCode(),
          upstream.body().getBytes(StandardCharsets.UTF_8),
          respCt);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      writeJson(exchange, 500, jsonError("Interrupted"));
    } catch (Exception e) {
      writeJson(exchange, 502, jsonError("MCP upstream error: " + sanitize(e.getMessage())));
    }
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
          client.send(
              loginCheckRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
      HttpExchange exchange,
      HttpClient client,
      String dremioBaseUrl,
      LlmConfig cfg,
      ChatHistoryStore historyStore)
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
    final String bodySessionId = extractStringValue(body, "sessionId");
    final String headerSessionId = exchange.getRequestHeaders().getFirst("X-Chat-Session-Id");
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
      final String sessionId =
          firstNonBlank(
              trimToNull(bodySessionId),
              trimToNull(headerSessionId),
              username == null || username.isBlank() ? null : "user:" + username,
              "default");

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
                + "\",\"sessionId\":\""
                + escapeJson(sessionId)
                + "\"}";
        writeJson(exchange, 200, answer);
        return;
      }

      historyStore.append(sessionId, "user", prompt);

      if (MODE_CUSTOM.equalsIgnoreCase(cfg.llmMode)) {
        final ArrayList<ChatHistoryEntry> recentHistory =
            historyStore.read(sessionId, MAX_HISTORY_MESSAGES_FOR_PROMPT);
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
                + ",\"sessionId\":\""
                + escapeJson(sessionId)
                + "\",\"chatHistory\":"
                + buildChatHistoryJsonArray(recentHistory)
                + "}";
        sendLlmRequest(exchange, client, cfg, aiRequestBody, historyStore, sessionId);
        return;
      }

      final ArrayList<ChatHistoryEntry> recentHistory =
          historyStore.read(sessionId, MAX_HISTORY_MESSAGES_FOR_PROMPT);
      final String systemBlock =
          (overrideSystem != null && !overrideSystem.isBlank() ? overrideSystem : cfg.systemPrompt)
              + (userContext.isBlank()
                  ? ""
                  : "\n\n--- Dremio user profile (JSON, do not repeat verbatim if sensitive) ---\n"
                      + userContext);
      final String openAiBody =
          buildOpenAiChatCompletionsBody(
              model, systemBlock, recentHistory, prompt, temperature, maxTokens);
      sendLlmRequest(exchange, client, cfg, openAiBody, historyStore, sessionId);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      writeJson(exchange, 500, jsonError("Interrupted while processing request"));
    } catch (Exception e) {
      writeJson(
          exchange, 500, jsonError("Failed to process chat request: " + sanitize(e.getMessage())));
    }
  }

  /**
   * In-memory chat history APIs.
   *
   * <p>- POST /aichat/history body: {"sessionId":"...","role":"user|assistant|system","message":"..."}
   *
   * <p>- GET /aichat/history?sessionId=...&limit=50
   *
   * <p>- DELETE /aichat/history?sessionId=...
   */
  private static void handleHistory(
      HttpExchange exchange, HttpClient client, String dremioBaseUrl, ChatHistoryStore store)
      throws IOException {
    addCors(exchange);
    if (handleOptions(exchange)) {
      return;
    }
    final String token = getAuthorization(exchange);
    if (token == null) {
      writeJson(exchange, 401, jsonError("Missing Authorization header"));
      return;
    }
    try {
      final HttpResponse<String> login = fetchLoginInfo(client, dremioBaseUrl, token);
      if (login.statusCode() >= 400) {
        writeJson(exchange, 401, jsonError("Invalid Dremio token"));
        return;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      writeJson(exchange, 500, jsonError("Interrupted while validating token"));
      return;
    } catch (Exception e) {
      writeJson(exchange, 500, jsonError("Failed to validate token: " + sanitize(e.getMessage())));
      return;
    }

    final String method = exchange.getRequestMethod();
    if ("POST".equalsIgnoreCase(method)) {
      final String body = readRequestBody(exchange.getRequestBody());
      final String sessionId = extractStringValue(body, "sessionId");
      final String role = normalizeHistoryRole(extractStringValue(body, "role"));
      final String message = extractStringValue(body, "message");
      if (sessionId == null || sessionId.isBlank()) {
        writeJson(exchange, 400, jsonError("Field 'sessionId' is required"));
        return;
      }
      if (role == null) {
        writeJson(exchange, 400, jsonError("Field 'role' must be one of: user, assistant, system"));
        return;
      }
      if (message == null || message.isBlank()) {
        writeJson(exchange, 400, jsonError("Field 'message' is required"));
        return;
      }
      final ChatHistoryEntry saved = store.append(sessionId, role, message);
      writeJson(
          exchange,
          200,
          "{"
              + "\"saved\":true,"
              + "\"sessionId\":\""
              + escapeJson(saved.sessionId)
              + "\","
              + "\"role\":\""
              + escapeJson(saved.role)
              + "\","
              + "\"message\":\""
              + escapeJson(saved.message)
              + "\","
              + "\"timestamp\":\""
              + escapeJson(saved.timestampIso)
              + "\""
              + "}");
      return;
    }

    final String query = exchange.getRequestURI().getRawQuery();
    final String sessionId = parseQueryParameter(query, "sessionId");
    if (sessionId == null || sessionId.isBlank()) {
      writeJson(exchange, 400, jsonError("Query 'sessionId' is required"));
      return;
    }

    if ("GET".equalsIgnoreCase(method)) {
      final int limit = parsePositiveIntOrDefault(parseQueryParameter(query, "limit"), 50);
      final ArrayList<ChatHistoryEntry> history = store.read(sessionId, limit);
      final StringBuilder sb = new StringBuilder();
      sb.append("{\"sessionId\":\"").append(escapeJson(sessionId)).append("\",");
      sb.append("\"count\":").append(history.size()).append(",");
      sb.append("\"messages\":[");
      for (int i = 0; i < history.size(); i++) {
        if (i > 0) {
          sb.append(',');
        }
        final ChatHistoryEntry e = history.get(i);
        sb.append("{\"role\":\"")
            .append(escapeJson(e.role))
            .append("\",\"message\":\"")
            .append(escapeJson(e.message))
            .append("\",\"timestamp\":\"")
            .append(escapeJson(e.timestampIso))
            .append("\"}");
      }
      sb.append("]}");
      writeJson(exchange, 200, sb.toString());
      return;
    }

    if ("DELETE".equalsIgnoreCase(method)) {
      final int removed = store.clear(sessionId);
      writeJson(
          exchange,
          200,
          "{"
              + "\"deleted\":true,"
              + "\"sessionId\":\""
              + escapeJson(sessionId)
              + "\","
              + "\"removed\":"
              + removed
              + "}");
      return;
    }

    writeJson(exchange, 405, jsonError("Method not allowed"));
  }

  private static int parsePositiveIntOrDefault(String raw, int fallback) {
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      final int parsed = Integer.parseInt(raw.trim());
      return parsed > 0 ? parsed : fallback;
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static String normalizeHistoryRole(String role) {
    if (role == null || role.isBlank()) {
      return null;
    }
    final String v = role.trim().toLowerCase(Locale.ROOT);
    if ("user".equals(v) || "assistant".equals(v) || "system".equals(v)) {
      return v;
    }
    return null;
  }

  private static String trimToNull(String s) {
    if (s == null) {
      return null;
    }
    final String t = s.trim();
    return t.isBlank() ? null : t;
  }

  private static String buildChatHistoryJsonArray(ArrayList<ChatHistoryEntry> history) {
    if (history == null || history.isEmpty()) {
      return "[]";
    }
    final StringBuilder sb = new StringBuilder();
    sb.append('[');
    for (int i = 0; i < history.size(); i++) {
      if (i > 0) {
        sb.append(',');
      }
      final ChatHistoryEntry e = history.get(i);
      sb.append("{\"role\":\"")
          .append(escapeJson(e.role))
          .append("\",\"message\":\"")
          .append(escapeJson(e.message))
          .append("\",\"timestamp\":\"")
          .append(escapeJson(e.timestampIso))
          .append("\"}");
    }
    sb.append(']');
    return sb.toString();
  }

  private static String extractAssistantMessageForHistory(LlmConfig cfg, String responseBody) {
    if (responseBody == null || responseBody.isBlank()) {
      return null;
    }
    final String openAiContent = tryExtractOpenAiMessageContent(responseBody);
    if (openAiContent != null && !openAiContent.isBlank()) {
      return openAiContent;
    }
    final String wrappedAnswer = extractStringValue(responseBody, "answer");
    if (wrappedAnswer != null && !wrappedAnswer.isBlank()) {
      return wrappedAnswer;
    }
    if (MODE_CUSTOM.equalsIgnoreCase(cfg.llmMode)) {
      return responseBody;
    }
    return null;
  }

  private static void sendLlmRequest(
      HttpExchange exchange,
      HttpClient client,
      LlmConfig cfg,
      String requestBody,
      ChatHistoryStore historyStore,
      String sessionId)
      throws IOException, InterruptedException {
    final HttpRequest.Builder aiRequestBuilder =
        HttpRequest.newBuilder()
            .uri(URI.create(cfg.aiBackendUrl))
            .header("Accept", APPLICATION_JSON_PLAIN)
            .header(CONTENT_TYPE, APPLICATION_JSON_PLAIN)
            .timeout(Duration.ofSeconds(cfg.requestTimeoutSeconds))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
    if (cfg.aiBackendAuthValue != null && !cfg.aiBackendAuthValue.isBlank()) {
      aiRequestBuilder.header(
          cfg.aiBackendAuthHeader, cfg.aiBackendAuthPrefix + cfg.aiBackendAuthValue);
    }

    final HttpResponse<String> aiResponse =
        client.send(
            aiRequestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

    final String assistantMessage = extractAssistantMessageForHistory(cfg, aiResponse.body());
    if (aiResponse.statusCode() < 400 && assistantMessage != null && !assistantMessage.isBlank()) {
      historyStore.append(sessionId, "assistant", assistantMessage);
    }

    final boolean openAiMode = MODE_OPENAI.equalsIgnoreCase(cfg.llmMode);
    if (!openAiMode || !cfg.unwrapAnswer || aiResponse.statusCode() >= 400) {
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
      ArrayList<ChatHistoryEntry> history,
      String userContent,
      Double temperature,
      Integer maxTokens) {
    final StringBuilder sb = new StringBuilder();
    sb.append('{');
    sb.append("\"model\":\"").append(escapeJson(model)).append("\",");
    sb.append("\"messages\":[");
    sb.append("{\"role\":\"system\",\"content\":\"")
        .append(escapeJson(systemContent))
        .append("\"},");
    if (history != null && !history.isEmpty()) {
      for (ChatHistoryEntry e : history) {
        if (!"user".equals(e.role) && !"assistant".equals(e.role)) {
          continue;
        }
        sb.append("{\"role\":\"")
            .append(escapeJson(e.role))
            .append("\",\"content\":\"")
            .append(escapeJson(e.message))
            .append("\"},");
      }
    }
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
    while (i < json.length()
        && (Character.isDigit(json.charAt(i)) || ".-+eE".indexOf(json.charAt(i)) >= 0)) {
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

  private static void handleStaticOrOptions(HttpExchange exchange, String prefix)
      throws IOException {
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

  private static void serveResource(
      HttpExchange exchange, String classpathResource, String contentType) throws IOException {
    try (InputStream in =
        AiChatBotPluginServer.class.getClassLoader().getResourceAsStream(classpathResource)) {
      if (in == null) {
        writeJson(
            exchange,
            404,
            "{\"error\":\"Resource missing: " + escapeJson(classpathResource) + "\"}");
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
    exchange
        .getResponseHeaders()
        .set(
            "Access-Control-Allow-Headers",
            "Authorization, Content-Type, X-Dremio-Username, X-Chat-Session-Id");
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

  private static void writeRaw(
      HttpExchange exchange, int statusCode, byte[] body, String contentType) throws IOException {
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
      HttpClient client, String dremioBaseUrl, String token)
      throws IOException, InterruptedException {
    final HttpRequest loginCheckRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(dremioBaseUrl + "/apiv2/login"))
            .header("Authorization", token)
            .header("Accept", APPLICATION_JSON_PLAIN)
            .GET()
            .build();
    return client.send(
        loginCheckRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

  private static void writeJson(HttpExchange exchange, int statusCode, String body)
      throws IOException {
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

  private static final class ChatHistoryStore {
    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<ChatHistoryEntry>> bySession =
        new ConcurrentHashMap<>();
    private final int maxMessagesPerSession;

    ChatHistoryStore(int maxMessagesPerSession) {
      this.maxMessagesPerSession = Math.max(1, maxMessagesPerSession);
    }

    ChatHistoryEntry append(String sessionId, String role, String message) {
      final String sid = sessionId.trim();
      final ChatHistoryEntry entry =
          new ChatHistoryEntry(sid, role, message, Instant.now().toString());
      final ConcurrentLinkedDeque<ChatHistoryEntry> q =
          bySession.computeIfAbsent(sid, ignored -> new ConcurrentLinkedDeque<>());
      q.addLast(entry);
      while (q.size() > maxMessagesPerSession) {
        q.pollFirst();
      }
      return entry;
    }

    ArrayList<ChatHistoryEntry> read(String sessionId, int limit) {
      final Deque<ChatHistoryEntry> q = bySession.get(sessionId.trim());
      if (q == null || q.isEmpty()) {
        return new ArrayList<>();
      }
      final int cap = Math.max(1, limit);
      final ArrayList<ChatHistoryEntry> out = new ArrayList<>(Math.min(cap, q.size()));
      for (ChatHistoryEntry e : q) {
        out.add(e);
        if (out.size() >= cap) {
          break;
        }
      }
      return out;
    }

    int clear(String sessionId) {
      final ConcurrentLinkedDeque<ChatHistoryEntry> removed = bySession.remove(sessionId.trim());
      return removed == null ? 0 : removed.size();
    }
  }

  private static final class ChatHistoryEntry {
    final String sessionId;
    final String role;
    final String message;
    final String timestampIso;

    ChatHistoryEntry(String sessionId, String role, String message, String timestampIso) {
      this.sessionId = sessionId;
      this.role = role;
      this.message = message;
      this.timestampIso = timestampIso;
    }
  }
}
