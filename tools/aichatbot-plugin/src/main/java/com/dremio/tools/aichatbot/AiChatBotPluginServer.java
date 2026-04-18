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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

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

    final HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    final HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

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
    server.createContext("/aichat/config", exchange -> handleConfig(exchange, llmConfig, dremioBaseUrl));
    server.createContext(
        "/aichat/context", exchange -> handleContext(exchange, httpClient, dremioBaseUrl));
    server.createContext(
        "/aichat/ask",
        exchange -> handleAsk(exchange, httpClient, dremioBaseUrl, llmConfig));
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
    System.out.printf("Open UI: http://localhost:%d/aichat/chat.html%n", port);
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

  private static void handleConfig(HttpExchange exchange, LlmConfig cfg, String dremioBaseUrl)
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
            + "\"defaultModel\":\""
            + escapeJson(cfg.defaultAiModel)
            + "\","
            + "\"requestTimeoutSeconds\":"
            + cfg.requestTimeoutSeconds
            + ","
            + "\"unwrapOpenAiContent\":"
            + cfg.unwrapAnswer
            + "}";
    writeJson(exchange, 200, body);
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
