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
const REQUEST_TIMEOUT_MS = 45_000;

const getAuthHeaders = () => {
  const token = localStorageUtils?.getAuthToken?.();
  return {
    "Content-Type": "application/json",
    ...(token ? { Authorization: token } : {}),
  };
};

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

  async ask(
    question: string,
    sessionId: string,
    signal?: AbortSignal,
  ): Promise<AskResponse> {
    const controller = new AbortController();
    const timeout = window.setTimeout(
      () => controller.abort(),
      REQUEST_TIMEOUT_MS,
    );
    const onAbort = () => controller.abort();
    signal?.addEventListener("abort", onAbort);

    try {
      const response = await fetch("/aichat/ask", {
        method: "POST",
        headers: getAuthHeaders(),
        signal: controller.signal,
        body: JSON.stringify({ question, sessionId }),
      });

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }

      const payload = (await response.json()) as AskResponse;
      return payload;
    } finally {
      signal?.removeEventListener("abort", onAbort);
      window.clearTimeout(timeout);
    }
  },
};
