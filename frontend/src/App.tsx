import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { ApiError, balanceSheet, listAccounts, postTransaction, type Account, type BalanceSheet, type PostedTransaction } from "./api/ledger";
import { BalanceChart } from "./components/BalanceChart";
import { CommandBar } from "./components/CommandBar";
import { InfoPage, useInfoPage } from "./components/InfoPage";
import { Landing } from "./components/Landing";
import { LedgerGrid } from "./components/LedgerGrid";
import { SiteFooter, SiteHeader } from "./components/SiteChrome";
import { WorkspaceSwitcher } from "./components/WorkspaceSwitcher";
import { useAuditStream } from "./hooks/useAuditStream";
import { expenseSides, type ExpenseSides } from "./lib/expense";
import { generateLedger, money, type LedgerRow } from "./lib/ledgerRows";
import { explainProblem } from "./lib/problems";
import { canAsk, canPost, companyName, roleLabel, roleNote, type Membership } from "./lib/memberships";

type Session = {
  token: string;
  username: string;
  memberships: Membership[];
  tenant: string;
};

export function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [preview, setPreview] = useState(false);
  const [sample, setSample] = useState(false);
  const [posted, setPosted] = useState<LedgerRow[]>([]);
  const [commandOpen, setCommandOpen] = useState(false);
  const [queryText, setQueryText] = useState("");
  const infoPage = useInfoPage();
  const [formError, setFormError] = useState<string | null>(null);
  const client = useQueryClient();
  const audit = useAuditStream(session?.token ?? null, session?.tenant ?? null);

  const accounts = useQuery({
    queryKey: ["accounts", session?.tenant],
    enabled: Boolean(session),
    queryFn: () => listAccounts(session!.token, session!.tenant),
  });
  const sheet = useQuery({
    queryKey: ["balance-sheet", session?.tenant],
    enabled: Boolean(session),
    queryFn: () => balanceSheet(session!.token, session!.tenant),
  });

  const sampleRows = useMemo(() => (preview || sample ? generateLedger(10_000) : []), [preview, sample]);
  const rows = posted.length > 0 ? [...posted, ...sampleRows] : sampleRows;
  const role = session?.memberships.find((membership) => membership.slug === session.tenant)?.role ?? "VIEWER";
  const company = session ? companyName(session.tenant) : "Sample";

  function ask(text: string) {
    setQueryText(text);
    void audit.ask(text);
  }

  function signOut() {
    setSession(null);
    setPreview(false);
    setPosted([]);
    setSample(false);
    setCommandOpen(false);
  }

  if (infoPage) {
    return (
      <div id="top" className="flex min-h-screen flex-col">
        <SiteHeader home={session ? "#desk" : "#top"} links={!session}>
          {session ? (
            <>
              <span className="hidden text-sm text-[var(--muted)] sm:inline">{session.username}</span>
              <a href="#desk" className="btn secondary">Back to the books</a>
            </>
          ) : (
            <a href="#sign-in" className="btn">Sign in</a>
          )}
        </SiteHeader>
        <InfoPage slug={infoPage} />
        <SiteFooter />
      </div>
    );
  }

  if (!session && !preview) {
    return <Landing onSession={setSession} onPreview={() => setPreview(true)} />;
  }

  return (
    <div className="flex min-h-screen flex-col">
      <SiteHeader>
        <span className="hidden text-sm text-[var(--muted)] sm:inline">{session ? session.username : "Preview"}</span>
        {session ? (
          <button type="button" className="btn secondary" onClick={signOut}>
            Sign out
          </button>
        ) : (
          <button type="button" className="btn secondary" onClick={() => setPreview(false)}>
            Back
          </button>
        )}
      </SiteHeader>
    <div className="flex flex-1 flex-col lg:grid lg:grid-cols-[16.5rem_minmax(0,1fr)]">
      <aside className="flex items-center gap-3 bg-[var(--sidebar)] px-4 py-3 text-[var(--sidebar-ink)] lg:sticky lg:top-14 lg:h-[calc(100vh-3.5rem)] lg:flex-col lg:items-stretch lg:gap-8 lg:overflow-auto lg:px-5 lg:py-6">
        <div className="shrink-0">
          <p className="font-mono text-[11px] tracking-[0.22em] text-[#c4a574] uppercase">Ledger</p>
          <h1 className="mt-2 hidden font-serif text-[1.7rem] leading-tight lg:block">Company books</h1>
          <p className="mt-2 hidden text-sm leading-5 text-[#c4a574] lg:block">One set of books per company. Nothing crosses over.</p>
        </div>
        <div className="min-w-0 flex-1 lg:flex-none [&_p]:hidden lg:[&_p]:block">
          {session ? (
            <WorkspaceSwitcher
              memberships={session.memberships}
              tenant={session.tenant}
              onChange={(tenant) => {
                setPosted([]);
                setSample(false);
                setSession({ ...session, tenant });
              }}
            />
          ) : (
            <p className="text-sm leading-5 text-[#c4a574]">This preview is generated here. It is not a company's books.</p>
          )}
        </div>
        <button type="button" className="btn shrink-0" onClick={() => setCommandOpen(true)}>
          <span className="lg:hidden">Ask</span>
          <span className="hidden lg:inline">Ask about these books</span>
        </button>
        <div className="mt-auto hidden text-sm text-[#c4a574] lg:block">
          {session ? session.username : "Not signed in"}
          {session ? (
            <button
              type="button"
              className="mt-2 block text-[var(--sidebar-ink)] underline"
              onClick={signOut}
            >
              Sign out
            </button>
          ) : (
            <button type="button" className="mt-2 block underline" onClick={() => setPreview(false)}>
              Back to sign in
            </button>
          )}
        </div>
      </aside>
      <div className="min-w-0">
      <header className="flex items-end justify-between border-b border-[var(--line)] px-8 py-5">
        <div>
          <p className="font-mono text-[11px] tracking-[0.16em] text-[var(--muted)] uppercase">
            {session ? roleLabel(role) : "Preview"}
          </p>
          <h2 className="font-serif text-3xl">{company}</h2>
          <p className="mt-1 text-sm text-[var(--muted)]">
            {session ? `You are viewing only ${company}'s books.` : "Scroll a long ledger without signing in."}
          </p>
        </div>
      </header>
      <main className="flex flex-col gap-4 px-4 py-4 lg:px-8 lg:py-5">
        {session ? <Summary accounts={accounts.data ?? []} sheet={sheet.data ?? null} pending={accounts.isPending || sheet.isPending} /> : null}
        {session && canPost(role) ? (
          accounts.isPending ? (
            <section className="panel shrink-0 px-5 py-4 text-sm text-[var(--muted)]">Loading the accounts…</section>
          ) : accounts.isError ? (
            <section className="panel shrink-0 px-5 py-4 text-sm text-[var(--muted)]">
              The accounts didn't load, so an expense can't be recorded yet.
            </section>
          ) : (
          <PostForm
            sides={expenseSides(accounts.data ?? [])}
            error={formError}
            onSubmit={async (description, amount, sides) => {
              setFormError(null);
              try {
                const postedTx = await postTransaction(session.token, session.tenant, {
                  effectiveDate: new Date().toISOString().slice(0, 10),
                  description,
                  lines: [
                    { accountCode: sides.expense.code, direction: "DEBIT", amount, currency: sides.expense.currency },
                    { accountCode: sides.cash.code, direction: "CREDIT", amount, currency: sides.cash.currency },
                  ],
                });
                setPosted((current) => [...linesOf(postedTx), ...current]);
                await client.invalidateQueries({ queryKey: ["accounts", session.tenant] });
                await client.invalidateQueries({ queryKey: ["balance-sheet", session.tenant] });
              } catch (caught) {
                setFormError(caught instanceof ApiError ? explainProblem(caught.problem) : "The expense was not recorded. Nothing was changed.");
              }
            }}
          />
          )
        ) : session ? (
          <p className="panel px-5 py-4 text-sm text-[var(--muted)]">{roleNote(role)}</p>
        ) : null}
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-[1.45fr_1fr]">
          <div className="h-64">
          <BalanceChart
            sheet={sheet.data ?? null}
            status={sheet.isError ? "The balance sheet did not load." : sheet.isPending && session ? "Loading the balance sheet…" : null}
          />
          </div>
          <div className="h-64">
          <AccountList
            accounts={accounts.data ?? []}
            status={
              accounts.isError
                ? "Balances did not load."
                : accounts.isPending && session
                  ? "Loading balances…"
                  : session && accounts.data?.length === 0
                    ? "This company has no accounts yet."
                    : null
            }
          />
          </div>
        </div>
        <LedgerGrid
          rows={rows}
          label={ledgerTitle(session !== null, sample, posted.length)}
          detail={
            session
              ? "Debit and credit are the two sides of one event. They match, so the books stay balanced."
              : "Each row is one side of a made-up event, so the list can be scrolled."
          }
          note={
            sample && posted.length > 0
              ? "Lines from this visit come first. The rest is a sample, not this company's books."
              : sample || preview
                ? "This list is a sample generated in the browser. It is not a company's books."
                : null
          }
          empty={
            session && canPost(role)
              ? "Nothing recorded in this visit yet. Record an expense above and both sides of that event will show up here."
              : "Nothing recorded in this visit."
          }
          action={
            session ? (
              <button type="button" className="btn quiet" onClick={() => setSample((value) => !value)}>
                {sample ? "Hide the sample ledger" : "Preview a long ledger"}
              </button>
            ) : null
          }
        />
      </main>
      </div>
      <CommandBar
        open={commandOpen}
        onOpenChange={(next) => {
          if (!next) audit.cancel();
          setCommandOpen(next);
        }}
        query={queryText}
        onQuery={setQueryText}
        streaming={audit.streaming}
        text={audit.text}
        findings={audit.findings}
        error={audit.error}
        disabledReason={session && !canAsk(role) ? roleNote(role) : null}
        onAsk={ask}
        onCancel={audit.cancel}
      />
    </div>
      <SiteFooter />
    </div>
  );
}

