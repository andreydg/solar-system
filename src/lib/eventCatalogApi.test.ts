import { afterEach, describe, expect, it, vi } from "vitest";
import { findNextEvent, findPreviousEvent, getValidatedEvents } from "./eventCatalogApi";

afterEach(() => vi.unstubAllGlobals());
const event = {
  id: "event", type: "opposition", bodyA: "earth", bodyB: "mars", timeUtc: "2027-02-19T00:00:00Z",
  computedTimeUtc: "2027-02-19T00:00:00Z", distanceAu: null, distanceKm: null,
  angleDeg: 180, magnitude: null, source: "JPL_HORIZONS", validationStatus: "validated",
};
describe("bounded catalog reads", () => {
  it("passes filters and the opaque cursor to the server", async () => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify([event]), { headers: { "X-Next-Cursor": "next" } }));
    vi.stubGlobal("fetch", fetch);
    const page = await getValidatedEvents({ bodyA: "mars", descending: true, cursor: "previous", from: "2026-01-01T00:00:00Z" });
    const url = new URL(fetch.mock.calls[0][0], "https://test.invalid");
    expect(url.searchParams.get("limit")).toBe("50");
    expect(url.searchParams.get("cursor")).toBe("previous");
    expect(url.searchParams.get("descending")).toBe("true");
    expect(page.nextCursor).toBe("next");
    expect(page.events).toHaveLength(1);
  });
  it.each([false, true])("fetches only the nearest event in the selected direction (%s)", async descending => {
    const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify([event])));
    vi.stubGlobal("fetch", fetch);
    await (descending ? findPreviousEvent : findNextEvent)("opposition", "earth", "mars", new Date(descending ? "2028-01-01T00:00:00Z" : "2026-01-01T00:00:00Z"));
    const url = new URL(fetch.mock.calls[0][0], "https://test.invalid");
    expect(url.searchParams.get("limit")).toBe("1");
    expect(url.searchParams.get("descending")).toBe(String(descending));
    expect(fetch).toHaveBeenCalledTimes(1);
  });
});
it.each([false, true])("excludes a stored event exactly at the anchor in both query and response (%s)", async previous => {
  const anchor = new Date(event.timeUtc);
  const adjacent = new Date(anchor.getTime() + (previous ? -1 : 1) * 86_400_000);
  const next = { ...event, id: "adjacent", timeUtc: adjacent.toISOString() };
  const fetcher = vi.fn<typeof fetch>().mockImplementation(async () => new Response(JSON.stringify(previous ? [next, event] : [event, next])));
  vi.stubGlobal("fetch", fetcher);
  const result = await (previous ? findPreviousEvent : findNextEvent)("opposition", "earth", "mars", anchor);
  expect(result?.id).toBe("adjacent");
  expect(fetcher).toHaveBeenCalledTimes(1);
  const params = new URL(fetcher.mock.calls[0][0] as string, "https://test.invalid").searchParams;
  expect(Date.parse(params.get(previous ? "to" : "from")!)).toBe(anchor.getTime() + (previous ? -1 : 1));
});

it.each([false, true])("pages past the current ID even after a 31-day validation shift (%s)", async previous => {
  const anchor = new Date(event.timeUtc);
  const direction = previous ? -1 : 1;
  const shifted = { ...event, timeUtc: new Date(+anchor + direction * 31 * 86_400_000).toISOString() };
  const adjacent = { ...event, id: "adjacent", timeUtc: new Date(+anchor + direction * 60 * 86_400_000).toISOString() };
  const fetcher = vi.fn<typeof fetch>()
    .mockResolvedValueOnce(new Response(JSON.stringify([shifted]), { headers: { "X-Next-Cursor": "after-current" } }))
    .mockResolvedValueOnce(new Response(JSON.stringify([adjacent])));
  vi.stubGlobal("fetch", fetcher);
  const result = await (previous ? findPreviousEvent : findNextEvent)("opposition", "earth", "mars", anchor, undefined, event.id);
  expect(result?.id).toBe("adjacent");
  expect(fetcher).toHaveBeenCalledTimes(2);
  const params = new URL(fetcher.mock.calls[1][0] as string, "https://test.invalid").searchParams;
  expect(params.get("cursor")).toBe("after-current");
});

it.each([false, true])("regeneration cannot reselect the current ID after validation moves it (%s)", async previous => {
  const anchor = new Date(event.timeUtc);
  const direction = previous ? -1 : 1;
  const shifted = { ...event, timeUtc: new Date(+anchor + direction * 31 * 86_400_000).toISOString() };
  const adjacent = { ...event, id: "adjacent", timeUtc: new Date(+anchor + direction * 60 * 86_400_000).toISOString() };
  const fetcher = vi.fn<typeof fetch>()
    .mockResolvedValueOnce(new Response("[]"))
    .mockResolvedValueOnce(new Response(JSON.stringify([shifted, adjacent])));
  vi.stubGlobal("fetch", fetcher);
  const result = await (previous ? findPreviousEvent : findNextEvent)("opposition", "earth", "mars", anchor, undefined, event.id);
  expect(result?.id).toBe("adjacent");
  expect(fetcher.mock.calls[1][1]?.method).toBe("POST");
});
