// @vitest-environment happy-dom
import { act } from "react";
import { createRoot, type Root } from "react-dom/client";
import { afterEach, expect, it, vi } from "vitest";
import type { BodyId } from "../domain/solarSystem";
import useSmallBodyTrajectories from "./useSmallBodyTrajectories";

let root: Root | undefined;
afterEach(async () => {
  if (root) await act(async () => root!.unmount());
  root = undefined;
  document.body.innerHTML = "";
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

it.each(["download", "backoff"] as const)("toggling off cancels a real hook %s without hidden retries", async phase => {
  vi.useFakeTimers();
  vi.stubGlobal("IS_REACT_ACT_ENVIRONMENT", true);
  let requestSignal: AbortSignal | undefined;
  const fetcher = vi.fn((_url: string, options: RequestInit) => {
    requestSignal = options.signal!;
    if (phase === "backoff") return Promise.resolve(new Response("{}", { status: 429 }));
    return new Promise<Response>((_resolve, reject) => {
      requestSignal!.addEventListener("abort", () => reject(requestSignal!.reason), { once: true });
    });
  });
  vi.stubGlobal("fetch", fetcher);
  function Probe({ bodies }: { bodies: BodyId[] }) {
    const state = useSmallBodyTrajectories(bodies);
    return <output>{state.error ?? Object.keys(state.trajectories).join(",")}</output>;
  }
  const container = document.createElement("div");
  document.body.append(container);
  root = createRoot(container);
  await act(async () => root!.render(<Probe bodies={["halley"]} />));
  await act(async () => { await vi.advanceTimersByTimeAsync(0); });
  expect(fetcher).toHaveBeenCalledTimes(1);
  expect(requestSignal!.aborted).toBe(false);
  await act(async () => root!.render(<Probe bodies={[]} />));
  if (phase === "download") expect(requestSignal!.aborted).toBe(true);
  // A hidden body must not retain either the HTTP deadline or a backoff timer.
  expect(vi.getTimerCount()).toBe(0);
  await act(async () => { await vi.advanceTimersByTimeAsync(180_000); });
  expect(fetcher).toHaveBeenCalledTimes(1);
  expect(container.textContent).toBe("");
});