function ledgerTitle(signedIn: boolean, sample: boolean, posted: number): string {
  if (!signedIn || (sample && posted === 0)) return "Sample activity";
  if (sample && posted > 0) return "This visit, then a sample";
  if (posted > 0) return "Recorded this visit";
  return "Activity";
}

function linesOf(transaction: PostedTransaction): LedgerRow[] {
  return transaction.lines.map((line) => ({
    id: `${transaction.id}-${line.lineNo}`,
    date: transaction.effectiveDate,
    description: transaction.description,
    account: line.direction === "CREDIT" ? `Paid from ${line.accountName}` : `Charged to ${line.accountName}`,
    debit: line.direction === "DEBIT" ? money(line.amount) : "",
    credit: line.direction === "CREDIT" ? money(line.amount) : "",
    status: "POSTED" as const,
  }));
}

function Summary({ accounts, sheet, pending }: { accounts: Account[]; sheet: BalanceSheet | null; pending: boolean }) {
  const cash = accounts.find((account) => account.code === "1000");
  const cloud = accounts.find((account) => account.code === "6100");
  const currency = cash?.currency ?? cloud?.currency ?? sheet?.currency ?? "";
  const balanced = sheet?.balanced.at(-1);
  return (
    <section className="grid shrink-0 grid-cols-3 gap-4">
      <Figure label="Cash on hand" value={cash ? money(cash.balance) : pending ? "…" : "—"} detail={currency || "Operating cash"} />
      <Figure label="Cloud spend" value={cloud ? money(cloud.balance) : pending ? "…" : "—"} detail={currency || "Recorded so far"} />
      <Figure
        label="Books"
        value={balanced == null ? (pending ? "…" : "—") : balanced ? "Balanced" : "Out of balance"}
        detail={
          balanced == null
            ? "Waiting for the balance sheet"
            : balanced
              ? "Assets equal liabilities plus equity"
              : "Assets do not equal liabilities plus equity"
        }
      />
    </section>
  );
}

