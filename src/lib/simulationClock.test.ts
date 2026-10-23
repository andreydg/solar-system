import { expect, it } from "vitest";
import { advanceClock } from "./useSimulationClock";

it("does not catch up minutes of hidden-tab time or leave the supported range", () => {
  const time = new Date("2026-01-01T00:00:00Z");
  expect(advanceClock(time, 60_000, 1).getTime() - time.getTime()).toBe(0.25 * 86400000);
  expect(advanceClock(time, -50, 1)).toEqual(time);
  expect(advanceClock(new Date("2999-12-31T23:59:58Z"), 250, 365).getUTCFullYear()).toBe(2999);
});
