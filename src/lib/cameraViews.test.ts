import * as THREE from "three";
import { describe, expect, it } from "vitest";
import { getBodyPosition } from "./ephemeris";
import { pairView, type CameraView, type Lens, type ScenePoint } from "./cameraViews";

const AU_TO_SCENE_UNITS = 3.2;
const scenePointOf = ({ x, y, z }: { x: number; y: number; z: number }): ScenePoint => [
  x * AU_TO_SCENE_UNITS,
  z * AU_TO_SCENE_UNITS,
  -y * AU_TO_SCENE_UNITS,
];

// Where a point lands in normalized device coordinates; the viewport spans [-1, 1] on each axis.
function project(view: CameraView, lens: Lens, point: ScenePoint) {
  const camera = new THREE.PerspectiveCamera(lens.fov, lens.aspect, 0.1, 1000);
  camera.position.copy(view.position);
  camera.lookAt(view.target);
  camera.updateMatrixWorld();
  return new THREE.Vector3(...point).project(camera);
}

function expectOnScreen(view: CameraView, lens: Lens, points: ScenePoint[]) {
  for (const point of points) {
    const ndc = project(view, lens, point);
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
    const earth = scenePointOf(getBodyPosition("earth", time).positionAu);
    const neptune = scenePointOf(getBodyPosition("neptune", time).positionAu);

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
    const view = pairView([3.2, 0, 0], [3.4, 0, 0], 0.4, WIDE);

    expect(view.target.toArray()).toEqual([3.3, 0, 0]);
    expect(view.position.distanceTo(view.target)).toBeCloseTo(14, 9);
  });
});
