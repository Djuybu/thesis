# Báo cáo: Giao diện Chatbot (AI Chatbot UI)

Tài liệu trình bày phần **Giao diện cho Chatbot** trong dự án `thesis` (nhánh `aichatbot-merge`), tích hợp vào Dremio Analyst Center (DAC).

---

## 1. Tổng quan

`AI Chatbot` là một widget hội thoại được nhúng trực tiếp vào UI Dremio. Người dùng có thể:

- Hỏi đáp bằng ngôn ngữ tự nhiên về dữ liệu / SQL.
- Nhận câu trả lời ở dạng Markdown (có code block SQL, bảng dữ liệu, danh sách).
- **Phê duyệt / chỉnh sửa / từ chối SQL** trước khi thực thi (Human-in-the-loop — HITL).
- Quản lý **nhiều phiên hội thoại**, ghim, đổi tên, tìm kiếm.
- Đẩy SQL vừa tạo sang trang `/new_query` để chạy.

**Vị trí trong UI:** một nút tròn nổi (FAB — Floating Action Button) ở góc dưới phải mọi trang Dremio. Khi click sẽ mở một modal panel rộng chứa khung chat + sidebar lịch sử.

---

## 2. Cấu trúc thư mục & file

Toàn bộ phần UI Chatbot nằm trong:

```text
dac/ui/src/components/AIChatbot/
├── AIChatbot.tsx          # Component React chính
├── AIChatbot.module.less  # CSS Module (LESS) cho styling
├── chatService.ts         # Lớp gọi API (fetch wrapper) + lưu localStorage
├── parser.ts              # Parse Markdown → HTML an toàn, tách SQL block, table
├── types.ts               # TypeScript types chung
├── chatService-spec.js    # Unit tests cho chatService
└── parser-spec.js         # Unit tests cho parser
```

Cách nhúng vào ứng dụng — tại `dac/ui/src/AdditionalAppElements.tsx`:

```17:19:dac/ui/src/AdditionalAppElements.tsx
import { AIChatbot } from "#oss/components/AIChatbot/AIChatbot";

export const AdditionalAppElements = <AIChatbot />;
```

Như vậy, chatbot là một **app-level element** luôn được render ở cấp ngoài cùng của ứng dụng, độc lập với routing.

---

## 3. Công nghệ sử dụng

| Thư viện | Phiên bản (UI repo) | Vai trò |
|---------|---------------------|---------|
| **React** | 18.3.1 | Framework UI |
| **TypeScript** | – | Static typing |
| **LESS (CSS Modules)** | – | Styling cục bộ, không xung đột class |
| **marked** | 14.1.2 | Parse Markdown trả về từ AI thành HTML |
| **dompurify** | ^3.1.7 | Sanitize HTML — chống XSS |
| **clsx** | ^2 | Ghép class CSS động |
| **fetch API** | (native) | Gọi REST tới gateway `/aichat/v1/*` |

Trạng thái được quản lý hoàn toàn bằng **React Hooks** (`useState`, `useEffect`, `useMemo`, `useRef`), không dùng Redux cho phần này.

---

## 4. Mô hình dữ liệu (types.ts)

```16:43:dac/ui/src/components/AIChatbot/types.ts
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
```

- **`ChatMessage`** lưu cả nội dung gốc (`raw`) và phiên bản đã parse (`parsed`) để render lại nhanh.
- **`ChatSession`** đại diện một cuộc trò chuyện; có `threadId` đồng bộ với backend khi đang trong luồng HITL.
- **`HitlInterrupt`** mô tả tín hiệu interrupt từ backend (xác nhận metadata hoặc duyệt SQL).
- **`ChatApiResponse`** là payload chuẩn từ endpoint `/aichat/v1/chat`.

---

## 5. Bố cục giao diện

Modal được chia hai cột:

