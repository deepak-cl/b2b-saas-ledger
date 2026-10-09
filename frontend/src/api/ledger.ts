import type { Membership } from "../lib/memberships";

export type Problem = {
  status: number;
  code?: string;
  detail?: string;
  title?: string;
};

export class ApiError extends Error {
  readonly problem: Problem;

  constructor(problem: Problem) {
    super(problem.detail || problem.title || `Request failed (${problem.status})`);
    this.problem = problem;
  }
}

export type Account = {
  code: string;
  name: string;
  type: string;
  currency: string;
  balance: number | string;
  active: boolean;
};

export type BalanceSheet = {
  currency: string;
  periods: string[];
  assets: { totals: Array<number | string> };
  liabilities: { totals: Array<number | string> };
  equity: { totals: Array<number | string> };
  currentEarnings: Array<number | string>;
  balanced: boolean[];
};

export type PostedLine = {
  lineNo: number;
  accountCode: string;
  accountName: string;
  direction: "DEBIT" | "CREDIT";
  amount: number | string;
  currency: string;
};

export type PostedTransaction = {
  id: string;
  effectiveDate: string;
  description: string;
  lines: PostedLine[];
};

async function request<T>(path: string, token: string, tenant: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Authorization", `Bearer ${token}`);
  headers.set("X-Tenant-ID", tenant);
  if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  const response = await fetch(path, { ...init, headers });
  if (!response.ok) {
    let problem: Problem = { status: response.status };
    try {
      problem = { ...problem, ...(await response.json()) };
    } catch {
      problem.detail = response.statusText;
    }
    throw new ApiError(problem);
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

export function listAccounts(token: string, tenant: string): Promise<Account[]> {
  return request("/api/v1/ledger/accounts", token, tenant);
}

export function balanceSheet(token: string, tenant: string): Promise<BalanceSheet> {
  return request("/api/v1/analytics/balance-sheet?periods=6", token, tenant);
}

export function postTransaction(
  token: string,
  tenant: string,
  body: { effectiveDate: string; description: string; lines: Array<Record<string, string>> },
): Promise<PostedTransaction> {
  return request("/api/v1/ledger/transaction", token, tenant, {
    method: "POST",
    headers: { "Idempotency-Key": crypto.randomUUID() },
    body: JSON.stringify(body),
  });
}

export async function login(username: string, password: string): Promise<{ token: string; username: string }> {
  const response = await fetch("/realms/ledger/protocol/openid-connect/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "password",
      client_id: "ledger-web",
      username,
      password,
    }),
  });
  if (!response.ok) {
    throw new Error("Sign-in failed. Use a demo user (password = username) with Keycloak running.");
  }
  const payload = (await response.json()) as { access_token: string };
  return { token: payload.access_token, username };
}

export type { Membership };
