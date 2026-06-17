import { describe, expect, it } from "vitest";
import {
  buildStarField,
  equatorialToScene,
  equatorialToSceneDirection,
  galacticToSceneDirection,
  SKY_RADIUS,
} from "./celestialSphere";

// Recover equatorial RA (deg) / Dec (deg) from a scene-direction vector [x, z, -y].
function sceneDirToRaDec(dir: readonly [number, number, number]) {
  const ex = dir[0];
  const ey = -dir[2];
  const ez = dir[1];
  const decDeg = (Math.asin(ez) * 180) / Math.PI;
  let raDeg = (Math.atan2(ey, ex) * 180) / Math.PI;
  if (raDeg < 0) {
    raDeg += 360;
  }
  return { raDeg, decDeg };
}

describe("celestialSphere coordinates", () => {
  it("maps RA 0h / Dec 0 to the +x scene axis (March equinox)", () => {
    const dir = equatorialToSceneDirection(0, 0);
    expect(dir[0]).toBeCloseTo(1, 6);
    expect(dir[1]).toBeCloseTo(0, 6);
    expect(dir[2]).toBeCloseTo(0, 6);
  });

  it("puts the north celestial pole (Polaris) near scene-up", () => {
    const dir = equatorialToSceneDirection(2.5302, 89.264);
    expect(dir[1]).toBeGreaterThan(0.999); // scene Y ≈ +1
    expect(Math.hypot(dir[0], dir[2])).toBeLessThan(0.02);
  });

  it("round-trips arbitrary RA/Dec through the scene mapping", () => {
    const { raDeg, decDeg } = sceneDirToRaDec(equatorialToSceneDirection(13.4199, -11.161));
    expect(raDeg).toBeCloseTo(13.4199 * 15, 4);
    expect(decDeg).toBeCloseTo(-11.161, 4);
  });

  it("places the galactic centre (l=0,b=0) at RA~266.4 deg, Dec~-28.9", () => {
    const { raDeg, decDeg } = sceneDirToRaDec(galacticToSceneDirection(0, 0));
    expect(raDeg).toBeCloseTo(266.4, 0);
    expect(decDeg).toBeCloseTo(-28.94, 1);
  });

  it("scales directions onto the sky sphere radius", () => {
    const p = equatorialToScene(6, 0, SKY_RADIUS);
    expect(Math.hypot(p[0], p[1], p[2])).toBeCloseTo(SKY_RADIUS, 3);
  });
});

describe("buildStarField", () => {
  it("returns consistent, finite, on-sphere buffers", () => {
    const field = buildStarField(SKY_RADIUS);
    const count = field.sizes.length;

    expect(count).toBeGreaterThan(1000);
    expect(field.positions.length).toBe(count * 3);
    expect(field.colors.length).toBe(count * 3);

    for (let i = 0; i < count; i += 1) {
      const r = Math.hypot(
        field.positions[i * 3],
        field.positions[i * 3 + 1],
        field.positions[i * 3 + 2],
      );
      expect(r).toBeCloseTo(SKY_RADIUS, 1);
      expect(field.sizes[i]).toBeGreaterThan(0);
    }

    expect(field.colors.every((c) => Number.isFinite(c) && c >= 0)).toBe(true);
  });
});
