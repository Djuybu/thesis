import { createSession, deriveSessionTitle, parseMessageContent } from "./parser";

describe("AIChatbot parser", () => {
  it("parses markdown and SQL code blocks", () => {
    const parsed = parseMessageContent("Hello\n```sql\nselect * from tbl;\n```");
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
