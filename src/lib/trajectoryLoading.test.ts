import { afterEach, expect, it, vi } from "vitest";
import { loadTrajectoryWithRetry, trajectoryCoverageNotice } from "./useSmallBodyTrajectories";
import type { SmallBodyTrajectory } from "./smallBodyTrajectory";

const points = [{ timeUtc: "2026-01-01Z", x: 1, y: 0, z: 0 }, { timeUtc: "2027-01-01Z", x: 2, y: 0, z: 0 }];
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });

it("retries 429 only after Retry-After and retains a bounded attempt count", async () => {
  vi.useFakeTimers();
  const fetcher = vi.fn().mockResolvedValueOnce(new Response("{}", { status: 429, headers: { "Retry-After": "2" } }))
    .mockResolvedValueOnce(new Response(JSON.stringify(points)));
  vi.stubGlobal("fetch", fetcher);
  const result = loadTrajectoryWithRetry("halley", new AbortController().signal);
  await vi.advanceTimersByTimeAsync(1999);
  expect(fetcher).toHaveBeenCalledTimes(1);
  await vi.advanceTimersByTimeAsync(1);
  expect(await result).toHaveLength(2);
  expect(fetcher).toHaveBeenCalledTimes(2);
  expect(vi.getTimerCount()).toBe(0);
});

it("cancels backoff and stops after three transient failures", async () => {
  vi.useFakeTimers();
  const fetcher = vi.fn(async () => new Response("{}", { status: 503 }));
  vi.stubGlobal("fetch", fetcher);
  const controller = new AbortController();
  const cancelled = loadTrajectoryWithRetry("halley", controller.signal).catch(error => error);
  await vi.advanceTimersByTimeAsync(0); controller.abort();
  expect((await cancelled).name).toBe("AbortError");
  await vi.advanceTimersByTimeAsync(10000);
  expect(fetcher).toHaveBeenCalledTimes(1);
  const failed = loadTrajectoryWithRetry("halley", new AbortController().signal).catch(error => error);
  await vi.advanceTimersByTimeAsync(5000);
  expect((await failed).status).toBe(503);
  expect(fetcher).toHaveBeenCalledTimes(4);
  expect(vi.getTimerCount()).toBe(0);
});

it("labels repeated-orbit extrapolation only for visible loaded bodies", () => {
  const trajectory: SmallBodyTrajectory = points.map(p => ({ time: new Date(p.timeUtc), positionAu: p }));
  expect(trajectoryCoverageNotice(["halley"], { halley: trajectory }, new Date("1700-01-01Z"))).toContain("repeated-orbit approximation");
  expect(trajectoryCoverageNotice(["earth"], { halley: trajectory }, new Date("1700-01-01Z"))).toBeNull();
  expect(trajectoryCoverageNotice(["halley"], { halley: trajectory }, new Date("2026-06-01Z"))).toBeNull();
});
