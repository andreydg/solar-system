import { expect, test } from "@playwright/test";
import { findPreviousEvent } from "../src/lib/eventCatalogApi";

test("library Prev excludes the exact stored-time anchor against the real packaged jar", async ({ request, baseURL }) => {
  const generated = await request.post("/api/events/generate", { data: {
    type: "closestApproach", bodyA: "earth", bodyB: "mars",
    from: "2026-01-01T00:00:00Z", to: "2030-01-01T00:00:00Z",
  } });
  expect(generated.ok()).toBe(true);
  const events = (await generated.json() as { id: string; timeUtc: string }[])
    .sort((a, b) => Date.parse(a.timeUtc) - Date.parse(b.timeUtc));
  expect(events.length).toBeGreaterThanOrEqual(2);
  const anchor = new Date(events[1].timeUtc);
  const originalFetch = globalThis.fetch;
  const calls: URL[] = [];
  // Node needs an origin for relative URLs; every response is still real HTTP from the jar.
  globalThis.fetch = (input, init) => {
    const url = new URL(String(input), baseURL);
    calls.push(url);
    return originalFetch(url, init);
  };
  try {
    const previous = await findPreviousEvent("closestApproach", "earth", "mars", anchor);
    expect(previous?.id).toBe(events[0].id);
    expect(previous?.time.getTime()).toBeLessThan(anchor.getTime());
    expect(calls).toHaveLength(1);
    expect(Date.parse(calls[0].searchParams.get("to")!)).toBe(anchor.getTime() - 1);
  } finally { globalThis.fetch = originalFetch; }
});

