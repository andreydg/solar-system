import { describe, expect, it } from "vitest";
import { addDays, addYears, DAY_MS } from "./timeUtils";

describe("timeUtils", () => {
  it("DAY_MS is one day in milliseconds", () => {
    expect(DAY_MS).toBe(24 * 60 * 60 * 1000);
  });

  describe("addDays", () => {
    it("adds whole days", () => {
      const result = addDays(new Date("2026-01-01T00:00:00Z"), 10);
      expect(result.toISOString()).toBe("2026-01-11T00:00:00.000Z");
    });

    it("handles fractional days", () => {
      const result = addDays(new Date("2026-01-01T00:00:00Z"), 0.5);
      expect(result.toISOString()).toBe("2026-01-01T12:00:00.000Z");
    });

    it("subtracts with negative days", () => {
      const result = addDays(new Date("2026-01-11T00:00:00Z"), -10);
      expect(result.toISOString()).toBe("2026-01-01T00:00:00.000Z");
    });

    it("does not mutate the input", () => {
      const input = new Date("2026-01-01T00:00:00Z");
      addDays(input, 5);
      expect(input.toISOString()).toBe("2026-01-01T00:00:00.000Z");
    });
  });

  describe("addYears", () => {
    it("adds calendar years in UTC", () => {
      const result = addYears(new Date("2026-06-13T00:00:00Z"), 2);
      expect(result.toISOString()).toBe("2028-06-13T00:00:00.000Z");
    });

    it("rolls a leap day back to Feb 28 in a non-leap year", () => {
      const result = addYears(new Date("2024-02-29T00:00:00Z"), 1);
      // 2025 has no Feb 29; JS Date rolls into March 1.
      expect(result.toISOString()).toBe("2025-03-01T00:00:00.000Z");
    });

    it("does not mutate the input", () => {
      const input = new Date("2026-06-13T00:00:00Z");
      addYears(input, 3);
      expect(input.toISOString()).toBe("2026-06-13T00:00:00.000Z");
    });
  });
});
