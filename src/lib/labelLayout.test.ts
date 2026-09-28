import { describe, expect, it } from "vitest";
import { pickVisibleLabels, type LabelBox } from "./labelLayout";

function box(key: string, priority: number, x: number, y: number): LabelBox {
  return { key, priority, x, y, width: 60, height: 20 };
}

describe("pickVisibleLabels", () => {
  it("keeps labels that don't overlap", () => {
    const visible = pickVisibleLabels([box("earth", 1, 0, 0), box("mars", 2, 200, 0)]);

    expect([...visible].sort()).toEqual(["earth", "mars"]);
  });

  it("hides the lower-priority label of an overlapping pair, whatever the input order", () => {
    const sun = box("sun", 10, 100, 100);
    const mercury = box("mercury", 1, 120, 105);

    expect([...pickVisibleLabels([mercury, sun])]).toEqual(["sun"]);
    expect([...pickVisibleLabels([sun, mercury])]).toEqual(["sun"]);
  });

  it("only yields to labels that are themselves shown", () => {
    // Venus loses to the Sun, so it no longer blocks Earth, which only overlaps Venus.
    const visible = pickVisibleLabels([
      box("sun", 10, 100, 100),
      box("venus", 5, 150, 100),
      box("earth", 1, 200, 100),
    ]);

    expect([...visible].sort()).toEqual(["earth", "sun"]);
  });

  it("treats boxes closer than the padding as overlapping", () => {
    const touching = [box("a", 2, 0, 0), box("b", 1, 61, 0)];

    expect(pickVisibleLabels(touching, 2).has("b")).toBe(false);
    expect(pickVisibleLabels(touching, 0).has("b")).toBe(true);
  });
});