```text
┌────────────────────────────────────────────────────────┐
│ Header: tiêu đề + Model + Phiên + Latency + API status │
│ ┌────────────────────────────────────────┐ ┌─────────┐ │
│ │           Quick prompts (lần đầu)      │ │ Lịch sử │ │
│ │  ┌──────────────────────────────────┐  │ │  Mới    │ │
│ │  │   Bong bóng tin nhắn (User)      │  │ │ Tìm...  │ │
│ │  │   Bong bóng AI + actions         │  │ │ - phiên │ │
│ │  │   ...                            │  │ │ - phiên │ │
│ │  └──────────────────────────────────┘  │ │ - phiên │ │
│ │                                        │ │         │ │
│ ├────────────────────────────────────────┤ │         │ │
│ │ Input bar: textarea + đếm ký tự + Send │ │         │ │
│ │ (khi HITL: nút Phê duyệt/Sửa/Từ chối)  │ │         │ │
│ └────────────────────────────────────────┘ └─────────┘ │
└────────────────────────────────────────────────────────┘
```

### 5.1 Launcher (FAB)

```16:34:dac/ui/src/components/AIChatbot/AIChatbot.module.less
.launcher {
  position: fixed;
  right: 108px;
  bottom: 24px;
  z-index: 2200;
  width: 52px;
  height: 52px;
  border-radius: 999px;
  border: none;
  background: var(--fill--primary--brand, #43b8c9);
  color: var(--text--on--brand, #fff);
  font-weight: 700;
  cursor: pointer;
  box-shadow: 0 8px 22px rgba(0, 0, 0, 0.25);
  ...
}
```

Nút "AI" cố định ở góc dưới phải, dùng biến CSS của design-system Dremio để **tự thích nghi theo theme** (light/dark).

### 5.2 Header

Hiển thị các badge metadata thời gian thực:

- `Model: default` — model AI đang dùng
- `Phiên: <title>` — tên phiên hiện tại
- `Latency: <ms>` — độ trễ request cuối cùng
- `API: idle | loading | ok | error` — trạng thái kết nối

Cùng các nút thao tác: **Retry / Dừng / Ngắt dòng code / Đóng**.

### 5.3 Khu vực tin nhắn

- Bong bóng **user** thụt từ trái 60px, nền màu brand pha loãng.
- Bong bóng **assistant** thụt từ phải 60px, viền nhẹ.
- Mỗi bong bóng có metadata: avatar (U/AI), nhãn vai trò, timestamp.
- Hiển thị **HTML đã sanitize** từ Markdown, kèm:
  - Nút **Run this SQL** / **Copy SQL** cho mỗi SQL block.
  - **Bảng dữ liệu** (tối đa 6 hàng) khi response chứa execution result.
  - Hành động: **Copy / Regenerate / Like / Dislike / Edit prompt**.

### 5.4 Trạng thái "đang xử lý"

Khi AI đang trả lời:

- Hiển thị bong bóng có 3 chấm "typing" nhấp nháy + con trỏ nháy + 3 đường skeleton pulse.
- CSS animation `@keyframes blink` / `cursorBlink` / `skeletonPulse` tạo cảm giác mượt.

### 5.5 Quick Prompts (gợi ý sẵn)

```37:41:dac/ui/src/components/AIChatbot/AIChatbot.tsx
const QUICK_PROMPTS = [
  "Tóm tắt bảng dữ liệu và cột quan trọng.",
  "Viết câu SQL để đếm số bản ghi theo ngày.",
  "Giải thích lỗi SQL và đề xuất cách sửa.",
];
```

Chỉ hiển thị khi phiên chưa có tin nhắn user nào — giúp người dùng mới biết có thể hỏi gì.

### 5.6 Input bar

- `textarea` tự co giãn (auto-resize đến tối đa 180px chiều cao).
- Đếm ký tự `0/2000`; cảnh báo vàng khi >= 1600 ký tự.
- **Phím tắt:** `Enter` để gửi, `Shift+Enter` xuống dòng.
- Nút **Send** chính (primary button).

