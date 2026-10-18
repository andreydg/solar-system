import { afterEach, expect, it, vi } from "vitest";
import { loadTrajectoryWithRetry, trajectoryCoverageNotice } from "./useSmallBodyTrajectories";
import { NetworkError, RequestTimeoutError } from "./http";
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

it("keeps retrying a busy NAT beyond three attempts and recovers without a toggle", async () => {
  vi.useFakeTimers();
  vi.setSystemTime(0);
  const times: number[] = [];
  const fetcher = vi.fn(async () => {
    times.push(performance.now());
    return times.length <= 8
      ? new Response("{}", { status: 429, headers: { "Retry-After": "2" } })
      : new Response(JSON.stringify(points));
  });
  vi.stubGlobal("fetch", fetcher);
  const result = loadTrajectoryWithRetry("halley", new AbortController().signal);
  await vi.advanceTimersByTimeAsync(1999);
  expect(fetcher).toHaveBeenCalledTimes(1);
  await vi.advanceTimersByTimeAsync(60_000);
  expect(await result).toHaveLength(2);
  expect(fetcher).toHaveBeenCalledTimes(9);
  for (let i = 1; i < times.length; i++) expect(times[i] - times[i - 1]).toBeGreaterThanOrEqual(2000);
  expect(times.at(-1)).toBeGreaterThan(20_000);
  expect(vi.getTimerCount()).toBe(0);
});

it("bounds persistent 429s by elapsed time and leaves no retry timers", async () => {
  vi.useFakeTimers();
  const started = performance.now();
  const times: number[] = [];
  vi.stubGlobal("fetch", vi.fn(async () => {
    times.push(performance.now() - started);
    return new Response("{}", { status: 429 });
  }));
  const result = loadTrajectoryWithRetry("halley", new AbortController().signal).catch(error => error);
  await vi.advanceTimersByTimeAsync(180_000);
  expect((await result).status).toBe(429);
  expect(times).toHaveLength(27);
  expect(times.at(-1)).toBe(117_500);
  expect(vi.getTimerCount()).toBe(0);
});

it("honours a minute Retry-After without scheduling beyond the two-minute budget", async () => {
  vi.useFakeTimers();
  const fetcher = vi.fn(async () => new Response("{}", { status: 429, headers: { "Retry-After": "60" } }));
  vi.stubGlobal("fetch", fetcher);
  const result = loadTrajectoryWithRetry("halley", new AbortController().signal).catch(error => error);
  await vi.advanceTimersByTimeAsync(59_999);
  expect(fetcher).toHaveBeenCalledTimes(1);
  await vi.advanceTimersByTimeAsync(1);
  expect((await result).status).toBe(429);
  expect(fetcher).toHaveBeenCalledTimes(2);
  expect(vi.getTimerCount()).toBe(0);
});

it("does not start a 429 retry after a suspended tab wakes beyond its deadline", async () => {
  vi.useFakeTimers();
  const started = performance.now();
  const fetcher = vi.fn(async () => new Response("{}", { status: 429 }));
  vi.stubGlobal("fetch", fetcher);
  const result = loadTrajectoryWithRetry("halley", new AbortController().signal).catch(error => error);
  await vi.advanceTimersByTimeAsync(0);
  const now = vi.spyOn(performance, "now").mockReturnValue(started + 120_001);
  try {
    await vi.advanceTimersByTimeAsync(500);
    expect((await result).status).toBe(429);
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(vi.getTimerCount()).toBe(0);
  } finally { now.mockRestore(); }
});

it("cancels extended 429 backoff and never retries permanent HTTP failures", async () => {
  vi.useFakeTimers();
  const fetcher = vi.fn(async () => new Response("{}", { status: 429 }));
  vi.stubGlobal("fetch", fetcher);
  const controller = new AbortController();
  const result = loadTrajectoryWithRetry("halley", controller.signal).catch(error => error);
  await vi.advanceTimersByTimeAsync(8000);
  const count = fetcher.mock.calls.length;
  expect(count).toBeGreaterThan(3);
  controller.abort();
  expect((await result).name).toBe("AbortError");
  await vi.advanceTimersByTimeAsync(180_000);
  expect(fetcher).toHaveBeenCalledTimes(count);
  expect(vi.getTimerCount()).toBe(0);
  fetcher.mockImplementation(async () => new Response("{}", { status: 400 }));
  expect((await loadTrajectoryWithRetry("halley", new AbortController().signal).catch(error => error)).status).toBe(400);
  expect(fetcher).toHaveBeenCalledTimes(count + 1);
});

