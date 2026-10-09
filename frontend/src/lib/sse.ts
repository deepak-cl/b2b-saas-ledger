export type SseEvent = {
  event: string;
  data: string;
};

/** One SSE frame (the text between blank lines). Accepts `event:token` and `event: token`. */
export function parseSseFrame(frame: string): SseEvent | null {
  let event = "message";
  const data: string[] = [];
  for (const raw of frame.split("\n")) {
    const line = raw.replace(/\r$/, "");
    if (line.startsWith("event:")) event = line.slice("event:".length).trim();
    else if (line.startsWith("data:")) data.push(line.slice("data:".length).trim());
  }
  if (data.length === 0) return null;
  return { event, data: data.join("\n") };
}

/** Pulls complete frames out of a growing buffer. The remainder stays for the next chunk. */
export function drainSse(buffer: string): { events: SseEvent[]; rest: string } {
  const parts = buffer.split(/\n\n/);
  const rest = parts.pop() ?? "";
  const events = parts.map(parseSseFrame).filter((event): event is SseEvent => event !== null);
  return { events, rest };
}
