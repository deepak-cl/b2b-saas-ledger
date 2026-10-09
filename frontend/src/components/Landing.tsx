import { useState } from "react";
import { login } from "../api/ledger";
import { parseMemberships, readJwtPayload, type Membership } from "../lib/memberships";
import { SiteFooter, SiteHeader } from "./SiteChrome";

type Session = {
  token: string;
  username: string;
  memberships: Membership[];
  tenant: string;
};

const FEATURES = [
  {
    title: "One company at a time",
    body: "Acme and Globex can share the software. They never share a ledger. Switching company loads a different set of books.",
  },
  {
    title: "An expense that stays balanced",
    body: "Recording a cloud bill adds it to Cloud Infrastructure and takes the same amount out of cash. The two sides always match.",
  },
  {
    title: "Ask in plain language",
    body: "“Did cloud spending spike?” is answered from that company’s books only. A viewer can read, and cannot ask.",
  },
];

export function Landing({ onSession, onPreview }: { onSession: (session: Session) => void; onPreview: () => void }) {
  return (
    <div id="top" className="flex min-h-screen flex-col">
      <SiteHeader links>
        <a href="#sign-in" className="btn">
          Sign in
        </a>
      </SiteHeader>
      <main>
        <section className="mx-auto grid max-w-6xl items-center gap-12 px-5 py-16 lg:grid-cols-[1.15fr_0.85fr] lg:py-24">
          <div>
            <p className="font-mono text-[11px] tracking-[0.18em] text-[var(--accent)] uppercase">Company books</p>
            <h1 className="mt-3 max-w-xl font-serif text-5xl leading-[1.05] tracking-tight sm:text-6xl">
              Every company keeps its own books.
            </h1>
            <p className="mt-5 max-w-lg text-lg leading-8 text-[var(--muted)]">
              Sign in, choose a company, and you see only that company’s cash, expenses, and answers. Another company on the same system never appears.
            </p>
            <div className="mt-8 flex flex-wrap gap-3">
              <a href="#sign-in" className="btn">Open the books</a>
              <button type="button" className="btn secondary" onClick={onPreview}>
                Preview a long ledger
              </button>
            </div>
          </div>
          <SignInCard onSession={onSession} />
        </section>
        <section id="product" className="border-y border-[var(--line)] bg-white">
          <div className="mx-auto grid max-w-6xl gap-px bg-[var(--line)] md:grid-cols-3">
            {FEATURES.map((feature) => (
              <article key={feature.title} className="bg-white px-6 py-8">
                <h2 className="font-serif text-2xl">{feature.title}</h2>
                <p className="mt-3 text-sm leading-6 text-[var(--muted)]">{feature.body}</p>
              </article>
            ))}
          </div>
        </section>
        <section id="how" className="mx-auto max-w-6xl px-5 py-16">
          <h2 className="font-serif text-3xl">How a visit goes</h2>
          <ol className="mt-8 grid gap-6 md:grid-cols-3">
            {[
              ["Choose the company", "The list comes from your sign-in. You only see companies you belong to."],
              ["Record an expense", "Cash goes down by the same amount the expense goes up."],
              ["Ask a question", "Type it in the search box and press Ask. The answer stays with that company."],
            ].map(([title, body], index) => (
              <li key={title} className="panel p-5">
                <p className="font-mono text-[11px] tracking-[0.16em] text-[var(--accent)]">0{index + 1}</p>
                <h3 className="mt-2 font-serif text-xl">{title}</h3>
                <p className="mt-2 text-sm leading-6 text-[var(--muted)]">{body}</p>
              </li>
            ))}
          </ol>
        </section>
      </main>
      <SiteFooter />
    </div>
  );
}

function SignInCard({ onSession }: { onSession: (session: Session) => void }) {
  const [username, setUsername] = useState("alice");
  const [password, setPassword] = useState("alice");
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);

  return (
    <form
      id="sign-in"
      className="panel scroll-mt-20 p-6 shadow-[0_24px_60px_rgb(28_25_21/0.08)]"
      onSubmit={(event) => {
        event.preventDefault();
        setPending(true);
        setError(null);
        void login(username.trim(), password)
          .then(({ token, username: name }) => {
            const memberships = parseMemberships(readJwtPayload(token).tenants);
            if (memberships.length === 0) throw new Error("This user is not a member of any company.");
            onSession({ token, username: name, memberships, tenant: memberships[0].slug });
          })
          .catch((caught: unknown) => setError(caught instanceof Error ? caught.message : "Sign-in failed"))
          .finally(() => setPending(false));
      }}
    >
      <h2 className="font-serif text-2xl">Sign in</h2>
      <p className="mt-2 text-sm leading-6 text-[var(--muted)]">
        Try alice. The password is alice. She can keep Acme’s books, and she can only look at Globex.
      </p>
      <label className="mt-5 block text-sm">
        Username
        <input className="field w-full" value={username} onChange={(event) => setUsername(event.target.value)} autoComplete="username" />
      </label>
      <label className="mt-3 block text-sm">
        Password
        <input className="field w-full" type="password" value={password} onChange={(event) => setPassword(event.target.value)} autoComplete="current-password" />
      </label>
      {error ? <p className="mt-3 text-sm text-[var(--accent)]">{error}</p> : null}
      <button className="btn mt-5 w-full" type="submit" disabled={pending}>
        {pending ? "Signing in…" : "Sign in"}
      </button>
    </form>
  );
}
