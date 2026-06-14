import { describe, expect, it } from "vitest";
import { AU_KM } from "../domain/solarSystem";
import { formatDate, formatDistance, formatEventTitle, formatSource } from "./formatters";

describe("formatters", () => {
  describe("formatDate", () => {
    it("formats in UTC regardless of host timezone", () => {
      const formatted = formatDate(new Date("2027-01-15T18:30:00Z"));
      expect(formatted).toMatch(/Jan 15, 2027/);
      expect(formatted).toMatch(/6:30/);
    });
  });

  describe("formatDistance", () => {
    it("renders sub-AU distances as a rounded integer", () => {
      // Strip locale grouping separators so the assertion is locale-independent.
      const digits = formatDistance(12345.6).replace(/\D/g, "");
      expect(digits).toBe("12346");
    });

    it("renders distances beyond 1 AU in millions of km", () => {
      expect(formatDistance(AU_KM * 2)).toBe("299.2 million");
    });
  });

  describe("formatSource", () => {
    it("maps known source codes to short labels", () => {
      expect(formatSource("JPL_HORIZONS")).toBe("JPL");
      expect(formatSource("VSOP87A_APPROX")).toBe("VSOP87A");
    });

    it("humanizes unknown sources by replacing underscores", () => {
      expect(formatSource("SOME_OTHER_SOURCE")).toBe("SOME OTHER SOURCE");
    });
  });

  describe("formatEventTitle", () => {
    it("names the non-Earth target for perihelion", () => {
      const title = formatEventTitle({ bodyA: "earth", bodyB: "ceres", type: "perihelion" });
      expect(title).toBe("perihelion: Ceres");
    });

    it("appends (from Earth) for Earth-locked event types", () => {
      const title = formatEventTitle({ bodyA: "earth", bodyB: "mars", type: "opposition" });
      expect(title).toBe("opposition: Mars (from Earth)");
    });

    it("renders a body-to-body title for unanchored event types", () => {
      const title = formatEventTitle({ bodyA: "earth", bodyB: "mars", type: "closestApproach" });
      expect(title).toBe("closest approach: Earth to Mars");
    });

    it("falls back to the provided type when the event has none", () => {
      const title = formatEventTitle({ bodyA: "earth", bodyB: "mars" }, "opposition");
      expect(title).toBe("opposition: Mars (from Earth)");
    });

    it("returns a generic label when no type is available", () => {
      expect(formatEventTitle({ bodyA: "earth", bodyB: "mars" })).toBe("Event");
    });
  });
});
