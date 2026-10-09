import { describe, expect, it } from "vitest";
import { explainProblem } from "./problems";

describe("explainProblem", () => {
  it("hides account codes and the raw error name", () => {
    const sentence = explainProblem({
      status: 422,
      code: "ACCOUNT_NOT_FOUND",
      detail: "Unknown account(s): 6100, 1000",
    });
    expect(sentence).toBe("This company doesn't have the accounts that expense needs, so nothing was recorded.");
    expect(sentence).not.toContain("6100");
    expect(sentence).not.toContain("ACCOUNT_NOT_FOUND");
  });

  it("says how long to wait when questions are coming too fast", () => {
    expect(explainProblem({ status: 429, code: "RATE_LIMITED", retryAfterSeconds: 45, detail: "Too many requests; retry after 45s" })).toBe(
      "You've asked several questions in a short time. Wait 45 seconds, then ask again.",
    );
    expect(explainProblem({ status: 429, code: "RATE_LIMITED" })).not.toContain("RATE_LIMITED");
  });

  it("uses a plain sentence for an unknown failure", () => {
    expect(explainProblem({ status: 500, detail: "column query_embedding does not exist" })).toBe(
      "Something went wrong. Nothing was changed.",
    );
  });
});
