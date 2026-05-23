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
import clsx from "clsx";
import { getSonarContext } from "dremio-ui-common/contexts/SonarContext.js";
import * as sqlPaths from "dremio-ui-common/paths/sqlEditor.js";
import { rmProjectBase } from "dremio-ui-common/utilities/projectBase.js";
import { useEffect, useMemo, useRef, useState } from "react";
import { chatService, hasAuthToken } from "./chatService";
import {
  createSession,
  deriveSessionTitle,
  parseMessageContent,
  uid,
} from "./parser";
import type {
  ChatApiResponse,
  ChatMessage,
  ChatSession,
  DataRow,
  HitlInterrupt,
} from "./types";
import * as classes from "./AIChatbot.module.less";
import { MessageActionBar } from "./MessageActionBar";

const MAX_PROMPT_LENGTH = 2000;
const SOFT_PROMPT_LIMIT = 1600;
const QUICK_PROMPTS = [
  "Tóm tắt bảng dữ liệu và cột quan trọng.",
  "Viết câu SQL để đếm số bản ghi theo ngày.",
  "Giải thích lỗi SQL và đề xuất cách sửa.",
];

const pickAnswerText = (payload: ChatApiResponse) => {
  if (payload.answer && payload.answer.trim()) return payload.answer;
  if (payload.error) return `Lỗi: ${payload.error}`;
  return "AI không trả về nội dung.";
};

const formatInterruptMessage = (interrupt: HitlInterrupt): string => {
  if (interrupt.action === "metadata_confirmation") {
    let msg = `**Xác nhận metadata trước khi sinh SQL:**\n\n`;
    if (interrupt.table_fqn) msg += `- **Bảng:** \`${interrupt.table_fqn}\`\n`;
    if (interrupt.schema_text)
      msg += `- **Schema:** ${interrupt.schema_text.slice(0, 500)}...\n`;
    msg += `\n${interrupt.message}`;
    return msg;
  }
  if (interrupt.action === "sql_approval") {
    let msg = `**Duyệt SQL trước khi thực thi:**\n\n`;
    if (interrupt.table_fqn) msg += `- **Bảng:** \`${interrupt.table_fqn}\`\n`;
    if (interrupt.proposed_sql)
      msg += `\n\`\`\`sql\n${interrupt.proposed_sql}\n\`\`\`\n`;
    if (interrupt.rationale) msg += `\n*${interrupt.rationale}*\n`;
    msg += `\n${interrupt.message}`;
    return msg;
  }
  return interrupt.message || "Cần phản hồi từ bạn.";
};

