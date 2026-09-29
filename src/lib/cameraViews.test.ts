import * as THREE from "three";
import { describe, expect, it } from "vitest";
import {
  CLOSE_UP_RADII,
  closeUpView,
  easeInOutCubic,
  interpolateView,
  pairView,
  type CameraView,
  type Lens,
} from "./cameraViews";
import { getBodyPosition } from "./ephemeris";
import { toScenePoint, type ScenePoint } from "./sceneSpace";

function view(position: ScenePoint, target: ScenePoint): CameraView {
  return { position: new THREE.Vector3(...position), target: new THREE.Vector3(...target) };
}

// Where a point lands in normalized device coordinates; the viewport spans [-1, 1] on each axis.
function project(cameraView: CameraView, lens: Lens, point: ScenePoint) {
  const camera = new THREE.PerspectiveCamera(lens.fov, lens.aspect, 0.1, 1000);
  camera.position.copy(cameraView.position);
  camera.lookAt(cameraView.target);
  camera.updateMatrixWorld();
  return new THREE.Vector3(...point).project(camera);
}

function expectOnScreen(cameraView: CameraView, lens: Lens, points: ScenePoint[]) {
  for (const point of points) {
    const ndc = project(cameraView, lens, point);
    expect(Math.abs(ndc.x), `x of ${point}`).toBeLessThan(0.95);
    expect(Math.abs(ndc.y), `y of ${point}`).toBeLessThan(0.95);
    expect(ndc.z, `depth of ${point}`).toBeLessThan(1); // in front of the camera
  }
}

const NARROW: Lens = { fov: 48, aspect: 600 / 800 }; // narrow desktop window beside the 420px panel
const PHONE: Lens = { fov: 48, aspect: 390 / 520 };
const WIDE: Lens = { fov: 48, aspect: 1600 / 900 };

describe("pairView", () => {
  it("keeps Earth and Neptune on screen in a narrow window (closest approach, 2026-09-25)", () => {
    const time = new Date("2026-09-25T00:00:00Z");
    const earth = toScenePoint(getBodyPosition("earth", time).positionAu);
    const neptune = toScenePoint(getBodyPosition("neptune", time).positionAu);

    for (const lens of [NARROW, PHONE, WIDE]) {
      expectOnScreen(pairView(earth, neptune, 0.4, lens), lens, [earth, neptune]);
    }
  });

  it("fits pairs spread sideways, in depth, and vertically", () => {
    const pairs: Array<[ScenePoint, ScenePoint]> = [
      [[-40, 0, 0], [40, 0, 0]],
      [[0, 0, -40], [0, 0, 40]],
      [[0, -30, 0], [0, 30, 0]],
      [[-25, 10, 30], [35, -5, -20]],
    ];
    for (const lens of [NARROW, PHONE, WIDE]) {
      for (const [a, b] of pairs) {
        expectOnScreen(pairView(a, b, 0.4, lens), lens, [a, b]);
      }
    }
  });

  it("looks at the midpoint, and doesn't zoom in past the minimum distance for close pairs", () => {
    const pair = pairView([3.2, 0, 0], [3.4, 0, 0], 0.4, WIDE);

    expect(pair.target.toArray()).toEqual([3.3, 0, 0]);
    expect(pair.position.distanceTo(pair.target)).toBeCloseTo(14, 9);
  });
});

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
  const body: ScenePoint = [10, 0, 0];
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

describe("easeInOutCubic", () => {
  it("eases from 0 to 1 symmetrically", () => {
    expect(easeInOutCubic(0)).toBe(0);
    expect(easeInOutCubic(0.5)).toBe(0.5);
    expect(easeInOutCubic(1)).toBe(1);
    expect(easeInOutCubic(0.25) + easeInOutCubic(0.75)).toBeCloseTo(1, 12);
  });
});
