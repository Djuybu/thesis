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
import DOMPurify from "dompurify";
import { marked } from "marked";
import type { ChatMessage, ChatSession, DataRow, ParsedMessage } from "./types";

const sqlFenceRegex = /```sql\s*([\s\S]*?)```/gi;
const DEFAULT_TITLE = "Cuộc trò chuyện mới";

export const uid = () =>
  `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`;

const parseSqlBlocks = (raw: string): string[] => {
  const blocks: string[] = [];
  raw.replace(sqlFenceRegex, (_full, group: string) => {
    blocks.push(group.trim());
    return _full;
  });
  return blocks;
};

const parseTableRows = (raw: string): DataRow[] => {
  try {
    const maybeArray = JSON.parse(raw);
    if (!Array.isArray(maybeArray)) return [];
    if (!maybeArray.every((row) => row && typeof row === "object")) return [];
    return maybeArray as DataRow[];
  } catch {
    return [];
  }
};

export const parseMessageContent = (
  raw: string,
  dataRows: DataRow[] = [],
): ParsedMessage => {
  const html = DOMPurify.sanitize(marked.parse(raw) as string, {
    USE_PROFILES: { html: true },
  });
  return {
    html,
    sqlBlocks: parseSqlBlocks(raw),
    tableRows: dataRows.length ? dataRows : parseTableRows(raw),
  };
};

export const createWelcomeMessage = (): ChatMessage => {
  const raw = "Xin chào! Tôi là AI Chatbot. Hãy hỏi tôi về dữ liệu hoặc SQL.";
  return {
    id: uid(),
    role: "assistant",
    raw,
    parsed: parseMessageContent(raw),
    createdAt: Date.now(),
  };
};

export const createSession = (): ChatSession => {
  const now = Date.now();
  return {
    id: uid(),
    title: DEFAULT_TITLE,
    createdAt: now,
    updatedAt: now,
    messages: [createWelcomeMessage()],
  };
};

export const deriveSessionTitle = (
  session: ChatSession,
  message: ChatMessage,
) => {
  if (session.title !== DEFAULT_TITLE || message.role !== "user") {
    return session.title;
  }
  return message.raw.slice(0, 40) || DEFAULT_TITLE;
};