export const AIChatbot = () => {
  const initialSessionRef = useRef<ChatSession | null>(null);
  if (!initialSessionRef.current) {
    initialSessionRef.current = createSession();
  }

  const [isOpen, setIsOpen] = useState(false);
  const [input, setInput] = useState("");
  const [isTyping, setIsTyping] = useState(false);
  const [error, setError] = useState("");
  const [toast, setToast] = useState("");
  const [historyFilter, setHistoryFilter] = useState("");
  const [lastPrompt, setLastPrompt] = useState("");
  const [codeWrap, setCodeWrap] = useState(false);
  const [pendingInterrupt, setPendingInterrupt] =
    useState<HitlInterrupt | null>(null);
  const [pendingThreadId, setPendingThreadId] = useState<string | null>(null);
  const [interruptMessageId, setInterruptMessageId] = useState<string | null>(
    null,
  );
  const [sqlEditValue, setSqlEditValue] = useState("");
  const [sessions, setSessions] = useState<ChatSession[]>(() => [
    initialSessionRef.current as ChatSession,
  ]);
  const [activeSessionId, setActiveSessionId] = useState<string>(
    () => (initialSessionRef.current as ChatSession).id,
  );
  const messagesRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);
  const requestControllerRef = useRef<AbortController | null>(null);
  const hasLocalHistoryChangesRef = useRef(false);

  const activeSession = useMemo(
    () =>
      sessions.find((session) => session.id === activeSessionId) || sessions[0],
    [activeSessionId, sessions],
  );

  useEffect(() => {
    let cancelled = false;

    chatService
      .loadSessionsFromServer()
      .then((existing) => {
        if (cancelled || !existing.length) return;
        if (hasLocalHistoryChangesRef.current) return;
        setSessions(existing);
        setActiveSessionId(existing[0].id);
      })
      .catch(() => {
        // Keep the local in-memory welcome session if history cannot be loaded.
      });

    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (messagesRef.current) {
      messagesRef.current.scrollTop = messagesRef.current.scrollHeight;
    }
  }, [activeSession?.messages.length, isTyping]);

  useEffect(() => {
    if (!toast) return;
    const timeout = window.setTimeout(() => setToast(""), 1800);
    return () => window.clearTimeout(timeout);
  }, [toast]);

  useEffect(() => {
    const inputEl = inputRef.current;
    if (!inputEl) return;
    inputEl.style.height = "auto";
    inputEl.style.height = `${Math.min(inputEl.scrollHeight, 180)}px`;
  }, [input]);

  const updateSession = (
    sessionId: string,
    updater: (session: ChatSession) => ChatSession,
  ) => {
    hasLocalHistoryChangesRef.current = true;
    setSessions((prev) => {
      const next = prev.map((session) =>
        session.id === sessionId ? updater(session) : session,
      );
      chatService.saveSessions(next);
      return next;
    });
  };

  const appendMessage = (sessionId: string, message: ChatMessage) => {
    updateSession(sessionId, (session) => ({
      ...session,
      messages: [...session.messages, message],
      updatedAt: Date.now(),
      title: deriveSessionTitle(session, message),
    }));
  };

  const setMessageFeedback = (
    sessionId: string,
    messageId: string,
    feedback: "up" | "down",
  ) => {
    updateSession(sessionId, (session) => ({
      ...session,
      messages: session.messages.map((message) =>
        message.id === messageId
          ? {
              ...message,
              feedback: message.feedback === feedback ? undefined : feedback,
            }
          : message,
      ),
      updatedAt: Date.now(),
    }));
  };

  const onCreateSession = () => {
    const session = createSession();
    hasLocalHistoryChangesRef.current = true;
    setSessions((prev) => {
      const next = [session, ...prev];
      chatService.saveSessions(next);
      return next;
    });
    setActiveSessionId(session.id);
    setPendingInterrupt(null);
    setPendingThreadId(null);
    setInterruptMessageId(null);
    setIsOpen(true);
  };

  const openSqlInRunner = (sql: string) => {
    const trimmed = sql.trim();
    if (!trimmed) return;

    chatService.storeSqlDraft(trimmed);
    window.dispatchEvent(
      new CustomEvent("aichatbot-run-sql", { detail: { sql: trimmed } }),
    );

    const onSqlRunner = rmProjectBase(window.location.pathname).startsWith(
      "/new_query",
    );
    if (!onSqlRunner) {
      const projectId = getSonarContext()?.getSelectedProjectId?.();
      window.location.assign(sqlPaths.newQuery.link({ projectId }));
    } else {
      setToast("Đã gửi SQL sang SQL Runner.");
    }
  };

  const copyMessage = async (message: ChatMessage) => {
    try {
      await navigator.clipboard.writeText(message.raw);
      setToast("Đã sao chép nội dung.");
    } catch {
      setToast("Không sao chép được nội dung.");
    }
  };

  const copySqlText = async (sql: string) => {
    try {
      await navigator.clipboard.writeText(sql);
      setToast("Đã sao chép SQL.");
    } catch {
      setToast("Không sao chép được SQL.");
    }
  };

  const stopGenerating = () => {
    requestControllerRef.current?.abort();
  };

  const handleApiResponse = (payload: ChatApiResponse) => {
    if (!activeSession) return;

    if (payload.status === "interrupted" && payload.interrupt) {
      const interrupt = payload.interrupt as HitlInterrupt;
      setPendingInterrupt(interrupt);
      setPendingThreadId(payload.thread_id);
      if (interrupt.proposed_sql) {
        setSqlEditValue(interrupt.proposed_sql);
      }
      updateSession(activeSession.id, (s) => ({
        ...s,
        threadId: payload.thread_id,
      }));
      const raw = formatInterruptMessage(interrupt);
      const messageId = uid();
      setInterruptMessageId(messageId);
      appendMessage(activeSession.id, {
        id: messageId,
        role: "assistant",
        raw,
        parsed: parseMessageContent(raw),
        createdAt: Date.now(),
      });
    } else if (payload.status === "completed") {
      setPendingInterrupt(null);
      setPendingThreadId(null);
      setInterruptMessageId(null);
      const raw = pickAnswerText(payload);
      const dataRows: DataRow[] = Array.isArray(payload.execution_result)
        ? payload.execution_result
        : [];
      appendMessage(activeSession.id, {
        id: uid(),
        role: "assistant",
        raw,
        parsed: parseMessageContent(raw, dataRows),
        createdAt: Date.now(),
      });
    } else {
      setPendingInterrupt(null);
      setPendingThreadId(null);
      setInterruptMessageId(null);
      const raw = `Lỗi: ${payload.error || "Unknown error"}`;
      appendMessage(activeSession.id, {
        id: uid(),
        role: "assistant",
        raw,
        parsed: parseMessageContent(raw),
        createdAt: Date.now(),
      });
    }
  };

  const askAI = async (presetPrompt?: string) => {
    if (isTyping || !activeSession) return;

    const value = (presetPrompt ?? input).trim();
    if (!value) return;
    if (!hasAuthToken()) {
      setError("Cần đăng nhập Dremio để dùng AI Chat.");
      return;
    }
    if (value.length > MAX_PROMPT_LENGTH) {
      setError(`Prompt quá dài (${value.length}/${MAX_PROMPT_LENGTH}).`);
      return;
    }

    setError("");
    setLastPrompt(value);
    const requestController = new AbortController();
    requestControllerRef.current = requestController;

    appendMessage(activeSession.id, {
      id: uid(),
      role: "user",
      raw: value,
      parsed: parseMessageContent(value),
      createdAt: Date.now(),
    });
    setInput("");
    setIsTyping(true);

    try {
      const payload = await chatService.startChat(
        value,
        activeSession.threadId,
        requestController.signal,
      );
      handleApiResponse(payload);
    } catch (e) {
      const isAbort =
        e instanceof DOMException && e.name.toLowerCase() === "aborterror";
      if (isAbort) {
        setError("Đã dừng tạo phản hồi.");
        return;
      }
      const rawMsg = e instanceof Error ? e.message : "Không rõ lỗi";
      const msg =
        rawMsg === "MISSING_AUTH"
          ? "Cần đăng nhập Dremio để dùng AI Chat."
          : rawMsg.startsWith("HTTP 401")
            ? "Phiên đăng nhập không hợp lệ hoặc đã hết hạn."
            : rawMsg;
      setError(`Lỗi: ${msg}`);
      appendMessage(activeSession.id, {
        id: uid(),
        role: "assistant",
        raw: `Lỗi khi gọi AI: ${msg}`,
        parsed: parseMessageContent(`Lỗi khi gọi AI: ${msg}`),
        createdAt: Date.now(),
      });
    } finally {
      requestControllerRef.current = null;
      setIsTyping(false);
    }
  };

  const handleHitlAction = async (action: "approve" | "reject" | "edit") => {
    if (!pendingThreadId || !activeSession) return;
    setIsTyping(true);
    setError("");

    const actionLabel =
      action === "approve"
        ? "Đã phê duyệt"
        : action === "reject"
          ? "Đã từ chối"
          : "Đã sửa SQL";
    appendMessage(activeSession.id, {
      id: uid(),
      role: "user",
      raw: actionLabel,
      parsed: parseMessageContent(actionLabel),
      createdAt: Date.now(),
    });

    try {
      const payload = await chatService.resumeChat(
        pendingThreadId,
        action,
        action === "edit" ? { sql: sqlEditValue } : undefined,
      );
      handleApiResponse(payload);
    } catch (e) {
      const msg = e instanceof Error ? e.message : "Không rõ lỗi";
      setError(`Lỗi resume: ${msg}`);
    } finally {
      setIsTyping(false);
    }
  };

  const loggedIn = hasAuthToken();

  const hasUserMessages = activeSession.messages.some(
    (message) => message.role === "user",
  );
  const filteredSessions = sessions
    .filter((session) =>
      session.title.toLowerCase().includes(historyFilter.toLowerCase()),
    )
    .slice()
    .sort((a, b) => {
      if (!!a.pinned === !!b.pinned) {
        return b.updatedAt - a.updatedAt;
      }
      return a.pinned ? -1 : 1;
    });

  if (!activeSession) return null;

  return (
    <>
      <button
        className={classes.launcher}
        onClick={() => setIsOpen((open) => !open)}
        title="Open AI chat"
      >
        AI
      </button>
      {isOpen && (
        <div className={classes.overlay} onClick={() => setIsOpen(false)}>
          <section
            className={classes.panel}
            aria-label="AI Chatbot"
            onClick={(e) => e.stopPropagation()}
          >
            <div className={classes.chat}>
              <header className={classes.header}>
                <div className={classes.headerTitle}>AI Chatbot</div>
                <div className={classes.headerActions}>
                  {isTyping && (
                    <button className={classes.btn} onClick={stopGenerating}>
                      Dừng
                    </button>
                  )}
                  <button
                    className={classes.btn}
                    onClick={() => setCodeWrap((value) => !value)}
                  >
                    {codeWrap ? "Không ngắt dòng code" : "Ngắt dòng code"}
                  </button>
                  <button
                    className={classes.btn}
                    onClick={() => setIsOpen(false)}
                  >
                    Đóng
                  </button>
                </div>
              </header>
              <div
                className={clsx(classes.messages, codeWrap && classes.wrapCode)}
                ref={messagesRef}
              >
                {!hasUserMessages && (
                  <div className={classes.quickPromptList}>
                    {QUICK_PROMPTS.map((prompt) => (
                      <button
                        key={prompt}
                        className={classes.quickPrompt}
                        disabled={!loggedIn}
                        onClick={() => {
                          setInput(prompt);
                          void askAI(prompt);
                        }}
                      >
                        {prompt}
                      </button>
                    ))}
                  </div>
                )}
                {activeSession.messages.map((message) => {
                  const isPendingHitlMessage =
                    message.id === interruptMessageId &&
                    !!pendingInterrupt &&
                    !isTyping;
                  const showSqlRunnerActions =
                    message.parsed.sqlBlocks.length > 0 &&
                    !isPendingHitlMessage;
                  const showSqlCopyOnlyDuringHitl =
                    isPendingHitlMessage &&
                    pendingInterrupt?.action === "sql_approval" &&
                    message.parsed.sqlBlocks.length > 0;
                  const showRegenerateIcon =
                    message.role === "assistant" &&
                    !pendingInterrupt &&
                    !!lastPrompt;

                  return (
                    <article
                      key={message.id}
                      className={clsx(
                        classes.bubble,
                        message.role === "user"
                          ? classes.userBubble
                          : classes.assistantBubble,
                      )}
                    >
                      <div className={classes.bubbleMeta}>
                        <span className={classes.avatar}>
                          {message.role === "user" ? "U" : "AI"}
                        </span>
                        <span>
                          {message.role === "user" ? "Bạn" : "Trợ lý"}
                        </span>
                        <span>
                          {new Date(message.createdAt).toLocaleTimeString()}
                        </span>
                      </div>
                      <div
                        dangerouslySetInnerHTML={{
                          __html: message.parsed.html,
                        }}
                      />
                      {showSqlRunnerActions &&
                        message.parsed.sqlBlocks.map((sql) => (
                          <div key={`${message.id}-${sql.slice(0, 16)}`}>
                            <button
                              className={clsx(classes.btn, classes.primaryBtn)}
                              type="button"
                              onClick={() => openSqlInRunner(sql)}
                            >
                              Mở trong SQL Runner
                            </button>
                            <button
                              className={classes.btn}
                              type="button"
                              onClick={() => void copySqlText(sql)}
                            >
                              Sao chép SQL
                            </button>
                          </div>
                        ))}
                      {showSqlCopyOnlyDuringHitl &&
                        message.parsed.sqlBlocks.map((sql) => (
                          <div key={`${message.id}-hitl-${sql.slice(0, 16)}`}>
                            <button
                              className={classes.btn}
                              type="button"
                              onClick={() => void copySqlText(sql)}
                            >
                              Sao chép SQL
                            </button>
                          </div>
                        ))}
                      {message.parsed.tableRows.length > 0 && (
                        <table className={classes.table}>
                          <thead>
                            <tr>
                              {Object.keys(message.parsed.tableRows[0]).map(
                                (key) => (
                                  <th key={key}>{key}</th>
                                ),
                              )}
                            </tr>
                          </thead>
                          <tbody>
                            {message.parsed.tableRows
                              .slice(0, 6)
                              .map((row, index) => (
                                <tr key={`${message.id}-row-${index}`}>
                                  {Object.keys(message.parsed.tableRows[0]).map(
                                    (key) => (
                                      <td key={`${message.id}-${index}-${key}`}>
                                        {String(row[key] ?? "")}
                                      </td>
                                    ),
                                  )}
                                </tr>
                              ))}
                          </tbody>
                        </table>
                      )}
                      {isPendingHitlMessage && pendingInterrupt && (
                        <div className={classes.hitlActions}>
                          {pendingInterrupt.action === "sql_approval" && (
                            <textarea
                              className={classes.input}
                              value={sqlEditValue}
                              rows={3}
                              onChange={(e) => setSqlEditValue(e.target.value)}
                              placeholder="Chỉnh sửa SQL nếu cần..."
                            />
                          )}
                          <div className={classes.hitlButtons}>
                            <button
                              type="button"
                              className={clsx(classes.btn, classes.primaryBtn)}
                              onClick={() => void handleHitlAction("approve")}
                              disabled={!loggedIn}
                            >
                              Phê duyệt
                            </button>
                            {pendingInterrupt.action === "sql_approval" && (
                              <button
                                type="button"
                                className={classes.btn}
                                onClick={() => void handleHitlAction("edit")}
                                disabled={!loggedIn || !sqlEditValue.trim()}
                              >
                                Sửa &amp; Chạy
                              </button>
                            )}
                            <button
                              type="button"
                              className={classes.btn}
                              onClick={() => void handleHitlAction("reject")}
                              disabled={!loggedIn}
                            >
                              Từ chối
                            </button>
                          </div>
                        </div>
                      )}
                      <MessageActionBar
                        message={message}
                        onCopy={() => void copyMessage(message)}
                        showRegenerate={showRegenerateIcon}
                        onRegenerate={() => void askAI(lastPrompt)}
                        regenerateDisabled={isTyping || !lastPrompt}
                        onFeedback={
                          message.role === "assistant"
                            ? (feedback) =>
                                setMessageFeedback(
                                  activeSession.id,
                                  message.id,
                                  feedback,
                                )
                            : undefined
                        }
                        onEditPrompt={
                          message.role === "user"
                            ? () => setInput(message.raw)
                            : undefined
                        }
                      />
                    </article>
                  );
                })}
                {isTyping && (
                  <div
                    className={clsx(classes.bubble, classes.assistantBubble)}
                  >
                    <div className={classes.typingLabel}>
                      Đang xử lý
                      <span className={classes.typingCursor}>|</span>
                    </div>
                    <div className={classes.typing}>
                      <span />
                      <span />
                      <span />
                    </div>
                    <div className={classes.skeleton}>
                      <span />
                      <span />
                      <span />
                    </div>
                  </div>
                )}
              </div>

              {!loggedIn && (
                <div className={classes.errorText}>
                  Đăng nhập Dremio để gửi câu hỏi tới AI.
                </div>
              )}
              {error && <div className={classes.errorText}>{error}</div>}
              {toast && <div className={classes.toast}>{toast}</div>}

              {/* Normal input bar (hidden when HITL is pending) */}
              {!pendingInterrupt && (
                <footer className={classes.inputBar}>
                  <div className={classes.inputColumn}>
                    <textarea
                      ref={inputRef}
                      className={classes.input}
                      value={input}
                      rows={3}
                      placeholder="Nhập câu hỏi..."
                      onChange={(e) => setInput(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === "Enter" && !e.shiftKey) {
                          e.preventDefault();
                          void askAI();
                        }
                      }}
                    />
                    <div className={classes.inputMeta}>
                      <span
                        className={clsx(
                          input.length >= SOFT_PROMPT_LIMIT && classes.warnText,
                        )}
                      >
                        {input.length}/{MAX_PROMPT_LENGTH}
                      </span>
                      {input.length >= SOFT_PROMPT_LIMIT && (
                        <span className={classes.warnText}>
                          Prompt dài, có thể tăng độ trễ.
                        </span>
                      )}
                    </div>
                  </div>
                  <button
                    type="button"
                    className={clsx(classes.btn, classes.sendIconBtn)}
                    aria-label="Gửi"
                    title="Gửi"
                    onClick={() => void askAI()}
                    disabled={isTyping || !input.trim() || !loggedIn}
                  >
                    <svg
                      className={classes.sendIcon}
                      viewBox="0 0 24 24"
                      aria-hidden
                      focusable="false"
                    >
                      <path
                        fill="currentColor"
                        d="M4 12l1.41 1.41L11 7.83V20h2V7.83l5.59 5.58L20 12l-8-8-8 8z"
                      />
                    </svg>
                  </button>
                </footer>
              )}
            </div>
            <aside className={classes.history}>
              <div className={classes.historyHeader}>
                <strong>Lịch sử</strong>
                <button className={classes.btn} onClick={onCreateSession}>
                  Mới
                </button>
              </div>
              <input
                className={classes.searchInput}
                value={historyFilter}
                placeholder="Tìm phiên..."
                onChange={(e) => setHistoryFilter(e.target.value)}
              />
              {filteredSessions.map((session) => (
                <button
                  key={session.id}
                  className={clsx(classes.btn, classes.historyItem, {
                    [classes.historyItemActive]: session.id === activeSessionId,
                  })}
                  onClick={() => {
                    setActiveSessionId(session.id);
                    setPendingInterrupt(null);
                    setPendingThreadId(null);
                    setInterruptMessageId(null);
                  }}
                >
                  <div className={classes.historyItemTitle}>
                    {session.pinned ? "[Pinned] " : ""}
                    {session.title}
                  </div>
                  <div className={classes.historyItemTime}>
                    {new Date(session.updatedAt).toLocaleString()}
                  </div>
                  <div className={classes.historyActions}>
                    <span
                      className={classes.linkBtn}
                      role="button"
                      tabIndex={0}
                      onClick={(event) => {
                        event.preventDefault();
                        event.stopPropagation();
                        updateSession(session.id, (item) => ({
                          ...item,
                          pinned: !item.pinned,
                          updatedAt: Date.now(),
                        }));
                      }}
                      onKeyDown={(event) => {
                        if (event.key !== "Enter") return;
                        event.preventDefault();
                        event.stopPropagation();
                        updateSession(session.id, (item) => ({
                          ...item,
                          pinned: !item.pinned,
                          updatedAt: Date.now(),
                        }));
                      }}
                    >
                      {session.pinned ? "Bỏ ghim" : "Ghim"}
                    </span>
                    <span
                      className={classes.linkBtn}
                      role="button"
                      tabIndex={0}
                      onClick={(event) => {
                        event.preventDefault();
                        event.stopPropagation();
                        const nextTitle = window.prompt(
                          "Nhập tên phiên",
                          session.title,
                        );
                        if (!nextTitle?.trim()) return;
                        updateSession(session.id, (item) => ({
                          ...item,
                          title: nextTitle.trim(),
                          updatedAt: Date.now(),
                        }));
                      }}
                      onKeyDown={(event) => {
                        if (event.key !== "Enter") return;
                        event.preventDefault();
                        event.stopPropagation();
                        const nextTitle = window.prompt(
                          "Nhập tên phiên",
                          session.title,
                        );
                        if (!nextTitle?.trim()) return;
                        updateSession(session.id, (item) => ({
                          ...item,
                          title: nextTitle.trim(),
                          updatedAt: Date.now(),
                        }));
                      }}
                    >
                      Đổi tên
                    </span>
                  </div>
                </button>
              ))}
            </aside>
          </section>
        </div>
      )}
    </>
  );
};
