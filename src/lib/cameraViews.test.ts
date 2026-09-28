import * as THREE from "three";
import { describe, expect, it } from "vitest";
import { CLOSE_UP_RADII, closeUpView, easeInOutCubic, interpolateView, pairView, type CameraView } from "./cameraViews";

function view(position: [number, number, number], target: [number, number, number]): CameraView {
  return { position: new THREE.Vector3(...position), target: new THREE.Vector3(...target) };
}

describe("interpolateView", () => {
  const from = view([0, 0, 100], [0, 0, 0]);
  const to = view([11, 0, 0], [10, 0, 0]);

  it("starts at the first view and ends at the second", () => {
    const start = interpolateView(from, to, 0);
    const end = interpolateView(from, to, 1);

    expect(start.position.distanceTo(from.position)).toBeLessThan(1e-9);
    expect(end.position.distanceTo(to.position)).toBeLessThan(1e-9);
    expect(end.target.distanceTo(to.target)).toBeLessThan(1e-9);
  });

  it("zooms at a steady rate: halfway through, the distance is the geometric mean", () => {
    const halfway = interpolateView(from, to, 0.5);

    // 100 → 1 units from the target: a straight lerp would still be ~50 away; log-space gives 10.
    expect(halfway.position.distanceTo(halfway.target)).toBeCloseTo(10, 9);
  });
});

describe("closeUpView", () => {
  const body: [number, number, number] = [10, 0, 0];
  const radius = 0.3;

  it("sits a fixed number of rendered radii from the body", () => {
    const closeUp = closeUpView(body, new THREE.Vector3(10, 5, 30), radius);

    expect(closeUp.target.toArray()).toEqual(body);
    expect(closeUp.position.distanceTo(closeUp.target)).toBeCloseTo(radius * CLOSE_UP_RADII, 9);
  });

  it("looks at the sunlit side, from the side the camera is already on", () => {
    for (const cameraZ of [30, -30]) {
      const closeUp = closeUpView(body, new THREE.Vector3(10, 5, cameraZ), radius);
      const lookFrom = closeUp.position.clone().sub(closeUp.target);

      expect(lookFrom.x).toBeLessThan(0); // toward the Sun at the origin
      expect(Math.sign(lookFrom.z)).toBe(Math.sign(cameraZ));
    }
  });
});

describe("pairView", () => {
  it("targets the midpoint from at least 10 units above and in front", () => {
    const pair = pairView([2, 0, 0], [4, 0, 0]);

    expect(pair.target.toArray()).toEqual([3, 0, 0]);
    expect(pair.position.toArray()).toEqual([3, 10, 10]);
  });
});

describe("easeInOutCubic", () => {
  it("eases from 0 to 1 symmetrically", () => {
    expect(easeInOutCubic(0)).toBe(0);
    expect(easeInOutCubic(0.5)).toBe(0.5);
    expect(easeInOutCubic(1)).toBe(1);
    expect(easeInOutCubic(0.25) + easeInOutCubic(0.75)).toBeCloseTo(1, 12);
  });
});
