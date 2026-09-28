import { useThree, type RootState } from "@react-three/fiber";
import ReactThreeTestRenderer from "@react-three/test-renderer";
import { describe, expect, it } from "vitest";
import type { OrbitControls as OrbitControlsImpl } from "three-stdlib";
import type { BodyId, BodyPosition } from "../domain/solarSystem";
import { SceneCamera } from "./SolarSystemScene";

// Positions on the equinox (x) axis map to scene x = AU * 3.2, which keeps the expected numbers
// readable: bodies at 1 and 3 AU frame the midpoint (6.4, 0, 0) with the camera 10 above and in
// front of it.
function onXAxis(earthAu: number, otherAu: number, other: BodyId = "mars"): BodyPosition[] {
  return [
    { body: "earth", positionAu: { x: earthAu, y: 0, z: 0 } },
    { body: other, positionAu: { x: otherAu, y: 0, z: 0 } },
  ];
}

async function mountSceneCamera(highlightedBodies: BodyId[], positions: BodyPosition[]) {
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

  const renderer = await ReactThreeTestRenderer.create(scene(highlightedBodies, positions));
  // OrbitControls.update() round-trips through spherical coordinates; ignore float noise.
  const rounded = (vector: number[]) => vector.map((value) => Math.round(value * 1e6) / 1e6 + 0);
  return {
    rerender: (bodies: BodyId[], bodyPositions: BodyPosition[]) => renderer.update(scene(bodies, bodyPositions)),
    camera: () => rounded(getState().camera.position.toArray()),
    target: () => rounded((getState().controls as OrbitControlsImpl).target.toArray()),
  };
}

describe("SceneCamera", () => {
  it("frames a newly loaded event pair", async () => {
    const view = await mountSceneCamera(["earth", "mars"], onXAxis(1, 3));

    expect(view.target()).toEqual([6.4, 0, 0]);
    expect(view.camera()).toEqual([6.4, 10, 10]);
  });

  it("leaves the camera to the user while playback moves the framed bodies", async () => {
    const event: BodyId[] = ["earth", "mars"];
    const view = await mountSceneCamera(event, onXAxis(1, 3));

    await view.rerender(event, onXAxis(1.2, 3.4));

    expect(view.target()).toEqual([6.4, 0, 0]);
    expect(view.camera()).toEqual([6.4, 10, 10]);
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
