import { afterEach, expect, it, vi } from "vitest";
import { canUseBrowserFallback, fetchJson, HttpError, NetworkError, RequestTimeoutError, requestScope } from "./http";

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers(); });

it("combines cancellation without AbortSignal.any or AbortSignal.timeout", async () => {
  vi.spyOn(AbortSignal, "any").mockImplementation(() => { throw Error("unsupported"); });
  vi.spyOn(AbortSignal, "timeout").mockImplementation(() => { throw Error("unsupported"); });
  vi.stubGlobal("fetch", vi.fn(async (_url, options) => new Promise((_resolve, reject) =>
    options.signal.addEventListener("abort", () => reject(options.signal.reason)))));
  const controller = new AbortController();
  const request = fetchJson("/test", { signal: controller.signal });
  controller.abort();
  await expect(request).rejects.toMatchObject({ name: "AbortError" });
});

it("cleans up completed deadlines and distinguishes real network failures from coding errors", async () => {
  vi.useFakeTimers();
  vi.stubGlobal("fetch", vi.fn().mockRejectedValueOnce(new TypeError("network offline"))
    .mockResolvedValueOnce({ ok: true, json: () => { throw new TypeError("parser bug"); } }));
  await expect(fetchJson("/test")).rejects.toBeInstanceOf(NetworkError);
  await expect(fetchJson("/test")).rejects.toThrow("parser bug");
  expect(canUseBrowserFallback(new TypeError("coding bug"))).toBe(false);
  expect(canUseBrowserFallback(new HttpError(500, "storage"))).toBe(true);
  expect(vi.getTimerCount()).toBe(0);
});

it("times out with a useful error and handles already-aborted parents", async () => {
  vi.useFakeTimers();
  const scope = requestScope(undefined, 100);
  await vi.advanceTimersByTimeAsync(100);
  expect(scope.signal.reason).toBeInstanceOf(RequestTimeoutError);
  expect(canUseBrowserFallback(scope.signal.reason)).toBe(true);
  scope.dispose();
  const parent = new AbortController(); parent.abort();
  const child = requestScope(parent.signal);
  expect(child.signal.aborted).toBe(true);
  child.dispose();
  expect(vi.getTimerCount()).toBe(0);
});
