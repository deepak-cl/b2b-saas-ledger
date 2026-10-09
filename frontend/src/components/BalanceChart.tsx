import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { BalanceSheet } from "../api/ledger";
import { money } from "../lib/ledgerRows";

export function BalanceChart({ sheet, status }: { sheet: BalanceSheet | null; status?: string | null }) {
  const data =
    sheet?.periods.map((period, index) => ({
      period,
      Assets: Number(sheet.assets.totals[index] ?? 0),
      Liabilities: Number(sheet.liabilities.totals[index] ?? 0),
      Equity: Number(sheet.equity.totals[index] ?? 0),
    })) ?? [];

  return (
    <section className="panel flex h-full flex-col p-4">
      <header className="mb-1">
        <h2 className="font-serif text-lg">How the books sit each month</h2>
        <p className="mt-1 text-sm text-[var(--muted)]">
          {sheet
            ? "A balanced month means assets equal liabilities plus equity."
            : status || "Sign in to see a company's balance sheet."}
        </p>
      </header>
      {data.length === 0 ? (
        <p className="mt-3 text-sm text-[var(--muted)]">
          {status || "Months appear here after the company has activity."}
        </p>
      ) : (
        <div className="min-h-0 flex-1">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={data} barGap={2}>
              <CartesianGrid stroke="var(--line)" vertical={false} />
              <XAxis dataKey="period" tick={{ fill: "var(--muted)", fontSize: 11 }} axisLine={false} tickLine={false} />
              <YAxis
                tick={{ fill: "var(--muted)", fontSize: 11 }}
                axisLine={false}
                tickLine={false}
                width={72}
                tickFormatter={(value: number) => money(value)}
              />
              <Tooltip formatter={(value) => money(Number(value))} />
              <Legend />
              <Bar dataKey="Assets" fill="#1f4d3a" radius={[2, 2, 0, 0]} />
              <Bar dataKey="Liabilities" fill="#8c3b2a" radius={[2, 2, 0, 0]} />
              <Bar dataKey="Equity" fill="#c4a574" radius={[2, 2, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}
    </section>
  );
}
