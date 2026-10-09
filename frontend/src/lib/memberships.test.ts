import { describe, expect, it } from "vitest";
import { canAsk, canPost, companyName, parseMemberships, roleLabel } from "./memberships";

describe("parseMemberships", () => {
  it("keeps the stronger role when a slug is listed twice", () => {
    expect(
      parseMemberships(["/tenants/acme/VIEWER", "/tenants/acme/ACCOUNTANT", "/tenants/globex/VIEWER"]),
    ).toEqual([
      { slug: "acme", role: "ACCOUNTANT" },
      { slug: "globex", role: "VIEWER" },
    ]);
  });

  it("ignores malformed groups and a single string claim", () => {
    expect(parseMemberships("/tenants/acme/OWNER")).toEqual([{ slug: "acme", role: "OWNER" }]);
    expect(parseMemberships(["nope", "/tenants/x/OWNER"])).toEqual([]);
  });
});

describe("labels", () => {
  it("turns slugs and roles into words a person can read", () => {
    expect(companyName("acme")).toBe("Acme");
    expect(companyName("e2e_shared")).toBe("Shared demo");
    expect(roleLabel("ACCOUNTANT")).toBe("Accountant");
  });
});

describe("permissions", () => {
  it("lets accountants post and ask, and viewers only read", () => {
    expect(canPost("ACCOUNTANT")).toBe(true);
    expect(canPost("VIEWER")).toBe(false);
    expect(canAsk("AUDITOR")).toBe(true);
    expect(canAsk("VIEWER")).toBe(false);
  });
});
