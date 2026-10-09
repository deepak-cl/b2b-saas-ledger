import { useVirtualizer } from "@tanstack/react-virtual";
import { useRef, type ReactNode } from "react";
import type { LedgerRow } from "../lib/ledgerRows";

const COLUMNS = ["Date", "What happened", "Account", "Debit", "Credit", "Status"] as const;

export function LedgerGrid({
  rows,
  label,
  detail,
  note,
  empty,
  action,
}: {
  rows: LedgerRow[];
  label: string;
  detail: string;
  note?: string | null;
  empty: string;
  action?: ReactNode;
}) {
  const parentRef = useRef<HTMLDivElement>(null);
  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => parentRef.current,
    estimateSize: () => 40,
    overscan: 16,
  });

  return (
    <section className="panel flex flex-col" aria-label={label}>
      <header className="flex items-start justify-between gap-4 border-b border-[var(--line)] px-5 py-4">
        <div>
          <h2 className="font-serif text-lg">{label}</h2>
          <p className="mt-1 text-sm text-[var(--muted)]">{note || detail}</p>
        </div>
        <div className="flex shrink-0 items-center gap-3">
          {rows.length > 0 ? (
            <p className="font-mono text-xs text-[var(--muted)]">{rows.length.toLocaleString()} lines</p>
          ) : null}
          {action}
        </div>
      </header>
      {rows.length === 0 ? (
        <p className="px-5 py-8 text-sm leading-6 text-[var(--muted)]">{empty}</p>
      ) : (
        <>
          <div className="grid grid-cols-[7.5rem_1fr_1.4fr_7rem_7rem_6rem] gap-3 border-b border-[var(--line)] px-5 py-2 font-mono text-[11px] tracking-wide text-[var(--muted)] uppercase">
            {COLUMNS.map((column) => (
              <span key={column} className={column === "Debit" || column === "Credit" ? "text-right" : ""}>
                {column}
              </span>
            ))}
          </div>
          <div ref={parentRef} className="h-80 overflow-auto">
            <div className="relative w-full" style={{ height: virtualizer.getTotalSize() }}>
              {virtualizer.getVirtualItems().map((item) => {
                const row = rows[item.index];
                return (
                  <div
                    key={row.id}
                    className="absolute top-0 left-0 grid w-full grid-cols-[7.5rem_1fr_1.4fr_7rem_7rem_6rem] gap-3 border-b border-[var(--line)] px-5 text-sm"
                    style={{ height: item.size, transform: `translateY(${item.start}px)` }}
                  >
                    <span className="self-center font-mono text-xs">{row.date}</span>
                    <span className="self-center truncate">{row.description}</span>
                    <span className="self-center truncate">{row.account}</span>
                    <span className="self-center text-right font-mono text-xs">{row.debit}</span>
                    <span className="self-center text-right font-mono text-xs">{row.credit}</span>
                    <span className="self-center text-xs text-[#1f4d3a]">Posted</span>
                  </div>
                );
              })}
            </div>
          </div>
        </>
      )}
    </section>
  );
}
