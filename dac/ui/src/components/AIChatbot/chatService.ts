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
import localStorageUtils from "@inject/utils/storageUtils/localStorageUtils";
import type { AskResponse, ChatSession } from "./types";

const STORAGE_KEY = "aichatbot-plugin-sessions";
const SQL_DRAFT_KEY = "aichatbot-plugin-sql-draft";
/** Browser fetch must outlive coordinator → plugin → gateway (align with long server defaults). */
const REQUEST_TIMEOUT_MS = 86_500_000;

/** Ollama model id when /aichat/config is unavailable (align with plugin default). */
const FALLBACK_LLM_MODEL = "gemma4:e4b";

let cachedPluginDefaultModel: string | null = null;

async function resolveModelForAsk(): Promise<string> {
  if (cachedPluginDefaultModel) {
    return cachedPluginDefaultModel;
  }
  try {
    const res = await fetch("/aichat/config");
    if (res.ok) {
      const j = (await res.json()) as { defaultModel?: string };
      const m = j.defaultModel != null ? String(j.defaultModel).trim() : "";
      if (m) {
        cachedPluginDefaultModel = m;
        return m;
      }
    }
  } catch {
    // ignore — use fallback
  }
  cachedPluginDefaultModel = FALLBACK_LLM_MODEL;
  return cachedPluginDefaultModel;
}

/** True when the Dremio UI session has a token (same shape as other API calls). */
export function hasAuthToken(): boolean {
  const token = localStorageUtils?.getAuthToken?.();
  return Boolean(token && String(token).trim());
}

/**
 * {@code localStorageUtils.getAuthToken()} returns {@code _dremio<secret>} for legacy REST;
 * aichat plugin + LangChain gateway expect {@code Bearer <secret>}.
 */
function authorizationHeaderForAichat(
  uiToken: string | null | undefined,
): string | undefined {
  const raw = uiToken != null ? String(uiToken).trim() : "";
  if (!raw) {
    return undefined;
  }
  const lower = raw.toLowerCase();
  if (lower.startsWith("bearer ")) {
    const secret = raw.slice(7).trim();
    return secret ? `Bearer ${secret}` : undefined;
  }
  if (lower.startsWith("_dremio")) {
    const secret = raw.slice("_dremio".length).trim();
    return secret ? `Bearer ${secret}` : undefined;
  }
  return `Bearer ${raw}`;
}

const getAuthHeaders = (): Record<string, string> => {
  const token = localStorageUtils?.getAuthToken?.();
  const user = localStorageUtils?.getUserData?.() as {
    userName?: string;
  } | null;
  const userName =
    user && typeof user.userName === "string" && user.userName.trim()
      ? user.userName.trim()
      : "";

  const headers: Record<string, string> = {
    "Content-Type": "application/json",
  };
  const auth = authorizationHeaderForAichat(token ?? undefined);
  if (auth) {
    headers.Authorization = auth;
  }
  if (userName) {
    headers["X-Dremio-Username"] = userName;
  }
  return headers;
};

async function readErrorMessage(response: Response): Promise<string> {
  try {
    const text = await response.text();
    if (!text) {
      return `HTTP ${response.status}`;
    }
    try {
      const json = JSON.parse(text) as { error?: string };
      if (json && typeof json.error === "string" && json.error.trim()) {
        return json.error.trim();
      }
    } catch {
      // not JSON
    }
    const trimmed = text.trim().slice(0, 200);
    return trimmed || `HTTP ${response.status}`;
  } catch {
    return `HTTP ${response.status}`;
  }
}

export const chatService = {
  loadSessions(): ChatSession[] {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return [];
      const parsed = JSON.parse(raw);
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  },

  saveSessions(sessions: ChatSession[]) {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(sessions));
  },

  storeSqlDraft(sql: string) {
    localStorage.setItem(SQL_DRAFT_KEY, sql);
  },

  /**
   * POST /aichat/ask on the same origin (DAC proxies to aichatbot-plugin).
   * Body matches {@code AiChatBotPluginServer#handleAsk}: {@code prompt}, optional model fields.
   */
  async ask(prompt: string, signal?: AbortSignal): Promise<AskResponse> {
    if (!hasAuthToken()) {
      throw new Error("MISSING_AUTH");
    }

    const controller = new AbortController();
    const timeout = window.setTimeout(
      () => controller.abort(),
      REQUEST_TIMEOUT_MS,
    );
    const onAbort = () => controller.abort();
    signal?.addEventListener("abort", onAbort);

    try {
      const model = await resolveModelForAsk();
      const response = await fetch("/aichat/ask", {
        method: "POST",
        headers: getAuthHeaders(),
        signal: controller.signal,
        body: JSON.stringify({ prompt, model }),
      });

      if (!response.ok) {
        const detail = await readErrorMessage(response);
        const err = new Error(
          response.status === 401
            ? `HTTP 401: ${detail}`
            : `HTTP ${response.status}: ${detail}`,
        );
        throw err;
      }

      const payload = (await response.json()) as AskResponse;
      return payload;
    } finally {
      signal?.removeEventListener("abort", onAbort);
      window.clearTimeout(timeout);
    }
  },
};
