import { describe, expect, it } from "vitest";
import type { Account } from "../api/ledger";
import { expenseSides } from "./expense";

function account(code: string, type: string, name = code): Account {
  return { code, name, type, currency: "USD", balance: 0, active: true };
}

describe("expenseSides", () => {
  it("uses cloud infrastructure and operating cash when they exist", () => {
    const sides = expenseSides([account("4000", "REVENUE"), account("6100", "EXPENSE", "Cloud Infrastructure"), account("1000", "ASSET", "Operating Cash")]);
    expect(sides?.expense.code).toBe("6100");
    expect(sides?.cash.code).toBe("1000");
  });

  it("refuses a company that has no cash account", () => {
    expect(expenseSides([account("9998", "EXPENSE"), account("9999", "LIABILITY")])).toBeNull();
  });
});
