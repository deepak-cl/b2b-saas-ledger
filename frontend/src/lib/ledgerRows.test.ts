import { describe, expect, it } from "vitest";
import { generateLedger, money } from "./ledgerRows";

describe("generateLedger", () => {
  it("builds ten thousand stable rows", () => {
    const rows = generateLedger(10_000);
    expect(rows).toHaveLength(10_000);
    expect(rows[0]).toEqual(generateLedger(1)[0]);
    expect(rows[9999].id).toBe("sample-9999");
    expect(rows.every((row) => row.debit !== "" || row.credit !== "")).toBe(true);
  });
});

describe("money", () => {
  it("prints two decimal places", () => {
    expect(money(1250)).toBe("1,250.00");
    expect(money("")).toBe("");
  });
});
