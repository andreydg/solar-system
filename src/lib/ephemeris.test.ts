import { describe, expect, it } from "vitest";
import { BODY_BY_ID, type BodyId, type Vec3 } from "../domain/solarSystem";
import { getBodyPosition, getNorthPole } from "./ephemeris";
import { addDays } from "./timeUtils";

const EPOCH = new Date("2026-01-01T00:00:00Z");

function cross(a: Vec3, b: Vec3): Vec3 {
  return { x: a.y * b.z - a.z * b.y, y: a.z * b.x - a.x * b.z, z: a.x * b.y - a.y * b.x };
}

function angleDeg(a: Vec3, b: Vec3) {
  const dot = a.x * b.x + a.y * b.y + a.z * b.z;
  const lengths = Math.hypot(a.x, a.y, a.z) * Math.hypot(b.x, b.y, b.z);
  return (Math.acos(dot / lengths) * 180) / Math.PI;
}

// Orbit normal from two positions a quarter orbit apart (both lie in the orbital plane).
function orbitNormal(body: BodyId): Vec3 {
  const start = getBodyPosition(body, EPOCH).positionAu;
  const later = getBodyPosition(body, addDays(EPOCH, BODY_BY_ID[body].orbitDays / 4)).positionAu;
  return cross(start, later);
}

describe("getNorthPole", () => {
  // Angle between the IAU north pole and the orbit normal. That is the published obliquity,
  // except for retrograde rotators, where IAU north is the pole opposite the spin vector
  // (Venus 177.36° → 2.64°, Uranus 97.77° → 82.23°).
  it.each<[BodyId, number]>([
    ["mercury", 0.03],
    ["venus", 2.64],
    ["earth", 23.44],
    ["mars", 25.19],
    ["jupiter", 3.13],
    ["saturn", 26.73],
    ["uranus", 82.23],
    ["neptune", 28.32],
  ])("%s pole sits at its real tilt to its orbit, in the positions' frame", (body, expectedDeg) => {
    const pole = getNorthPole(body, EPOCH);
    expect(pole).not.toBeNull();
    expect(angleDeg(pole!, orbitNormal(body))).toBeCloseTo(expectedDeg, 0);
  });

  // How far Earth sits above or below Saturn's equatorial (ring) plane, i.e. how open the
  // rings look from here. The scene draws the rings perpendicular to this same pole.
  function saturnRingOpeningDeg(time: Date) {
    const pole = getNorthPole("saturn", time)!;
    const saturn = getBodyPosition("saturn", time).positionAu;
    const earth = getBodyPosition("earth", time).positionAu;
    return 90 - angleDeg(pole, { x: earth.x - saturn.x, y: earth.y - saturn.y, z: earth.z - saturn.z });
  }

  it("has Saturn's rings edge-on to Earth at the 2025-03-23 ring-plane crossing", () => {
    expect(Math.abs(saturnRingOpeningDeg(new Date("2025-03-23T00:00:00Z")))).toBeLessThan(0.5);
    expect(Math.abs(saturnRingOpeningDeg(new Date("2032-06-01T00:00:00Z")))).toBeGreaterThan(25);
  });

  it("returns a unit vector", () => {
    const pole = getNorthPole("saturn", EPOCH)!;
    expect(Math.hypot(pole.x, pole.y, pole.z)).toBeCloseTo(1, 10);
  });

  it("returns null for bodies the browser ephemeris does not model", () => {
    expect(getNorthPole("halley", EPOCH)).toBeNull();
  });
});
