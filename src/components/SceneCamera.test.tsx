import { useThree, type RootState } from "@react-three/fiber";
import ReactThreeTestRenderer from "@react-three/test-renderer";
import * as THREE from "three";
import { describe, expect, it } from "vitest";
import type { OrbitControls as OrbitControlsImpl } from "three-stdlib";
import type { BodyId, BodyPosition } from "../domain/solarSystem";
import { getBodyPositions } from "../lib/ephemeris";
import { SceneCamera } from "./SolarSystemScene";

// Positions on the equinox (x) axis map to scene x = AU * 3.2, which keeps the expected numbers
// readable: bodies at 1 and 3 AU frame the midpoint (6.4, 0, 0).
function onXAxis(earthAu: number, otherAu: number, other: BodyId = "mars"): BodyPosition[] {
  return [
    { body: "earth", positionAu: { x: earthAu, y: 0, z: 0 } },
    { body: other, positionAu: { x: otherAu, y: 0, z: 0 } },
  ];
}

const toScene = ({ x, y, z }: { x: number; y: number; z: number }) => new THREE.Vector3(x * 3.2, z * 3.2, -y * 3.2);

// The production camera (48° vertical FOV) on a canvas of the given size.
async function mountSceneCamera(
  highlightedBodies: BodyId[],
  positions: BodyPosition[],
  canvas = { width: 1280, height: 800 },
) {
  let getState: () => RootState = () => {
    throw new Error("scene not mounted");
  };
  const StoreProbe = () => {
    getState = useThree((state) => state.get);
    return null;
  };
  const scene = (bodies: BodyId[], bodyPositions: BodyPosition[]) => (
    <>
      <SceneCamera highlightedBodies={bodies} positions={bodyPositions} />
      <StoreProbe />
    </>
  );

  const renderer = await ReactThreeTestRenderer.create(scene(highlightedBodies, positions), {
    ...canvas,
    camera: { fov: 48 },
  });
  // OrbitControls.update() round-trips through spherical coordinates; ignore float noise.
  const rounded = (vector: number[]) => vector.map((value) => Math.round(value * 1e6) / 1e6 + 0);
  return {
    rerender: (bodies: BodyId[], bodyPositions: BodyPosition[]) => renderer.update(scene(bodies, bodyPositions)),
    camera: () => rounded(getState().camera.position.toArray()),
    target: () => rounded((getState().controls as OrbitControlsImpl).target.toArray()),
    // Where a body lands in normalized device coordinates; the viewport spans [-1, 1].
    project: (position: BodyPosition) => {
      const camera = getState().camera;
      camera.updateMatrixWorld();
      return toScene(position.positionAu).project(camera);
    },
  };
}

describe("SceneCamera", () => {
  it("frames a newly loaded event pair around its midpoint", async () => {
    const positions = onXAxis(1, 3);
    const view = await mountSceneCamera(["earth", "mars"], positions);

    expect(view.target()).toEqual([6.4, 0, 0]);
    for (const position of positions) {
      const onScreen = view.project(position);
      expect(Math.max(Math.abs(onScreen.x), Math.abs(onScreen.y))).toBeLessThan(1);
    }
  });

  it("keeps both event bodies on screen in a narrow window", async () => {
    // Earth and Neptune at their 2026-09-25 closest approach, on a 600×800 canvas: the old fixed
    // camera offsets put Earth at x ≈ -1.54, off the left edge.
    const positions = getBodyPositions(["earth", "neptune"], new Date("2026-09-25T00:00:00Z"));
    const view = await mountSceneCamera(["earth", "neptune"], positions, { width: 600, height: 800 });

    for (const position of positions) {
      const onScreen = view.project(position);
      expect(Math.abs(onScreen.x), `${position.body} x`).toBeLessThan(1);
      expect(Math.abs(onScreen.y), `${position.body} y`).toBeLessThan(1);
    }
  });

  it("leaves the camera to the user while playback moves the framed bodies", async () => {
    const event: BodyId[] = ["earth", "mars"];
    const view = await mountSceneCamera(event, onXAxis(1, 3));
    const framed = view.camera();

    await view.rerender(event, onXAxis(1.2, 3.4));

    expect(view.target()).toEqual([6.4, 0, 0]);
    expect(view.camera()).toEqual(framed);
  });

  it("frames each newly loaded event, even for the same bodies", async () => {
    const view = await mountSceneCamera(["earth", "mars"], onXAxis(1, 3));

    await view.rerender(["earth", "mars"], onXAxis(-1, -3));

    expect(view.target()).toEqual([-6.4, 0, 0]);
  });

  it("frames the pair once a still-loading body's position arrives", async () => {
    const event: BodyId[] = ["earth", "ceres"];
    const view = await mountSceneCamera(event, onXAxis(1, 3, "ceres").slice(0, 1));
    expect(view.target()).toEqual([0, 0, 0]);

    await view.rerender(event, onXAxis(1, 3, "ceres"));

    expect(view.target()).toEqual([6.4, 0, 0]);
  });
});
