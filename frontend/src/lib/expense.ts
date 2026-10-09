import type { Account } from "../api/ledger";

export type ExpenseSides = {
  expense: Account;
  cash: Account;
};

/** The two accounts a cloud expense can post to, when this company actually has them. */
export function expenseSides(accounts: Account[]): ExpenseSides | null {
  const open = accounts.filter((account) => account.active);
  const expense = open.find((account) => account.code === "6100") ?? open.find((account) => account.type === "EXPENSE");
  const cash = open.find((account) => account.code === "1000") ?? open.find((account) => account.type === "ASSET");
  if (!expense || !cash || expense.code === cash.code || expense.currency !== cash.currency) return null;
  return { expense, cash };
}
