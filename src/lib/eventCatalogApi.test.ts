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
    await (descending ? findPreviousEvent : findNextEvent)("opposition", "earth", "mars", new Date("2028-01-01T00:00:00Z"));
    const url = new URL(fetch.mock.calls[0][0], "https://test.invalid");
    expect(url.searchParams.get("limit")).toBe("1");
    expect(url.searchParams.get("descending")).toBe(String(descending));
    expect(fetch).toHaveBeenCalledTimes(1);
  });
});
