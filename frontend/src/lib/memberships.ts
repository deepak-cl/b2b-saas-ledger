/** Keycloak puts `/tenants/<slug>/<ROLE>` in the `tenants` claim. */
const GROUP = /^\/tenants\/([a-z][a-z0-9_]{2,47})\/([A-Z]+)$/;

export type Membership = {
  slug: string;
  role: string;
};

const RANK = ["OWNER", "ADMIN", "ACCOUNTANT", "AUDITOR", "VIEWER"];

export function parseMemberships(claim: unknown): Membership[] {
  const groups = Array.isArray(claim) ? claim : typeof claim === "string" ? [claim] : [];
  const bySlug = new Map<string, string>();
  for (const group of groups) {
    if (typeof group !== "string") continue;
    const match = GROUP.exec(group);
    if (!match) continue;
    const [, slug, role] = match;
    if (!RANK.includes(role)) continue;
    const current = bySlug.get(slug);
    if (!current || RANK.indexOf(role) < RANK.indexOf(current)) {
      bySlug.set(slug, role);
    }
  }
  return [...bySlug.entries()].map(([slug, role]) => ({ slug, role }));
}

export function canPost(role: string): boolean {
  return role === "OWNER" || role === "ADMIN" || role === "ACCOUNTANT";
}

export function canAsk(role: string): boolean {
  return role !== "VIEWER";
}

const COMPANY_NAMES: Record<string, string> = {
  acme: "Acme",
  globex: "Globex",
  initech: "Initech",
  e2e_shared: "Shared demo",
  e2e_isolated: "Isolated demo",
};

export function companyName(slug: string): string {
  return COMPANY_NAMES[slug] ?? slug.replace(/_/g, " ").replace(/\b[a-z]/g, (letter) => letter.toUpperCase());
}

const ROLE_LABELS: Record<string, string> = {
  OWNER: "Owner",
  ADMIN: "Admin",
  ACCOUNTANT: "Accountant",
  AUDITOR: "Auditor",
  VIEWER: "Viewer",
};

export function roleLabel(role: string): string {
  return ROLE_LABELS[role] ?? role;
}

export function roleNote(role: string): string {
  if (role === "VIEWER") return "You can read these books. Recording an expense and asking questions are turned off.";
  if (role === "AUDITOR") return "You can read these books and ask questions. Recording an expense is turned off.";
  return "You can record expenses and ask questions about these books.";
}

export function readJwtPayload(token: string): Record<string, unknown> {
  const part = token.split(".")[1];
  if (!part) throw new Error("Token has no payload");
  const json = atob(part.replace(/-/g, "+").replace(/_/g, "/"));
  return JSON.parse(json) as Record<string, unknown>;
}
