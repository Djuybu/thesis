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

describe("AIChatbot chatService (v1 API)", () => {
  beforeEach(() => {
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

  it("saves and loads sessions from Dremio history API", async () => {
    const sessions = [{ id: "s1", messages: [] }];
    sinon.stub(global, "fetch").callsFake((url, options) => {
      if (String(url).includes("/api/v3/aichat-history/sessions")) {
        if (options?.method === "PUT") {
          return Promise.resolve({
            ok: true,
            json: async () => sessions,
          });
        }
        return Promise.resolve({
          ok: true,
          json: async () => sessions,
        });
      }
      return Promise.reject(new Error(`Unexpected URL: ${url}`));
    });

    chatService.saveSessions(sessions);
    const loaded = await chatService.loadSessionsFromServer();

    expect(loaded).to.deep.equal(sessions);
    const saveCall = global.fetch
      .getCalls()
      .find((c) => c.args[1]?.method === "PUT");
    expect(saveCall).to.be.ok;
    expect(saveCall.args[1].headers.Authorization).to.equal("Bearer secrettok");
    expect(JSON.parse(saveCall.args[1].body)).to.deep.equal(sessions);
  });

  it("stores SQL draft", () => {
    chatService.storeSqlDraft("select 1");
    expect(localStorage.getItem("aichatbot-plugin-sql-draft")).to.equal(
      "select 1",
    );
  });

  it("calls startChat endpoint with correct body and auth headers", async () => {
    sinon.stub(global, "fetch").callsFake(() => {
      return Promise.resolve({
        ok: true,
        json: async () => ({
          status: "completed",
          thread_id: "t1",
          model: "test",
          answer: "ok",
        }),
      });
    });

    const payload = await chatService.startChat("hello");
    expect(payload.status).to.equal("completed");
    expect(payload.answer).to.equal("ok");
    expect(global.fetch).to.have.been.called;

    const chatCall = global.fetch
      .getCalls()
      .find((c) => String(c.args[0]).includes("/aichat/v1/chat"));
    expect(chatCall).to.be.ok;
    const [, options] = chatCall.args;
    expect(options.headers.Authorization).to.equal("Bearer secrettok");
    const body = JSON.parse(options.body);
    expect(body.message).to.equal("hello");
  });

  it("sends X-Dremio-Username when present in user data", async () => {
    localStorageUtils.getUserData.restore();
    sinon.stub(localStorageUtils, "getUserData").returns({ userName: "alice" });
    sinon.stub(global, "fetch").callsFake(() => {
      return Promise.resolve({
        ok: true,
        json: async () => ({
          status: "completed",
          thread_id: "t2",
          model: "m",
          answer: "ok",
        }),
      });
    });

    await chatService.startChat("q");
    const chatCall = global.fetch
      .getCalls()
      .find((c) => String(c.args[0]).includes("/aichat/v1/chat"));
    const [, options] = chatCall.args;
    expect(options.headers["X-Dremio-Username"]).to.equal("alice");
  });

  it("calls resumeChat with correct action and thread_id", async () => {
    sinon.stub(global, "fetch").callsFake(() => {
      return Promise.resolve({
        ok: true,
        json: async () => ({
          status: "completed",
          thread_id: "t3",
          model: "m",
          answer: "executed",
        }),
      });
    });

    const payload = await chatService.resumeChat("t3", "approve");
    expect(payload.status).to.equal("completed");
    const resumeCall = global.fetch
      .getCalls()
      .find((c) => String(c.args[0]).includes("/aichat/v1/chat/resume"));
    expect(resumeCall).to.be.ok;
    const body = JSON.parse(resumeCall.args[1].body);
    expect(body.thread_id).to.equal("t3");
    expect(body.action).to.equal("approve");
  });

  it("hasAuthToken is false without token", () => {
    localStorageUtils.getAuthToken.restore();
    sinon.stub(localStorageUtils, "getAuthToken").returns(null);
    expect(hasAuthToken()).to.equal(false);
  });

  it("startChat throws MISSING_AUTH when there is no token", async () => {
    localStorageUtils.getAuthToken.restore();
    sinon.stub(localStorageUtils, "getAuthToken").returns(null);

    let caught;
    try {
      await chatService.startChat("hello");
    } catch (e) {
      caught = e;
    }
    expect(caught).to.be.instanceOf(Error);
    expect(caught.message).to.equal("MISSING_AUTH");
  });
});
