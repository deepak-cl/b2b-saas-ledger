export type LedgerRow = {
  id: string;
  date: string;
  description: string;
  account: string;
  debit: string;
  credit: string;
  status: "POSTED";
};

const ACCOUNTS = [
  ["1000", "Operating Cash"],
  ["6100", "Cloud Infrastructure"],
  ["6200", "Payroll"],
  ["6300", "Software Subscriptions"],
  ["4000", "Subscription Revenue"],
  ["6400", "Marketing"],
] as const;

/** Deterministic activity used to prove the grid stays smooth past 10,000 lines. */
export function generateLedger(count: number): LedgerRow[] {
  const rows: LedgerRow[] = [];
  let state = 0xdecaf;
  const next = () => {
    state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
    return state;
  };
  for (let i = 0; i < count; i++) {
    const account = ACCOUNTS[next() % ACCOUNTS.length];
    const day = String((next() % 28) + 1).padStart(2, "0");
    const month = String((next() % 12) + 1).padStart(2, "0");
    const cents = (next() % 250000) + 100;
    const amount = (cents / 100).toFixed(2);
    const debit = account[0].startsWith("6") || account[0] === "1000";
    rows.push({
      id: `sample-${i}`,
      date: `2026-${month}-${day}`,
      description: `${account[1]} ${month}/${day}`,
      account: `${account[0]} ${account[1]}`,
      debit: debit ? amount : "",
      credit: debit ? "" : amount,
      status: "POSTED",
    });
  }
  return rows;
}

export function money(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === "") return "";
  const amount = typeof value === "number" ? value : Number(value);
  if (Number.isNaN(amount)) return String(value);
  return amount.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