### 5.7 HITL Action Bar

Khi backend trả `status: "interrupted"`, input bar thường ẩn đi và thay bằng action bar:

- Với `metadata_confirmation`: hiển thị bảng/schema + 2 nút **Phê duyệt / Từ chối**.
- Với `sql_approval`: thêm `textarea` cho phép sửa SQL trực tiếp + nút **Sửa & Chạy**.

```620:657:dac/ui/src/components/AIChatbot/AIChatbot.tsx
{pendingInterrupt && !isTyping && (
  <div className={classes.inputBar}>
    {pendingInterrupt.action === "sql_approval" && (
      <textarea
        className={classes.input}
        value={sqlEditValue}
        rows={3}
        onChange={(e) => setSqlEditValue(e.target.value)}
        placeholder="Chỉnh sửa SQL nếu cần..."
      />
    )}
    <div className={classes.headerActions}>
      <button ... onClick={() => void handleHitlAction("approve")}>Phê duyệt</button>
      {pendingInterrupt.action === "sql_approval" && (
        <button ... onClick={() => void handleHitlAction("edit")}>Sửa & Chạy</button>
      )}
      <button ... onClick={() => void handleHitlAction("reject")}>Từ chối</button>
    </div>
  </div>
)}
```

### 5.8 Sidebar Lịch sử

- Nút **Mới** tạo phiên mới (`createSession`).
- Ô tìm kiếm theo tiêu đề (`historyFilter`).
- Danh sách phiên sắp xếp: **pinned trước**, sau đó theo `updatedAt` mới nhất.
- Mỗi phiên có **Ghim / Bỏ ghim** và **Đổi tên** (popup `window.prompt`).
- Phiên đang active được highlight nền màu brand.

---

## 6. Quản lý trạng thái (State)

Toàn bộ state là local trong component `AIChatbot`:

| State | Kiểu | Mục đích |
|-------|------|----------|
| `isOpen` | boolean | Mở/đóng modal |
| `input` | string | Nội dung textarea hiện tại |
| `isTyping` | boolean | AI đang trả lời? |
| `error` / `toast` | string | Hiển thị lỗi / thông báo nhanh |
| `historyFilter` | string | Lọc danh sách phiên |
| `lastPrompt` | string | Prompt vừa gửi, dùng cho nút Retry/Regenerate |
| `lastLatencyMs` | number\|null | Độ trễ request cuối |
| `connectionStatus` | `idle\|loading\|ok\|error` | Hiển thị badge API |
| `codeWrap` | boolean | Bật/tắt word-wrap cho code block |
| `pendingInterrupt` | `HitlInterrupt\|null` | Interrupt đang chờ user xử lý |
| `pendingThreadId` | string\|null | Thread ID đang HITL |
| `sqlEditValue` | string | SQL người dùng sửa trong HITL |
| `sessions` | `ChatSession[]` | Toàn bộ danh sách phiên |
| `activeSessionId` | string | ID phiên đang xem |

Có 3 refs:

- `messagesRef` — auto-scroll xuống đáy khi có tin nhắn mới.
- `inputRef` — auto-resize textarea theo nội dung.
- `requestControllerRef` — `AbortController` cho phép **Dừng generating**.

### 6.1 Persistence (lưu trữ)

`chatService` sử dụng **`localStorage`** để duy trì state qua nhiều phiên trình duyệt:

| Key | Nội dung |
|-----|----------|
| `aichatbot-plugin-sessions` | JSON array `ChatSession[]` |
| `aichatbot-plugin-sql-draft` | SQL nháp khi đẩy sang `/new_query` |

```101:119:dac/ui/src/components/AIChatbot/chatService.ts
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
```

---

## 7. Tích hợp Backend (API)

UI gọi gateway **`dremio-sql-agent`** (chạy ở `http://127.0.0.1:9292`) thông qua các route được Dremio proxy:

