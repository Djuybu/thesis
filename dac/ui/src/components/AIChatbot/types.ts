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
export type ChatRole = "user" | "assistant";

export type DataRow = Record<string, unknown>;

export type ParsedMessage = {
  html: string;
  sqlBlocks: string[];
  tableRows: DataRow[];
};

export type ChatMessage = {
  id: string;
  role: ChatRole;
  raw: string;
  parsed: ParsedMessage;
  createdAt: number;
  feedback?: "up" | "down";
};

export type ChatSession = {
  id: string;
  title: string;
  createdAt: number;
  updatedAt: number;
  messages: ChatMessage[];
  pinned?: boolean;
  threadId?: string;
};

export type HitlStatus = "interrupted" | "completed" | "error";

export type HitlInterrupt = {
  action: string;
  message: string;
  table_fqn?: string;
  schema_text?: string;
  discover_excerpt?: string;
  proposed_sql?: string;
  rationale?: string;
};

export type ChatApiResponse = {
  status: HitlStatus;
  thread_id: string;
  model: string;
  node?: string;
  interrupt?: HitlInterrupt;
  answer?: string;
  execution_result?: unknown;
  error?: string;
  /** Wall-clock time for this agent run (gateway), milliseconds */
  elapsed_ms?: number;
  /** Per LangGraph node, milliseconds (e.g. finalize, sql_gen, execute) */
  step_timings_ms?: Record<string, number>;
  token_usage?: {
    input_tokens: number;
    output_tokens: number;
    total_tokens: number;
  };
  step_token_usage?: Record<
    string,
    { input_tokens: number; output_tokens: number; total_tokens: number }
  >;
};

export type ConfigApiResponse = {
  service: string;
  version: string;
  default_model: string;
  hitl_enabled: boolean;
  guardrail_enabled: boolean;
  mcp_configured: boolean;
};

/** @deprecated kept for backward compat with old AskResponse shape */
export type AskResponse = {
  response?: string;
  answer?: string;
  content?: string;
  data?: DataRow[];
};
