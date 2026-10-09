import { describe, expect, it } from "vitest";
import { drainSse, parseSseFrame } from "./sse";

describe("sse", () => {
  it("reads a frame with no space after the colon", () => {
    expect(parseSseFrame('event:token\ndata:{"text":"Cloud "}')).toEqual({
      event: "token",
      data: '{"text":"Cloud "}',
    });
  });

  it("holds an incomplete frame until the blank line arrives", () => {
    const first = drainSse('event:token\ndata:{"text":"A"}\n\nevent:finding\ndata:');
    expect(first.events).toEqual([{ event: "token", data: '{"text":"A"}' }]);
    const second = drainSse(first.rest + '{"type":"ANOMALY"}\n\n');
    expect(second.events).toEqual([{ event: "finding", data: '{"type":"ANOMALY"}' }]);
    expect(second.rest).toBe("");
  });
});