| Endpoint | Method | Mục đích |
|----------|--------|----------|
| `/aichat/v1/config` | GET | Lấy cấu hình runtime (model, HITL bật/tắt, …) |
| `/aichat/v1/chat` | POST | Bắt đầu / tiếp tục hội thoại |
| `/aichat/v1/chat/resume` | POST | Resume sau HITL interrupt (approve / reject / edit) |

### 7.1 Authentication

Token Dremio lấy từ `localStorageUtils.getAuthToken()`, build header:

```44:59:dac/ui/src/components/AIChatbot/chatService.ts
function authorizationHeaderForAichat(uiToken) {
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
```

Khi không có token → hiển thị "Cần đăng nhập Dremio để dùng AI Chat."

### 7.2 Luồng `startChat`

```125:165:dac/ui/src/components/AIChatbot/chatService.ts
async startChat(
  message: string,
  threadId?: string,
  signal?: AbortSignal,
): Promise<ChatApiResponse> {
  if (!hasAuthToken()) throw new Error("MISSING_AUTH");
  ...
  const response = await fetch("/aichat/v1/chat", {
    method: "POST",
    headers: getAuthHeaders(),
    signal: controller.signal,
    body: JSON.stringify({
      message,
      ...(threadId ? { thread_id: threadId } : {}),
    }),
  });
  ...
}
```

Hỗ trợ **abort** thông qua `AbortController` — nút "Dừng" trong header sẽ huỷ request đang chạy.

### 7.3 Xử lý response

Hàm `handleApiResponse` phân nhánh theo `payload.status`:

- **`interrupted`** → lưu `pendingInterrupt` + `pendingThreadId`, prefill `sqlEditValue`, render message HITL bằng `formatInterruptMessage`.
- **`completed`** → render câu trả lời + bảng dữ liệu (nếu có `execution_result`).
- **`error`** → hiển thị lỗi, set badge API = `error`.

---

## 8. Render & Bảo mật nội dung

Mọi nội dung AI trả về đều đi qua `parseMessageContent`:

```46:58:dac/ui/src/components/AIChatbot/parser.ts
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
```

Quy trình **3 bước**:

1. `marked.parse` — Markdown → HTML.
2. `DOMPurify.sanitize` — loại bỏ script / event handler / iframe nguy hiểm.
3. Trích SQL block (`` ```sql ... ``` ``) và bảng JSON nếu có.

Sau đó được nhúng vào DOM qua `dangerouslySetInnerHTML` — **an toàn vì đã được purify**.

---

## 9. Tính năng UX nổi bật

| Tính năng | Mô tả |
|-----------|-------|
| **Auto-scroll** | `useEffect` cuộn xuống đáy khi `messages.length` hoặc `isTyping` thay đổi |
| **Auto-resize textarea** | `useEffect` cập nhật `height = scrollHeight` (giới hạn 180px) |
| **Đếm ký tự + cảnh báo** | Soft limit 1600 / Hard limit 2000 |
| **Quick Prompts** | Gợi ý 3 câu hỏi mẫu cho phiên mới |
| **Abort request** | `AbortController` + nút "Dừng" |
| **Retry / Regenerate** | Gọi lại với `lastPrompt` |
| **Feedback Like/Dislike** | Toggle, lưu cùng message |
| **Edit Prompt** | Đẩy nội dung user message ngược lại textarea |
| **Run SQL** | Copy SQL → localStorage draft → chuyển sang `/new_query` |
| **Toast** | Hiện 1.8 giây rồi tự ẩn |
| **Code wrap** | Bật/tắt `white-space: pre-wrap` cho `<pre>` |
| **Pin / Rename phiên** | Phiên đã ghim luôn lên đầu |
| **Search phiên** | Lọc real-time theo `title` |
| **HITL** | Khoá input + hiện action bar Approve/Edit/Reject |
| **Responsive** | < 1100px: thu hẹp sidebar; < 860px: chuyển sang layout dọc |
| **Theme tokens** | Dùng `var(--fill--primary--brand, ...)` đồng bộ design-system |
| **Accessibility** | `aria-label="AI Chatbot"`, `focus-visible` outline rõ |

