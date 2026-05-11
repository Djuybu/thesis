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
import type { ChatApiResponse, ChatSession, ConfigApiResponse } from "./types";

const STORAGE_KEY = "aichatbot-plugin-sessions";
const SQL_DRAFT_KEY = "aichatbot-plugin-sql-draft";
const REQUEST_TIMEOUT_MS = 86_500_000;

let cachedConfig: ConfigApiResponse | null = null;

async function loadConfig(): Promise<ConfigApiResponse | null> {
  if (cachedConfig) return cachedConfig;
  try {
    const res = await fetch("/aichat/v1/config");
    if (res.ok) {
      cachedConfig = (await res.json()) as ConfigApiResponse;
      return cachedConfig;
    }
  } catch {
    // ignore
  }
  return null;
}

export function hasAuthToken(): boolean {
  const token = localStorageUtils?.getAuthToken?.();
  return Boolean(token && String(token).trim());
}

function authorizationHeaderForAichat(
  uiToken: string | null | undefined,
): string | undefined {
  const raw = uiToken != null ? String(uiToken).trim() : "";
  if (!raw) return undefined;
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
    if (!text) return `HTTP ${response.status}`;
    try {
      const json = JSON.parse(text) as { error?: string; detail?: string };
      const msg = json?.error || json?.detail;
      if (typeof msg === "string" && msg.trim()) return msg.trim();
    } catch {
      // not JSON
    }
    return text.trim().slice(0, 200) || `HTTP ${response.status}`;
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

  async getConfig(): Promise<ConfigApiResponse | null> {
    return loadConfig();
  },

  async startChat(
    message: string,
    threadId?: string,
    signal?: AbortSignal,
  ): Promise<ChatApiResponse> {
    if (!hasAuthToken()) throw new Error("MISSING_AUTH");

    const controller = new AbortController();
    const timeout = window.setTimeout(
      () => controller.abort(),
      REQUEST_TIMEOUT_MS,
    );
    const onAbort = () => controller.abort();
    signal?.addEventListener("abort", onAbort);

    try {
      const response = await fetch("/aichat/v1/chat", {
        method: "POST",
        headers: getAuthHeaders(),
        signal: controller.signal,
        body: JSON.stringify({
          message,
          ...(threadId ? { thread_id: threadId } : {}),
        }),
      });

      if (!response.ok) {
        const detail = await readErrorMessage(response);
        throw new Error(
          response.status === 401
            ? `HTTP 401: ${detail}`
            : `HTTP ${response.status}: ${detail}`,
        );
      }

      return (await response.json()) as ChatApiResponse;
    } finally {
      signal?.removeEventListener("abort", onAbort);
      window.clearTimeout(timeout);
    }
  },

  async resumeChat(
    threadId: string,
    action: "approve" | "reject" | "edit",
    payload?: Record<string, unknown>,
    signal?: AbortSignal,
  ): Promise<ChatApiResponse> {
    if (!hasAuthToken()) throw new Error("MISSING_AUTH");

    const controller = new AbortController();
    const timeout = window.setTimeout(
      () => controller.abort(),
      REQUEST_TIMEOUT_MS,
    );
    const onAbort = () => controller.abort();
    signal?.addEventListener("abort", onAbort);

    try {
      const response = await fetch("/aichat/v1/chat/resume", {
        method: "POST",
        headers: getAuthHeaders(),
        signal: controller.signal,
        body: JSON.stringify({
          thread_id: threadId,
          action,
          ...(payload ? { payload } : {}),
        }),
      });

      if (!response.ok) {
        const detail = await readErrorMessage(response);
        throw new Error(`HTTP ${response.status}: ${detail}`);
      }

      return (await response.json()) as ChatApiResponse;
    } finally {
      signal?.removeEventListener("abort", onAbort);
      window.clearTimeout(timeout);
    }
  },

  /**
   * @deprecated Kept for backward compatibility. Prefer startChat + resumeChat.
   */
  async ask(prompt: string, signal?: AbortSignal) {
    const resp = await this.startChat(prompt, undefined, signal);
    return {
      answer: resp.answer ?? resp.error ?? "",
      response: resp.answer,
      data: [],
    };
  },
};
