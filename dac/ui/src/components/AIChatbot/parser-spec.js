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
import {
  createSession,
  deriveSessionTitle,
  parseMessageContent,
} from "./parser";

describe("AIChatbot parser", () => {
  it("parses markdown and SQL code blocks", () => {
    const parsed = parseMessageContent(
      "Hello\n```sql\nselect * from tbl;\n```",
    );
    expect(parsed.html).to.contain("<p>Hello");
    expect(parsed.sqlBlocks).to.eql(["select * from tbl;"]);
  });

  it("parses array JSON payload as table rows", () => {
    const parsed = parseMessageContent('[{"name":"a","count":1}]');
    expect(parsed.tableRows).to.have.length(1);
    expect(parsed.tableRows[0]).to.deep.equal({ name: "a", count: 1 });
  });

  it("derives session title from first user message", () => {
    const session = createSession();
    const titled = deriveSessionTitle(session, {
      id: "1",
      role: "user",
      raw: "This is my first very useful query request",
      parsed: parseMessageContent("text"),
      createdAt: Date.now(),
    });
    expect(titled).to.equal("This is my first very useful query re");
  });
});
