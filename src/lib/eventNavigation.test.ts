import { expect, it } from "vitest";
import { createEventNavigation, EVENT_HISTORY_LIMIT, recordEventNavigation, stepEventNavigation } from "./eventNavigation";

it("starts empty and only replays a visited event", () => {
  const empty = createEventNavigation<string>();
  expect(stepEventNavigation(empty, "next")).toBeNull();
  expect(stepEventNavigation(empty, "prev")).toBeNull();
  const first = recordEventNavigation(empty, "first", "next");
  expect(first).toEqual({ previous: [], current: "first", next: [] });
  expect(stepEventNavigation(first, "next")).toBeNull();
  expect(stepEventNavigation(first, "prev")).toBeNull();
});

it("Next undoes Prev and Prev undoes Next with exact snapshot identity", () => {
  const newer = { id: "newer", time: new Date("2016-12-06T11:59:59Z"), status: "provisional" };
  const older = { id: "older", time: new Date("2016-11-30T20:02:54Z"), status: "validated" };
  const first = createEventNavigation(newer);
  Object.freeze(first.previous); Object.freeze(first.next); Object.freeze(first);
  const previous = recordEventNavigation(first, older, "prev");
  expect(first.current).toBe(newer);
  expect(previous.current).toBe(older);
  const undone = stepEventNavigation(previous, "next")!;
  expect(undone.current).toBe(newer);
  expect(stepEventNavigation(undone, "prev")!.current).toBe(older);
  const later = recordEventNavigation(undone, { ...newer, id: "later" }, "next");
  expect(stepEventNavigation(later, "prev")!.current).toBe(newer);
});

it("replays several successful searches in order in both directions", () => {
  let history = createEventNavigation(3);
  history = recordEventNavigation(history, 2, "prev");
  history = recordEventNavigation(history, 1, "prev");
  for (const expected of [2, 3]) {
    history = stepEventNavigation(history, "next")!;
    expect(history.current).toBe(expected);
  }
  history = recordEventNavigation(history, 4, "next");
  for (const expected of [3, 2, 1]) {
    history = stepEventNavigation(history, "prev")!;
    expect(history.current).toBe(expected);
  }
  expect(stepEventNavigation(history, "prev")).toBeNull();
});

it.each(["next", "prev"] as const)("bounds total retained snapshots for %s searches and replay", direction => {
  let history = createEventNavigation(0);
  for (let i = 1; i <= 250; i++) history = recordEventNavigation(history, i, direction);
  expect(history.previous.length + history.next.length + 1).toBe(EVENT_HISTORY_LIMIT);
  expect(EVENT_HISTORY_LIMIT).toBe(100);
  const reverse = direction === "next" ? "prev" : "next";
  for (let i = 249; i >= 151; i--) {
    history = stepEventNavigation(history, reverse)!;
    expect(history.current).toBe(i);
    expect(history.previous.length + history.next.length + 1).toBe(EVENT_HISTORY_LIMIT);
  }
  expect(stepEventNavigation(history, reverse)).toBeNull();
});

it("starts a clean history, optionally seeded with a restored view's event", () => {
  const restored = createEventNavigation("restored");
  expect(restored.previous).toEqual([]);
  expect(restored.next).toEqual([]);
  const found = recordEventNavigation(restored, "found", "next");
  expect(stepEventNavigation(found, "prev")!.current).toBe("restored");
  expect(createEventNavigation().current).toBeNull();
});
