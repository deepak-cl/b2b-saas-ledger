import { useEffect } from "react";
import type { Finding } from "../hooks/useAuditStream";

const EXAMPLES = ["Did cloud spending spike?", "How much cash is left?"];

export function CommandBar({
  open,
  onOpenChange,
  query,
  onQuery,
  onAsk,
  onCancel,
  streaming,
  text,
  findings,
  error,
  disabledReason,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  query: string;
  onQuery: (query: string) => void;
  onAsk: (query: string) => void;
  onCancel: () => void;
  streaming: boolean;
  text: string;
  findings: Finding[];
  error: string | null;
  disabledReason: string | null;
}) {
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        if (open) onCancel();
        onOpenChange(!open);
        return;
      }
      if (open && event.key === "Escape") {
        event.preventDefault();
        onCancel();
        onOpenChange(false);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onCancel, onOpenChange]);

  if (!open) return null;

  function close() {
    onCancel();
    onOpenChange(false);
  }

  const canAsk = !streaming && !disabledReason && query.trim().length >= 3;

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center bg-[rgb(28_25_21/0.5)] px-4 pt-[10vh]" onMouseDown={close}>
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="ask-title"
        className="w-full max-w-xl overflow-hidden rounded-2xl border border-[var(--line)] bg-[var(--paper)] shadow-2xl"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header className="flex items-start justify-between gap-4 px-5 pt-4">
          <div>
            <h2 id="ask-title" className="font-serif text-xl">Ask about these books</h2>
            <p className="mt-1 text-sm text-[var(--muted)]">The answer uses only this company.</p>
          </div>
          <button type="button" className="btn secondary" onClick={close}>
            Close
          </button>
        </header>
        <form
          className="px-5 pt-4 pb-5"
          onSubmit={(event) => {
            event.preventDefault();
            if (canAsk) onAsk(query.trim());
          }}
        >
          <label className="text-sm" htmlFor="ask-query">
            Question
          </label>
          <div className="mt-1.5 flex gap-2">
            <div className="flex min-w-0 flex-1 items-center gap-2 rounded-lg border border-[var(--line)] bg-white px-3">
              <SearchIcon />
              <input
                id="ask-query"
                value={query}
                autoFocus
                onChange={(event) => onQuery(event.target.value)}
                placeholder="Type a question about this company's books"
                className="min-w-0 flex-1 border-0 bg-transparent py-2.5 text-base outline-none"
              />
            </div>
            <button type="submit" className="btn shrink-0" disabled={!canAsk}>
              {streaming ? "Answering…" : "Ask"}
            </button>
          </div>
          <div className="mt-4 max-h-[40vh] overflow-auto">
            {disabledReason ? <p className="text-sm leading-6 text-[var(--muted)]">{disabledReason}</p> : null}
            {streaming ? <p className="text-sm text-[var(--muted)]">Looking through the books…</p> : null}
            {!disabledReason && error ? <p className="text-sm leading-6 text-[var(--accent)]">{error}</p> : null}
            {!streaming && text ? <p className="text-sm leading-6 whitespace-pre-wrap">{text}</p> : null}
            {!streaming && findings.length > 0 ? (
              <ul className="mt-3 space-y-2">
                {findings.map((finding, index) => (
                  <li key={`${finding.title}-${index}`} className="rounded-md bg-[var(--wash)] px-3 py-2 text-sm">
                    <span className="font-mono text-[11px] text-[var(--accent)]">{finding.severity}</span> {finding.title}
                  </li>
                ))}
              </ul>
            ) : null}
            {!disabledReason ? (
              <div className={text || error || streaming ? "mt-4 border-t border-[var(--line)] pt-3" : ""}>
                <p className="text-sm text-[var(--muted)]">Or start from one of these</p>
                <div className="mt-2 flex flex-wrap gap-2">
                  {EXAMPLES.map((example) => (
                    <button
                      key={example}
                      type="button"
                      className="chip"
                      disabled={streaming}
                      onClick={() => onAsk(example)}
                    >
                      {example}
                    </button>
                  ))}
                </div>
              </div>
            ) : null}
          </div>
        </form>
      </div>
    </div>
  );
}

function SearchIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true" className="shrink-0 text-[var(--muted)]">
      <circle cx="7" cy="7" r="4.25" fill="none" stroke="currentColor" strokeWidth="1.5" />
      <path d="M10.5 10.5 L13.5 13.5" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
    </svg>
  );
}
