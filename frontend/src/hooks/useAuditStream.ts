import { useEffect, useRef, useState } from "react";
import { explainProblem, type Problem } from "../lib/problems";
import { drainSse } from "../lib/sse";

export type Finding = {
  type?: string;
  severity?: string;
  title?: string;
  detail?: string;
  accountCode?: string;
  period?: string;
  amount?: string;
};

export function useAuditStream(token: string | null, tenant: string | null) {
  const [text, setText] = useState("");
  const [findings, setFindings] = useState<Finding[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [streaming, setStreaming] = useState(false);
  const abort = useRef<AbortController | null>(null);

  useEffect(() => {
    abort.current?.abort();
    setText("");
    setFindings([]);
    setError(null);
    setStreaming(false);
  }, [token, tenant]);

  async function ask(query: string) {
    abort.current?.abort();
    const controller = new AbortController();
    abort.current = controller;
    setError(null);
    setText("");
    setFindings([]);
    setStreaming(true);
    if (!token || !tenant) {
      try {
        await preview(query, controller.signal, setText);
      } finally {
        setStreaming(false);
      }
      return;
    }
    try {
      const response = await fetch("/api/v1/ai/audit/query", {
        method: "POST",
        signal: controller.signal,
        headers: {
          Authorization: `Bearer ${token}`,
          "X-Tenant-ID": tenant,
          Accept: "text/event-stream",
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ query }),
      });
      if (!response.ok || !response.body) {
        let problem: Problem = { status: response.status };
        try {
          problem = { ...problem, ...(await response.json()) };
        } catch {
          /* body was not JSON */
        }
        throw new Error(explainProblem(problem));
      }
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = "";
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const drained = drainSse(buffer);
        buffer = drained.rest;
        apply(drained.events, setText, setFindings, setError);
      }
    } catch (caught) {
      if ((caught as Error).name !== "AbortError") {
        setError(caught instanceof Error ? caught.message : "The question could not be answered.");
      }
    } finally {
      setStreaming(false);
    }
  }

  function cancel() {
    abort.current?.abort();
    setStreaming(false);
  }

  return { text, findings, error, streaming, ask, cancel };
}

function apply(
  events: { event: string; data: string }[],
  setText: (value: string | ((current: string) => string)) => void,
  setFindings: (value: Finding[] | ((current: Finding[]) => Finding[])) => void,
  setError: (value: string) => void,
) {
  let text = "";
  const findings: Finding[] = [];
  let failed = false;
  for (const event of events) {
    if (event.event === "token") {
      const payload = JSON.parse(event.data) as { text?: string };
      const chunk = payload.text ?? "";
      text += chunk;
      setText((current) => current + chunk);
    } else if (event.event === "finding") {
      const finding = JSON.parse(event.data) as Finding;
      findings.push(finding);
      setFindings((current) => [...current, finding]);
    } else if (event.event === "error") {
      failed = true;
      const problem = JSON.parse(event.data) as Problem;
      setError(explainProblem({ ...problem, status: problem.status ?? 500 }));
    }
  }
  return { text, findings, failed };
}

/** Local typing effect so the command bar can be tried without a signed-in tenant. */
async function preview(query: string, signal: AbortSignal, setText: (value: string) => void) {
  const sentence = `Preview only. Signed in, this would stream an answer for “${query}” from the tenant ledger.`;
  let shown = "";
  for (const char of sentence) {
    if (signal.aborted) return;
    shown += char;
    setText(shown);
    await new Promise((resolve) => setTimeout(resolve, 12));
  }
}
