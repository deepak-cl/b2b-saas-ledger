export type Problem = {
  status: number;
  code?: string;
  detail?: string;
  title?: string;
  retryAfterSeconds?: number;
};

const PLAIN: Record<string, string> = {
  VALIDATION_FAILED: "That didn't look right. Check the amount and try again.",
  MALFORMED_REQUEST: "That didn't look right. Check the amount and try again.",
  TENANT_HEADER_MISSING: "Choose a company before continuing.",
  TENANT_HEADER_INVALID: "Choose a company before continuing.",
  IDEMPOTENCY_KEY_MISSING: "The expense could not be recorded. Try again.",
  IDEMPOTENCY_KEY_INVALID: "The expense could not be recorded. Try again.",
  UNAUTHENTICATED: "Your sign-in has expired. Sign in again.",
  TENANT_ACCESS_DENIED: "You don't have access to this company.",
  PERMISSION_DENIED: "Your role can look at these books, not change them.",
  TENANT_NOT_FOUND: "That company could not be found.",
  TRANSACTION_NOT_FOUND: "That entry could not be found.",
  RESOURCE_NOT_FOUND: "That could not be found.",
  IDEMPOTENCY_KEY_REUSED: "This expense was already sent with different details.",
  ACCOUNT_CODE_EXISTS: "That account already exists.",
  TENANT_EXISTS: "That company already exists.",
  JOURNAL_SEALED: "This entry is already final. It can't be changed.",
  LEDGER_IMMUTABLE: "Posted entries can't be changed.",
  PERIOD_CLOSED: "That date is in a closed period, so nothing was recorded.",
  LEDGER_UNBALANCED: "The two sides of this entry don't match, so nothing was recorded.",
  INSUFFICIENT_LINES: "An entry needs two sides, so nothing was recorded.",
  ACCOUNT_NOT_FOUND: "This company doesn't have the accounts that expense needs, so nothing was recorded.",
  ACCOUNT_INACTIVE: "One of those accounts is closed, so nothing was recorded.",
  CURRENCY_MISMATCH: "Those accounts don't use the same currency, so nothing was recorded.",
  INSUFFICIENT_FUNDS: "There isn't enough cash for that expense.",
  PERIOD_NOT_OPEN: "That date isn't in an open period, so nothing was recorded.",
  TENANT_UNAVAILABLE: "This company is paused, so nothing was recorded.",
  RATE_LIMITED: "You've asked several questions in a short time. Wait a minute, then ask again.",
  AI_BUDGET_EXCEEDED: "This company has used its questions for the month.",
  AI_PROVIDER_UNAVAILABLE: "The auditor is unavailable right now. Try again in a moment.",
  TENANT_BUSY: "These books are busy. Try again in a moment.",
  LEDGER_CONTENTION: "These books are busy. Try again in a moment.",
  TENANT_PROVISIONING_FAILED: "This company isn't ready yet.",
  INTERNAL_ERROR: "Something went wrong. Nothing was changed.",
};

/** A sentence for a person. Never the error code or the raw server text. */
export function explainProblem(problem: Problem): string {
  if (problem.code === "RATE_LIMITED") return rateLimit(problem.retryAfterSeconds);
  if (problem.code && PLAIN[problem.code]) return PLAIN[problem.code];
  if (problem.status === 401) return PLAIN.UNAUTHENTICATED;
  if (problem.status === 403) return PLAIN.PERMISSION_DENIED;
  if (problem.status === 404) return PLAIN.RESOURCE_NOT_FOUND;
  return "Something went wrong. Nothing was changed.";
}

function rateLimit(retryAfterSeconds: number | undefined): string {
  if (retryAfterSeconds != null && retryAfterSeconds > 0 && retryAfterSeconds < 60) {
    const seconds = Math.ceil(retryAfterSeconds);
    return `You've asked several questions in a short time. Wait ${seconds} seconds, then ask again.`;
  }
  return PLAIN.RATE_LIMITED;
}
