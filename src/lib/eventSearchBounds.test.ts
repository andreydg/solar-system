import { afterEach, expect, it, vi } from "vitest";
import { findNextEvent, findPreviousEvent } from "./eventCatalogApi";

afterEach(() => vi.unstubAllGlobals());

it.each([
  ["next", "2990-01-01T00:00:00Z"],
  ["previous", "1601-01-01T00:00:00Z"],
] as const)("clamps %s search and generation to supported catalog years", async (direction, anchor) => {
  const fetcher = vi.fn(async () => new Response("[]"));
  vi.stubGlobal("fetch", fetcher);
  const find = direction === "next" ? findNextEvent : findPreviousEvent;
  expect(await find("closestApproach", "earth", "mars", new Date(anchor))).toBeNull();
  expect(fetcher).toHaveBeenCalledTimes(2);
  for (const call of fetcher.mock.calls as unknown as [string, RequestInit | undefined][]) {
    const range = call[1]?.body
      ? JSON.parse(String(call[1].body)) as Record<string, string>
      : Object.fromEntries(new URL(call[0], "http://localhost").searchParams);
    expect(new Date(range.from).getUTCFullYear()).toBeGreaterThanOrEqual(1600);
    expect(new Date(range.to).getUTCFullYear()).toBeLessThan(3000);
  }
});

it("follows empty catalog cursors instead of generating duplicate events", async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(new Response("[]", { headers: { "X-Next-Cursor": "next-page" } }))
    .mockResolvedValueOnce(new Response(JSON.stringify([{
      id: "valid", type: "opposition", bodyA: "earth", bodyB: "mars",
      timeUtc: "2991-01-01T00:00:00Z", computedTimeUtc: "2991-01-01T00:00:00Z",
      angleDeg: 180, validationStatus: "validated",
    }])));
  vi.stubGlobal("fetch", fetcher);
  expect((await findNextEvent("opposition", "earth", "mars", new Date("2990-01-01Z")))?.id).toBe("valid");
  expect(fetcher).toHaveBeenCalledTimes(2);
  expect(fetcher.mock.calls[1][0]).toContain("cursor=next-page");
  expect(fetcher.mock.calls.every(call => !call[1])).toBe(true);
});

it("falls back to bounded generation after twenty skipped catalog pages", async () => {
  const fetcher = vi.fn(async (_url: string, options?: RequestInit) => options?.method === "POST"
    ? new Response(JSON.stringify([{ id: "repaired", type: "opposition", bodyA: "earth", bodyB: "mars",
        timeUtc: "2991-01-01T00:00:00Z", computedTimeUtc: "2991-01-01T00:00:00Z", validationStatus: "pending" }]))
    : new Response("[]", { headers: { "X-Next-Cursor": "stuck" } }));
  vi.stubGlobal("fetch", fetcher);
  expect((await findNextEvent("opposition", "earth", "mars", new Date("2990-01-01Z")))?.id).toBe("repaired");
  expect(fetcher).toHaveBeenCalledTimes(21);
  expect(fetcher.mock.calls[20][1]?.method).toBe("POST");
});
