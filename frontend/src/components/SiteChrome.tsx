import type { ReactNode } from "react";

export function SiteHeader({ home = "#top", links = false, children }: { home?: string; links?: boolean; children?: ReactNode }) {
  return (
    <header className="sticky top-0 z-30 flex h-14 shrink-0 items-center justify-between gap-4 border-b border-[var(--line)] bg-[rgb(247_243_236/0.92)] px-5 backdrop-blur-md">
      <a href={home} className="flex items-center gap-2 text-[var(--ink)]">
        <span className="grid h-7 w-7 place-items-center rounded-md bg-[var(--ink)] font-serif text-sm text-[var(--paper)]">L</span>
        <span className="font-serif text-lg leading-none">Ledger</span>
      </a>
      {links ? (
        <nav className="hidden items-center gap-6 text-sm text-[var(--muted)] md:flex" aria-label="Site">
          <a href="#product" className="hover:text-[var(--ink)]">Product</a>
          <a href="#how" className="hover:text-[var(--ink)]">How it works</a>
          <a href="#sign-in" className="hover:text-[var(--ink)]">Sign in</a>
        </nav>
      ) : null}
      <div className="flex items-center gap-3">{children}</div>
    </header>
  );
}

const HELP = [
  ["#help", "Help"],
  ["#contact", "Contact"],
];

const LEGAL = [
  ["#privacy", "Privacy"],
  ["#terms", "Terms"],
  ["#cookies", "Cookie settings"],
  ["#accessibility", "Accessibility"],
];

export function SiteFooter() {
  return (
    <footer className="shrink-0 bg-[var(--ink)] text-[#d9d0c4]">
      <div className="mx-auto grid max-w-6xl gap-8 px-5 py-10 sm:grid-cols-2 lg:grid-cols-3">
        <nav aria-label="Help and support">
          <p className="text-sm font-medium text-white">Help and support</p>
          <ul className="mt-3 space-y-2 text-sm">
            {HELP.map(([href, label]) => (
              <li key={href}>
                <a href={href} className="hover:text-white">{label}</a>
              </li>
            ))}
          </ul>
        </nav>
        <nav aria-label="Legal">
          <p className="text-sm font-medium text-white">Legal</p>
          <ul className="mt-3 space-y-2 text-sm">
            {LEGAL.map(([href, label]) => (
              <li key={href}>
                <a href={href} className="hover:text-white">{label}</a>
              </li>
            ))}
          </ul>
        </nav>
      </div>
      <div className="border-t border-white/10">
        <div className="mx-auto flex max-w-6xl flex-col gap-3 px-5 py-5 sm:flex-row sm:items-center sm:justify-between">
          <a href="#top" className="font-serif text-lg text-white">Ledger</a>
          <p className="text-xs text-[#a3988c]">© {new Date().getFullYear()} Ledger</p>
        </div>
      </div>
    </footer>
  );
}
