import { afterEach, expect, it, vi } from "vitest";
import { canReloadApp } from "./sceneRecovery";

const index = '<div id="root" data-app="solar-system-explorer"></div><script type="module" crossorigin src="/assets/index-hash.js"></script>';
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });

it("reload probe requires a fresh successful app index", async () => {
  const fetcher = vi.fn().mockResolvedValue(new Response(index, { headers: { "content-type": "text/html" } }));
  vi.stubGlobal("fetch", fetcher);
  expect(await canReloadApp()).toBe(true);
  expect(fetcher).toHaveBeenCalledWith("/", { cache: "no-store", signal: expect.any(AbortSignal) });
});

it.each([
  ["<html>Sign in to Wi-Fi</html>", 200, "text/html"],
  [index, 503, "text/html"],
  [index, 200, "text/plain"],
  ['<div id="root" data-app="solar-system-explorer"></div>', 200, "text/html"],
] as const)("reload probe rejects non-app or unsuccessful responses (%s, %i)", async (body, status, type) => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(body, { status, headers: { "content-type": type } })));
  expect(await canReloadApp()).toBe(false);
});

it("reload probe contains refused requests", async () => {
  vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));
  expect(await canReloadApp()).toBe(false);
});

it("reload probe aborts a hung request within two seconds", async () => {
  vi.useFakeTimers();
  let signal: AbortSignal | undefined;
  vi.stubGlobal("fetch", vi.fn((_url, init: RequestInit) => new Promise((_resolve, reject) => {
    signal = init.signal!;
    signal.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
  })));
  const result = canReloadApp();
  await vi.advanceTimersByTimeAsync(2_000);
  expect(signal?.aborted).toBe(true);
  expect(await result).toBe(false);
  expect(vi.getTimerCount()).toBe(0);
});
it("reload probe rejects a foreign bundled page without app identity", async () => {
  const html = '<title>Wi-Fi login</title><div id="root"></div><script type="module" src="/assets/index.js"></script>';
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(html, { headers: { "content-type": "text/html" } })));
  expect(await canReloadApp()).toBe(false);
});

it("reload probe recognises a marker-less rollback index by title and module script", async () => {
  const html = '<title>Solar System Explorer</title><div id="root"></div><script type="module" src="/assets/index-old.js"></script>';
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(html, { headers: { "content-type": "text/html" } })));
  expect(await canReloadApp()).toBe(true);
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("<title>Solar System Explorer</title>", { headers: { "content-type": "text/html" } })));
  expect(await canReloadApp()).toBe(false);
});

it("reload probe accepts a valid response at 1.9 seconds before its two-second deadline", async () => {
  vi.useFakeTimers();
  let signal: AbortSignal | undefined;
  vi.stubGlobal("fetch", vi.fn((_url, init: RequestInit) => new Promise((resolve, reject) => {
    signal = init.signal!;
    signal.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
    setTimeout(() => resolve(new Response(index, { headers: { "content-type": "text/html" } })), 1_900);
  })));
  const result = canReloadApp();
  await vi.advanceTimersByTimeAsync(1_900);
  expect(signal?.aborted).toBe(false);
  expect(await result).toBe(true);
  expect(vi.getTimerCount()).toBe(0);
});
