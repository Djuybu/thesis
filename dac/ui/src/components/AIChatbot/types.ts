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
};

export type AskResponse = {
  response?: string;
  answer?: string;
  content?: string;
  data?: DataRow[];
};
