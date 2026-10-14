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
