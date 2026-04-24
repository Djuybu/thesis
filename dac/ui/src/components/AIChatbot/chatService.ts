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

  async ask(question: string, sessionId: string): Promise<AskResponse> {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

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
      window.clearTimeout(timeout);
    }
  },
};
