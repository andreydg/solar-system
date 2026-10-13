import { describe, it, expect, vi, afterEach } from "vitest";
import { parseUtcInput } from "./timeUtils";
import { findClosestApproach } from "./ephemeris";
import { findNextEvent } from "./eventCatalogApi";
import { canUseBrowserFallback, HttpError } from "./http";

afterEach(() => vi.unstubAllGlobals());
describe("search correctness", () => {
  it("parses explicit UTC and rejects empty, normalized, and out-of-range values", () => {
    expect(parseUtcInput("2026-07-04T12:30")?.toISOString()).toBe("2026-07-04T12:30:00.000Z");
    for (const value of ["", "bad", "2026-02-30T12:30", "1599-12-31T12:30", "3000-01-01T00:00"]) expect(parseUtcInput(value)).toBeNull();
  });
  it("finds the next Mars minimum, not a closer minimum several years later", () => {
    const result = findClosestApproach("earth", "mars", new Date("2026-01-01Z"), new Date("2031-01-01Z"));
    expect(result?.time.getUTCFullYear()).toBe(2027);
    const previous = findClosestApproach("earth", "mars", new Date("2026-01-01Z"), new Date("2031-01-01Z"), "prev");
    expect(previous?.time.getUTCFullYear()).toBe(2029);
    expect(findClosestApproach("earth", "halley", new Date(), new Date())).toBeNull();
  });
  it("does not skip a Mars minimum within the first twelve hours in either direction", () => {
    const minimum = new Date("2027-02-20T00:20:00Z").getTime();
    for (let hours = 1; hours <= 11; hours++) {
      const after = new Date(minimum - hours * 3600000);
      const next = findClosestApproach("earth", "mars", after, new Date("2030-01-01Z"));
      expect(next?.time.getTime()).toBeGreaterThan(after.getTime());
      expect(Math.abs(next!.time.getTime() - minimum)).toBeLessThan(3600000);
      const before = new Date(minimum + hours * 3600000);
      const previous = findClosestApproach("earth", "mars", new Date("2025-01-01Z"), before, "prev");
      expect(previous?.time.getTime()).toBeLessThan(before.getTime());
      expect(Math.abs(previous!.time.getTime() - minimum)).toBeLessThan(3600000);
    }
  });
  it("does not mask rate limits or bad requests with a browser fallback", () => {
    expect(canUseBrowserFallback(new HttpError(429, "Wait"))).toBe(false);
    expect(canUseBrowserFallback(new HttpError(400, "Invalid"))).toBe(false);
    expect(canUseBrowserFallback(new HttpError(503, "Unavailable"))).toBe(true);
  });
  it("propagates cancellation through catalog requests", async () => {
    const controller = new AbortController();
    const fetch = vi.fn().mockImplementation((_url, init) => new Promise((_resolve, reject) => {
      init.signal.addEventListener("abort", () => reject(init.signal.reason), { once: true });
    }));
    vi.stubGlobal("fetch", fetch);
    const search = findNextEvent("opposition", "earth", "mars", new Date("2026-01-01Z"), controller.signal);
    controller.abort();
    await expect(search).rejects.toMatchObject({ name: "AbortError" });
    expect(fetch).toHaveBeenCalledTimes(1);
  });
});
