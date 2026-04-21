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
};

export type ChatSession = {
  id: string;
  title: string;
  createdAt: number;
  updatedAt: number;
  messages: ChatMessage[];
};

export type AskResponse = {
  response?: string;
  answer?: string;
  content?: string;
  data?: DataRow[];
};