---

## 10. Vòng đời một câu hỏi (sequence)

```text
User gõ câu hỏi → Enter
  │
  ▼
askAI(): kiểm tra auth, độ dài
  │
  ├─ appendMessage(user)        ── render bong bóng user
  ├─ setIsTyping(true)          ── hiện skeleton + 3 chấm
  ├─ chatService.startChat()    ── POST /aichat/v1/chat
  │     │
  │     └─ AbortController ←─── nút "Dừng"
  │
  ▼
handleApiResponse(payload)
  │
  ├─ status="interrupted" ──► pendingInterrupt = { action, ... }
  │                          ├─ render bong bóng HITL
  │                          └─ hiện action bar Approve/Edit/Reject
  │                                  │
  │                                  ▼
  │                      handleHitlAction("approve"|"edit"|"reject")
  │                          └─ POST /aichat/v1/chat/resume
  │                                  │
  │                                  └─► handleApiResponse(...) (đệ quy)
  │
  ├─ status="completed"   ──► render answer + table data
  └─ status="error"       ──► render thông báo lỗi
  │
  ▼
setIsTyping(false), tính latency, lưu localStorage
```

---

## 11. Khả năng kiểm thử

Hai file unit test cùng cấp:

- **`parser-spec.js`** — kiểm tra parse Markdown, sanitize, tách SQL block, parse JSON table.
- **`chatService-spec.js`** — kiểm tra build header auth, abort, load/save sessions, xử lý lỗi HTTP.

Chạy test riêng cho UI (theo cấu hình Vite/Jest của repo):

```bash
cd dac/ui
pnpm test
```

---

## 12. Đánh giá

### Ưu điểm

1. **Tách lớp rõ ràng**: `chatService` (network) — `parser` (transform) — `AIChatbot.tsx` (presentation).
2. **An toàn**: `DOMPurify` + sanitize HTML + chỉ chèn HTML đã làm sạch.
3. **UX hoàn chỉnh**: tất cả tương tác cơ bản đều có (history, pin, search, copy, retry, abort, feedback, HITL).
4. **Persistence offline**: state phục hồi sau reload nhờ `localStorage`.
5. **Responsive + Theme-aware**: dùng CSS variables của Dremio.
6. **Hỗ trợ HITL** — điểm khác biệt so với chatbot AI thông thường, cho phép kiểm soát SQL trước khi chạy lên database.

### Hạn chế / hướng cải thiện

1. **State trong một component lớn (~815 dòng)** — có thể tách thành `<MessageList>`, `<InputBar>`, `<HistoryPanel>`, `<HitlBar>` để dễ test.
2. **Lưu sessions toàn bộ vào `localStorage`** — không scale khi có hàng nghìn message; có thể dùng IndexedDB.
3. **Chưa hỗ trợ streaming response** (SSE / WebSocket) — câu trả lời dài phải đợi toàn bộ mới hiện.
4. **`marked` đặt mặc định** — chưa cấu hình syntax highlighting cho `<pre>` (cần `highlight.js` hoặc `prismjs`).
5. **Rename phiên dùng `window.prompt`** — nên thay bằng modal đẹp hơn.
6. **Chưa có i18n** — chuỗi tiếng Việt đang hard-code.
7. **Không có tin nhắn nhận `feedback` gửi về backend** — chỉ lưu local.

---

## 13. Tham chiếu

- Source: `dac/ui/src/components/AIChatbot/`
- Inject point: `dac/ui/src/AdditionalAppElements.tsx`
- API spec: `tools/dremio-mcp/docs/api-spec.md`
- Build & chạy gateway: `BUILD-FULL-VI.md` mục 4
- Architecture tổng thể: `tools/dremio-mcp/docs/architecture.md`