function Figure({ label, value, detail }: { label: string; value: string; detail: string }) {
  return (
    <article className="panel px-5 py-4">
      <p className="font-mono text-[11px] tracking-[0.14em] text-[var(--muted)] uppercase">{label}</p>
      <p className="mt-1 font-serif text-2xl tracking-tight">{value}</p>
      <p className="mt-1 text-xs text-[var(--muted)]">{detail}</p>
    </article>
  );
}

function AccountList({ accounts, status }: { accounts: Account[]; status?: string | null }) {
  const [showAll, setShowAll] = useState(false);
  const active = accounts.filter((account) => Number(account.balance) !== 0);
  const visible = showAll ? accounts : active;

  return (
    <section className="panel flex h-full flex-col overflow-hidden p-4">
      <header className="flex items-baseline justify-between gap-3">
        <h2 className="font-serif text-lg">{active.length > 0 ? "Balances that moved" : "Balances"}</h2>
        {accounts.length > active.length ? (
          <button type="button" className="btn quiet" onClick={() => setShowAll((value) => !value)}>
            {showAll ? "Hide zero balances" : `Show all ${accounts.length}`}
          </button>
        ) : null}
      </header>
      {visible.length === 0 ? (
        <p className="mt-3 text-sm text-[var(--muted)]">
          {status || (accounts.length > 0 ? "Every balance is still zero." : "Balances appear after you sign in.")}
        </p>
      ) : (
        <ul className="mt-2 min-h-0 flex-1 divide-y divide-[var(--line)] overflow-auto">
          {visible.map((account) => (
            <li key={account.code} className="flex items-baseline justify-between gap-3 py-1.5 text-sm">
              <span className="truncate">{account.name}</span>
              <span className="shrink-0 font-mono text-xs">
                {money(account.balance)} {account.currency}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function PostForm({
  sides,
  error,
  onSubmit,
}: {
  sides: ExpenseSides | null;
  error: string | null;
  onSubmit: (description: string, amount: string, sides: ExpenseSides) => Promise<void>;
}) {
  const [description, setDescription] = useState("Cloud infrastructure");
  const [amount, setAmount] = useState("125.00");
  const [pending, setPending] = useState(false);
  if (!sides) {
    return (
      <section className="panel shrink-0 px-5 py-4">
        <h2 className="font-serif text-lg">Record an expense</h2>
        <p className="mt-1 max-w-2xl text-sm leading-6 text-[var(--muted)]">
          This company doesn't have both an expense account and a cash account, so there is nothing to record here.
        </p>
      </section>
    );
  }
  return (
    <form
      className="panel shrink-0 px-5 py-4"
      onSubmit={(event) => {
        event.preventDefault();
        setPending(true);
        void onSubmit(description.trim(), amount.trim(), sides).finally(() => setPending(false));
      }}
    >
      <h2 className="font-serif text-lg">Record an expense</h2>
      <p className="mt-1 max-w-2xl text-sm leading-6 text-[var(--muted)]">
        The same amount is added to {sides.expense.name} and taken out of {sides.cash.name}, so the books stay balanced.
      </p>
      <div className="mt-4 flex flex-wrap items-end gap-4">
        <label className="text-sm">
          What happened
          <input className="field w-56" value={description} onChange={(event) => setDescription(event.target.value)} required />
        </label>
        <label className="text-sm">
          Amount ({sides.cash.currency})
          <input className="field w-32 font-mono" value={amount} onChange={(event) => setAmount(event.target.value)} required />
        </label>
        <button className="btn" type="submit" disabled={pending || description.trim().length === 0}>
          {pending ? "Recording…" : "Record expense"}
        </button>
      </div>
      {error ? <p className="mt-3 text-sm leading-6 text-[var(--accent)]">{error}</p> : null}
    </form>
  );
}

