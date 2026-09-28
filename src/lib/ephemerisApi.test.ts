import * as Astronomy from "astronomy-engine";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Vec3 } from "../domain/solarSystem";
import { getBodyPosition } from "./ephemeris";
import { eclipticToEquatorial, getBackendBodyPositions, getBackendBodyTrajectory } from "./ephemerisApi";

// Two real JPL Horizons samples of Ceres as the backend serves them (J2000 ecliptic, AU),
// about a quarter orbit apart.
const CERES_FROM_BACKEND = [
  { timeUtc: "2024-01-01T00:00:00Z", x: -1.095912554676036, y: -2.530434213002657, z: 0.1218180883420231 },
  { timeUtc: "2025-02-24T00:00:00Z", x: 2.488462243239908, y: -1.567470351847198, z: -0.5080453909836316 },
];
const CERES_INCLINATION_DEG = 10.59;

function stubBackendResponse(payload: unknown) {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify(payload))));
}

function cross(a: Vec3, b: Vec3): Vec3 {
  return { x: a.y * b.z - a.z * b.y, y: a.z * b.x - a.x * b.z, z: a.x * b.y - a.y * b.x };
}

function angleDeg(a: Vec3, b: Vec3) {
  const dot = a.x * b.x + a.y * b.y + a.z * b.z;
  return (Math.acos(dot / (Math.hypot(a.x, a.y, a.z) * Math.hypot(b.x, b.y, b.z))) * 180) / Math.PI;
}

// Earth's orbit normal (the ecliptic pole) in the frame the browser ephemeris and scene use.
function earthOrbitNormal(): Vec3 {
  const start = getBodyPosition("earth", new Date("2026-01-01T00:00:00Z")).positionAu;
  const later = getBodyPosition("earth", new Date("2026-04-02T00:00:00Z")).positionAu;
  return cross(start, later);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("eclipticToEquatorial", () => {
  it("matches astronomy-engine's J2000 ecliptic-to-equatorial rotation", () => {
    const ecliptic = { x: 0.3, y: -1.7, z: 0.9 };
    const time = Astronomy.MakeTime(new Date("2026-01-01T00:00:00Z"));
    const expected = Astronomy.RotateVector(
      Astronomy.Rotation_ECL_EQJ(),
      new Astronomy.Vector(ecliptic.x, ecliptic.y, ecliptic.z, time),
    );

    const actual = eclipticToEquatorial(ecliptic);

    // Same rotation; the two obliquity constants (IAU 1976 vs 2006) differ by only 0.04″.
    expect(actual.x).toBeCloseTo(expected.x, 6);
    expect(actual.y).toBeCloseTo(expected.y, 6);
    expect(actual.z).toBeCloseTo(expected.z, 6);
  });
});

describe("backend small-body vectors", () => {
  it("land in the planets' frame: Ceres's orbit sits at its real 10.59° tilt to Earth's", async () => {
    stubBackendResponse(CERES_FROM_BACKEND);

    const [first, second] = await getBackendBodyTrajectory("ceres");
    const ceresOrbitNormal = cross(first.positionAu, second.positionAu);

    // Before the conversion this came out ~23.9°, skewed by the ecliptic/equator obliquity.
    expect(angleDeg(ceresOrbitNormal, earthOrbitNormal())).toBeCloseTo(CERES_INCLINATION_DEG, 1);
  });

  it("converts live positions too", async () => {
    stubBackendResponse([{ body: "ceres", source: "JPL_HORIZONS", ...CERES_FROM_BACKEND[0] }]);

    const [position] = await getBackendBodyPositions(["ceres"], new Date("2024-01-01T00:00:00Z"));

    expect(position.body).toBe("ceres");
    expect(position.positionAu).toEqual(eclipticToEquatorial(CERES_FROM_BACKEND[0]));
  });
});
