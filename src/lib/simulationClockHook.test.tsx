import { afterEach, expect, it, vi } from "vitest";
import { create, act } from "@react-three/test-renderer";
import { useSimulationClock } from "./useSimulationClock";

afterEach(() => vi.unstubAllGlobals());

it("advances every frame but publishes at <=10 Hz, survives speed edits and ignores queued ticks after pause", async () => {
  const visibility = new EventTarget() as EventTarget & { hidden: boolean };
  visibility.hidden = false;
  vi.stubGlobal("document", visibility);
  let id = 0;
  const frames = new Map<number, FrameRequestCallback>();
  vi.stubGlobal("requestAnimationFrame", (callback: FrameRequestCallback) => { frames.set(++id, callback); return id; });
  vi.stubGlobal("cancelAnimationFrame", (key: number) => frames.delete(key));
  const initial = new Date("2026-01-01Z");
  let renders = 0, clock!: ReturnType<typeof useSimulationClock>;
  function Clock({ speed }: { speed: number }) {
    clock = useSimulationClock(initial, true, speed);
    renders++;
    return null;
  }
  const renderer = await create(<Clock speed={1} />);
  const frame = async (now: number) => {
    const current = [...frames.values()]; frames.clear();
    await act(async () => current.forEach(callback => callback(now)));
  };
  await frame(1000);
  for (let i = 1; i <= 60; i++) {
    if (i === 31) await renderer.update(<Clock speed={2} />);
    const before = clock.timeRef.current.getTime();
    await frame(1000 + i * (1000 / 60));
    expect(clock.timeRef.current.getTime()).toBeGreaterThan(before);
  }
  expect(renders).toBeLessThanOrEqual(12); // initial + speed edit + <=10 published frames
  expect(renders).toBeGreaterThan(5);
  expect(Math.abs(clock.timeRef.current.getTime() - initial.getTime() - 1.5 * 86400000)).toBeLessThan(100);
  visibility.hidden = true;
  visibility.dispatchEvent(new Event("visibilitychange"));
  expect(frames.size).toBe(0);
  visibility.hidden = false;
  visibility.dispatchEvent(new Event("visibilitychange"));
  const before = clock.timeRef.current;
  await frame(100000);
  expect(clock.timeRef.current).toBe(before);
  // Tick + Share in the same batch: the snapshot must use the live clock and queued ticks stop.
  let paused!: Date;
  await act(async () => {
    const current = [...frames.values()]; frames.clear();
    current.forEach(callback => callback(100120));
    paused = clock.pauseClock();
    [...frames.values()].forEach(callback => callback(100240));
  });
  expect(clock.currentTime).toBe(paused);
  expect(clock.timeRef.current).toBe(paused);
  await renderer.unmount();
  expect(frames.size).toBe(0);
});
