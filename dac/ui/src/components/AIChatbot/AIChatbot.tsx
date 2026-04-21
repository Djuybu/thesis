import clsx from "clsx";
import { useEffect, useMemo, useRef, useState } from "react";
import { chatService } from "./chatService";
import { createSession, deriveSessionTitle, parseMessageContent, uid } from "./parser";
import type { AskResponse, ChatMessage, ChatSession, DataRow } from "./types";
import * as classes from "./AIChatbot.module.less";

const SQL_DRAFT_KEY = "aichatbot-plugin-sql-draft";

const pickAnswerText = (payload: AskResponse) => {
  const candidates = [payload.response, payload.answer, payload.content];
  for (const value of candidates) {
    if (typeof value === "string" && value.trim().length > 0) {
      return value;
    }
  }
  return "AI khong tra ve noi dung.";
};

export const AIChatbot = () => {
  const [isOpen, setIsOpen] = useState(false);
  const [input, setInput] = useState("");
  const [isTyping, setIsTyping] = useState(false);
  const [error, setError] = useState("");
  const [sessions, setSessions] = useState<ChatSession[]>(() => {
    const existing = chatService.loadSessions();
    return existing.length ? existing : [createSession()];
  });
  const [activeSessionId, setActiveSessionId] = useState<string>(() => {
    const existing = chatService.loadSessions();
    return existing[0]?.id || createSession().id;
  });
  const messagesRef = useRef<HTMLDivElement | null>(null);

  const activeSession = useMemo(
    () => sessions.find((session) => session.id === activeSessionId) || sessions[0],
    [activeSessionId, sessions],
  );

  useEffect(() => {
    if (messagesRef.current) {
      messagesRef.current.scrollTop = messagesRef.current.scrollHeight;
    }
  }, [activeSession?.messages.length, isTyping]);

  const persistSessions = (nextSessions: ChatSession[]) => {
    setSessions(nextSessions);
    chatService.saveSessions(nextSessions);
  };

  const updateSession = (sessionId: string, updater: (session: ChatSession) => ChatSession) => {
    const nextSessions = sessions.map((session) =>
      session.id === sessionId ? updater(session) : session,
    );
    persistSessions(nextSessions);
  };

  const appendMessage = (sessionId: string, message: ChatMessage) => {
    updateSession(sessionId, (session) => ({
      ...session,
      messages: [...session.messages, message],
      updatedAt: Date.now(),
      title: deriveSessionTitle(session, message),
    }));
  };

  const onCreateSession = () => {
    const session = createSession();
    const nextSessions = [session, ...sessions];
    persistSessions(nextSessions);
    setActiveSessionId(session.id);
    setIsOpen(true);
  };

  const runSql = async (sql: string) => {
    try {
      await navigator.clipboard.writeText(sql);
    } catch {
      // Clipboard can fail on insecure or restricted contexts.
    }
    chatService.storeSqlDraft(sql);
    localStorage.setItem(SQL_DRAFT_KEY, sql);
    window.location.assign("/new_query");
  };

  const askAI = async () => {
    if (isTyping || !activeSession) return;

    const value = input.trim();
    if (!value) return;

    setError("");
    const userMessage: ChatMessage = {
      id: uid(),
      role: "user",
      raw: value,
      parsed: parseMessageContent(value),
      createdAt: Date.now(),
    };

    appendMessage(activeSession.id, userMessage);
    setInput("");
    setIsTyping(true);

    try {
      const payload = await chatService.ask(value, activeSession.id);
      const raw = pickAnswerText(payload);
      const dataRows: DataRow[] = Array.isArray(payload.data) ? payload.data : [];
      const aiMessage: ChatMessage = {
        id: uid(),
        role: "assistant",
        raw,
        parsed: parseMessageContent(raw, dataRows),
        createdAt: Date.now(),
      };
      appendMessage(activeSession.id, aiMessage);
    } catch (e) {
      const msg = e instanceof Error ? e.message : "Khong ro loi";
      setError(`Loi khi goi AI backend: ${msg}`);
      appendMessage(activeSession.id, {
        id: uid(),
        role: "assistant",
        raw: `Loi khi goi AI backend: ${msg}`,
        parsed: parseMessageContent(`Loi khi goi AI backend: ${msg}`),
        createdAt: Date.now(),
      });
    } finally {
      setIsTyping(false);
    }
  };

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
        <section className={classes.panel} aria-label="AI Chatbot">
          <div className={classes.chat}>
            <header className={classes.header}>
              <span>AI Chatbot</span>
              <button className={classes.btn} onClick={() => setIsOpen(false)}>
                Dong
              </button>
            </header>
            <div className={classes.messages} ref={messagesRef}>
              {activeSession.messages.map((message) => (
                <article
                  key={message.id}
                  className={clsx(
                    classes.bubble,
                    message.role === "user" ? classes.userBubble : classes.assistantBubble,
                  )}
                >
                  <div dangerouslySetInnerHTML={{ __html: message.parsed.html }} />
                  {message.parsed.sqlBlocks.map((sql) => (
                    <div key={`${message.id}-${sql.slice(0, 16)}`}>
                      <button className={classes.btn} onClick={() => runSql(sql)}>
                        Run this SQL
                      </button>
                    </div>
                  ))}
                  {message.parsed.tableRows.length > 0 && (
                    <table className={classes.table}>
                      <thead>
                        <tr>
                          {Object.keys(message.parsed.tableRows[0]).map((key) => (
                            <th key={key}>{key}</th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {message.parsed.tableRows.slice(0, 6).map((row, index) => (
                          <tr key={`${message.id}-row-${index}`}>
                            {Object.keys(message.parsed.tableRows[0]).map((key) => (
                              <td key={`${message.id}-${index}-${key}`}>
                                {String(row[key] ?? "")}
                              </td>
                            ))}
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </article>
              ))}
              {isTyping && (
                <div className={clsx(classes.bubble, classes.assistantBubble)}>
                  <div>AI is typing...</div>
                  <div className={classes.typing}>
                    <span />
                    <span />
                    <span />
                  </div>
                </div>
              )}
            </div>
            {error && <div className={classes.errorText}>{error}</div>}
            <footer className={classes.inputBar}>
              <textarea
                className={classes.input}
                value={input}
                rows={3}
                placeholder="Nhap cau hoi..."
                onChange={(e) => setInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter" && !e.shiftKey) {
                    e.preventDefault();
                    void askAI();
                  }
                }}
              />
              <button className={clsx(classes.btn, classes.primaryBtn)} onClick={() => void askAI()}>
                Send
              </button>
            </footer>
          </div>
          <aside className={classes.history}>
            <div className={classes.historyHeader}>
              <strong>History</strong>
              <button className={classes.btn} onClick={onCreateSession}>
                New
              </button>
            </div>
            {sessions
              .slice()
              .sort((a, b) => b.updatedAt - a.updatedAt)
              .map((session) => (
                <button
                  key={session.id}
                  className={clsx(classes.btn, classes.historyItem, {
                    [classes.historyItemActive]: session.id === activeSessionId,
                  })}
                  onClick={() => setActiveSessionId(session.id)}
                >
                  <div style={{ fontWeight: 600 }}>{session.title}</div>
                  <div style={{ fontSize: 11, opacity: 0.7 }}>
                    {new Date(session.updatedAt).toLocaleString()}
                  </div>
                </button>
              ))}
          </aside>
        </section>
      )}
    </>
  );
};
