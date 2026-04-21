import localStorageUtils from "@inject/utils/storageUtils/localStorageUtils";
import { chatService } from "./chatService";

describe("AIChatbot chatService", () => {
  beforeEach(() => {
    localStorage.clear();
    sinon.stub(localStorageUtils, "getAuthToken").returns("_dremio-token");
  });

  afterEach(() => {
    localStorageUtils.getAuthToken.restore();
    if (global.fetch?.restore) {
      global.fetch.restore();
    }
  });

  it("saves and loads sessions from localStorage", () => {
    const sessions = [{ id: "s1", messages: [] }];
    chatService.saveSessions(sessions);
    expect(chatService.loadSessions()).to.deep.equal(sessions);
  });

  it("stores SQL draft", () => {
    chatService.storeSqlDraft("select 1");
    expect(localStorage.getItem("aichatbot-plugin-sql-draft")).to.equal(
      "select 1",
    );
  });

  it("calls ask endpoint with auth headers", async () => {
    sinon.stub(global, "fetch").resolves({
      ok: true,
      json: async () => ({ response: "ok" }),
    });

    const payload = await chatService.ask("hello", "session-1");
    expect(payload.response).to.equal("ok");
    expect(global.fetch).to.have.been.called;

    const [, options] = global.fetch.getCall(0).args;
    expect(options.headers.Authorization).to.equal("_dremio-token");
  });
});
