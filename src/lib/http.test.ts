import serverBudgetSource from "../../backend/src/main/java/dev/andreydg/solarsystem/catalog/GenerationBudget.java?raw";
import { afterEach, expect, it, vi } from "vitest";
import { canUseBrowserFallback, fetchJson, HttpError, NetworkError, RequestTimeoutError, requestScope, REQUEST_TIMEOUT_MS } from "./http";

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
it("removes every completed request listener from a long-lived parent", async () => {
  vi.useFakeTimers();
  const parent = new AbortController();
  const listeners = new Set<EventListenerOrEventListenerObject>();
  const add = parent.signal.addEventListener.bind(parent.signal);
  const remove = parent.signal.removeEventListener.bind(parent.signal);
  vi.spyOn(parent.signal, "addEventListener").mockImplementation((type, listener, options) => {
    if (type === "abort" && listener) listeners.add(listener);
    add(type, listener, options);
  });
  vi.spyOn(parent.signal, "removeEventListener").mockImplementation((type, listener, options) => {
    if (type === "abort" && listener) listeners.delete(listener);
    remove(type, listener, options);
  });
  vi.stubGlobal("fetch", vi.fn(async () => new Response("{}")));
  for (let i = 0; i < 80; i++) await fetchJson("/test", { signal: parent.signal });
  expect(listeners.size).toBe(0);
  expect(vi.getTimerCount()).toBe(0);
});
it("keeps the actual default request deadline above the server generation budget", async () => {
  const serverMs = Number(serverBudgetSource.match(/Duration.ofSeconds\((\d+)\)/)![1]) * 1000;
  expect(REQUEST_TIMEOUT_MS).toBeGreaterThan(serverMs + 5_000);
  vi.useFakeTimers();
  const scope = requestScope();
  await vi.advanceTimersByTimeAsync(serverMs + 5_000);
  expect(scope.signal.aborted).toBe(false);
  await vi.advanceTimersByTimeAsync(REQUEST_TIMEOUT_MS);
  expect(scope.signal.reason).toBeInstanceOf(RequestTimeoutError);
  scope.dispose();
});