it("retains an independent 30-attempt ceiling even if the elapsed clock stops advancing", async () => {
  vi.useFakeTimers();
  const now = vi.spyOn(performance, "now").mockReturnValue(0);
  try {
    const fetcher = vi.fn(async () => new Response("{}", { status: 429 }));
    vi.stubGlobal("fetch", fetcher);
    const result = loadTrajectoryWithRetry("halley", new AbortController().signal).catch(error => error);
    await vi.advanceTimersByTimeAsync(200_000);
    expect(fetcher).toHaveBeenCalledTimes(30);
    expect((await result).status).toBe(429);
    expect(vi.getTimerCount()).toBe(0);
  } finally { now.mockRestore(); }
});

it("fetch TypeError keeps the independent three-attempt network limit", async () => {
  vi.useFakeTimers();
  const fetcher = vi.fn().mockRejectedValue(new TypeError("connection dropped"));
  vi.stubGlobal("fetch", fetcher);
  const result = loadTrajectoryWithRetry("halley", new AbortController().signal).catch(error => error);
  await vi.advanceTimersByTimeAsync(180_000);
  expect(await result).toBeInstanceOf(NetworkError);
  expect(fetcher).toHaveBeenCalledTimes(3);
  expect(vi.getTimerCount()).toBe(0);
});

it("real HTTP timeouts keep three attempts even if the elapsed clock stalls", async () => {
  vi.useFakeTimers();
  // Pin the attempt limit independently of the 429 elapsed-time ceiling.
  const now = vi.spyOn(performance, "now").mockReturnValue(0);
  try {
    const fetcher = vi.fn((_url: string, options: RequestInit) => new Promise<Response>((_resolve, reject) => {
      options.signal!.addEventListener("abort", () => reject(options.signal!.reason), { once: true });
    }));
    vi.stubGlobal("fetch", fetcher);
    const result = loadTrajectoryWithRetry("halley", new AbortController().signal).catch(error => error);
    await vi.advanceTimersByTimeAsync(44_999);
    expect(fetcher).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(1_500_000);
    expect(await result).toBeInstanceOf(RequestTimeoutError);
    expect(fetcher).toHaveBeenCalledTimes(3);
    expect(vi.getTimerCount()).toBe(0);
  } finally { now.mockRestore(); }
});

it("503 Retry-After beyond two minutes gives up without a retry timer", async () => {
  vi.useFakeTimers();
  const fetcher = vi.fn(async () => new Response("{}", { status: 503, headers: { "Retry-After": "300" } }));
  vi.stubGlobal("fetch", fetcher);
  const result = loadTrajectoryWithRetry("halley", new AbortController().signal).catch(error => error);
  await vi.advanceTimersByTimeAsync(600_000);
  expect((await result).status).toBe(503);
  expect(fetcher).toHaveBeenCalledTimes(1);
  expect(vi.getTimerCount()).toBe(0);
});

it("two quota waits do not exhaust the following 502 retry allowance", async () => {
  vi.useFakeTimers();
  const fetcher = vi.fn()
    .mockResolvedValueOnce(new Response("{}", { status: 429 }))
    .mockResolvedValueOnce(new Response("{}", { status: 429 }))
    .mockResolvedValueOnce(new Response("{}", { status: 502 }))
    .mockResolvedValueOnce(new Response(JSON.stringify(points)));
  vi.stubGlobal("fetch", fetcher);
  const result = loadTrajectoryWithRetry("halley", new AbortController().signal).catch(error => error);
  await vi.advanceTimersByTimeAsync(5000);
  expect(await result).toHaveLength(2);
  expect(fetcher).toHaveBeenCalledTimes(4);
  expect(vi.getTimerCount()).toBe(0);
});

it("interleaved quota waits never reset the server-error retry count", async () => {
  vi.useFakeTimers();
  const statuses = [502, 429, 502, 429, 502];
  const fetcher = vi.fn(async () => new Response("{}", { status: statuses.shift() ?? 502 }));
  vi.stubGlobal("fetch", fetcher);
  const result = loadTrajectoryWithRetry("halley", new AbortController().signal).catch(error => error);
  await vi.advanceTimersByTimeAsync(180_000);
  expect((await result).status).toBe(502);
  expect(fetcher).toHaveBeenCalledTimes(5);
  expect(vi.getTimerCount()).toBe(0);
});

it("server failures do not spend the independent thirty-response quota ceiling", async () => {
  vi.useFakeTimers();
  const now = vi.spyOn(performance, "now").mockReturnValue(0);
  try {
    let requests = 0;
    const fetcher = vi.fn(async () => new Response("{}", { status: ++requests <= 2 ? 502 : 429 }));
    vi.stubGlobal("fetch", fetcher);
    const result = loadTrajectoryWithRetry("halley", new AbortController().signal).catch(error => error);
    await vi.advanceTimersByTimeAsync(200_000);
    expect((await result).status).toBe(429);
    expect(fetcher).toHaveBeenCalledTimes(32);
    expect(vi.getTimerCount()).toBe(0);
  } finally { now.mockRestore(); }
});
