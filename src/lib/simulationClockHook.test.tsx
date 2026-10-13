import { afterEach, expect, it, vi } from "vitest";
import { create, act } from "@react-three/test-renderer";
import { useSimulationClock } from "./useSimulationClock";

afterEach(() => vi.unstubAllGlobals());

it("updates at display cadence and does not restart time while the speed slider changes", async () => {
  const visibility = new EventTarget() as EventTarget & { hidden: boolean };
  visibility.hidden = false;
  vi.stubGlobal("document", visibility);
  let id = 0;
  const frames = new Map<number, FrameRequestCallback>();
  vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) => { frames.set(++id, callback); return id; });
  vi.stubGlobal("cancelAnimationFrame", (key: number) => frames.delete(key));
  let time = new Date("2026-01-01Z");
  let updates = 0;
  const update = (fn: (time: Date) => Date) => { time = fn(time); updates++; };
  function Clock({ speed }: { speed: number }) { useSimulationClock(true, speed, update); return null; }
  const renderer = await create(<Clock speed={1} />);
  const frame = async (now: number) => {
    const current = [...frames.values()]; frames.clear();
    await act(async () => current.forEach(callback => callback(now)));
  };
  await frame(1000);
  for (let i = 1; i <= 60; i++) {
    if (i === 31) await renderer.update(<Clock speed={2} />);
    await frame(1000 + i * (1000 / 60));
  }
  expect(updates).toBe(60);
  expect(Math.abs(time.getTime() - new Date("2026-01-01Z").getTime() - 1.5 * 86400000)).toBeLessThan(100);
  visibility.hidden = true;
  visibility.dispatchEvent(new Event("visibilitychange"));
  expect(frames.size).toBe(0);
  visibility.hidden = false;
  visibility.dispatchEvent(new Event("visibilitychange"));
  await frame(100000);
  expect(updates).toBe(60);
  await renderer.unmount();
  expect(frames.size).toBe(0);
});
