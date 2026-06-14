import { describe, expect, it } from "vitest";
import {
  formatEventTypeLabel,
  getEventTargetOptions,
  isValidEventPair,
  locksEarthAsBodyA,
  requiresEarth,
} from "./eventTypes";

describe("eventTypes", () => {
  describe("isValidEventPair", () => {
    it("rejects a body paired with itself", () => {
      expect(isValidEventPair("closestApproach", "earth", "earth")).toBe(false);
      expect(isValidEventPair("opposition", "mars", "mars")).toBe(false);
    });

    it("allows any two distinct bodies for unanchored types", () => {
      expect(isValidEventPair("closestApproach", "mars", "jupiter")).toBe(true);
      expect(isValidEventPair("farthestApproach", "venus", "saturn")).toBe(true);
    });

    it("requires Earth as bodyA for anchored types", () => {
      expect(isValidEventPair("opposition", "mars", "earth")).toBe(false);
      expect(isValidEventPair("opposition", "earth", "mars")).toBe(true);
    });

    it("restricts opposition (earthOuter) to outer targets", () => {
      expect(isValidEventPair("opposition", "earth", "mercury")).toBe(false);
      expect(isValidEventPair("opposition", "earth", "venus")).toBe(false);
      expect(isValidEventPair("opposition", "earth", "jupiter")).toBe(true);
    });

    it("restricts greatest elongation (earthInner) to Mercury and Venus", () => {
      expect(isValidEventPair("greatestElongation", "earth", "mercury")).toBe(true);
      expect(isValidEventPair("greatestElongation", "earth", "venus")).toBe(true);
      expect(isValidEventPair("greatestElongation", "earth", "mars")).toBe(false);
    });

    it("restricts perihelion (smallBody) to catalog small bodies", () => {
      expect(isValidEventPair("perihelion", "earth", "ceres")).toBe(true);
      expect(isValidEventPair("perihelion", "earth", "halley")).toBe(true);
      expect(isValidEventPair("perihelion", "earth", "mars")).toBe(false);
    });
  });

  describe("locksEarthAsBodyA / requiresEarth", () => {
    it("are false for unanchored (pairing: any) types", () => {
      expect(locksEarthAsBodyA("closestApproach")).toBe(false);
      expect(requiresEarth("farthestApproach")).toBe(false);
    });

    it("are true for Earth-anchored types", () => {
      expect(locksEarthAsBodyA("opposition")).toBe(true);
      expect(requiresEarth("conjunction")).toBe(true);
    });
  });

  describe("getEventTargetOptions", () => {
    it("returns inner planets for greatest elongation", () => {
      expect(getEventTargetOptions("greatestElongation")).toEqual(["mercury", "venus"]);
    });

    it("excludes inner planets and Earth for opposition", () => {
      const options = getEventTargetOptions("opposition");
      expect(options).not.toContain("earth");
      expect(options).not.toContain("mercury");
      expect(options).not.toContain("venus");
      expect(options).toContain("jupiter");
    });

    it("returns the small-body set for perihelion", () => {
      expect(getEventTargetOptions("perihelion")).toEqual(["ceres", "vesta", "encke", "halley"]);
    });
  });

  describe("formatEventTypeLabel", () => {
    it("lowercases the display label", () => {
      expect(formatEventTypeLabel("closestApproach")).toBe("closest approach");
      expect(formatEventTypeLabel("greatestElongation")).toBe("greatest elongation");
    });
  });
});
