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
import { chatService, hasAuthToken } from "./chatService";

describe("AIChatbot chatService", () => {
  beforeEach(() => {
    localStorage.removeItem("aichatbot-plugin-sessions");
    localStorage.removeItem("aichatbot-plugin-sql-draft");
    sinon.stub(localStorageUtils, "getAuthToken").returns("_dremio-secrettok");
    sinon.stub(localStorageUtils, "getUserData").returns({});
  });

  afterEach(() => {
    if (localStorageUtils.getAuthToken?.restore) {
      localStorageUtils.getAuthToken.restore();
    }
    if (localStorageUtils.getUserData?.restore) {
      localStorageUtils.getUserData.restore();
    }
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

  it("calls ask endpoint with prompt body and auth headers", async () => {
    sinon.stub(global, "fetch").callsFake((url) => {
      if (String(url).includes("/aichat/config")) {
        return Promise.resolve({
          ok: true,
          json: async () => ({ defaultModel: "test-model" }),
        });
      }
      return Promise.resolve({
        ok: true,
        json: async () => ({ response: "ok" }),
      });
    });

    const payload = await chatService.ask("hello");
    expect(payload.response).to.equal("ok");
    expect(global.fetch).to.have.been.called;

    const askCall = global.fetch
      .getCalls()
      .find((c) => String(c.args[0]).includes("/aichat/ask"));
    expect(askCall).to.be.ok;
    const [, options] = askCall.args;
    expect(options.headers.Authorization).to.equal("Bearer secrettok");
    const body = JSON.parse(options.body);
    expect(body).to.deep.equal({ prompt: "hello", model: "test-model" });
  });

  it("sends X-Dremio-Username when present in user data", async () => {
    localStorageUtils.getUserData.restore();
    sinon.stub(localStorageUtils, "getUserData").returns({ userName: "alice" });
    sinon.stub(global, "fetch").callsFake((url) => {
      if (String(url).includes("/aichat/config")) {
        return Promise.resolve({
          ok: true,
          json: async () => ({ defaultModel: "m" }),
        });
      }
      return Promise.resolve({
        ok: true,
        json: async () => ({ answer: "ok" }),
      });
    });

    await chatService.ask("q");
    const askCall = global.fetch
      .getCalls()
      .find((c) => String(c.args[0]).includes("/aichat/ask"));
    const [, options] = askCall.args;
    expect(options.headers["X-Dremio-Username"]).to.equal("alice");
  });

  it("hasAuthToken is false without token", () => {
    localStorageUtils.getAuthToken.restore();
    sinon.stub(localStorageUtils, "getAuthToken").returns(null);
    expect(hasAuthToken()).to.equal(false);
  });

  it("ask throws MISSING_AUTH when there is no token", async () => {
    localStorageUtils.getAuthToken.restore();
    sinon.stub(localStorageUtils, "getAuthToken").returns(null);

    let caught;
    try {
      await chatService.ask("hello");
    } catch (e) {
      caught = e;
    }
    expect(caught).to.be.instanceOf(Error);
    expect(caught.message).to.equal("MISSING_AUTH");
  });
});
