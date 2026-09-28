import { useThree, type RootState } from "@react-three/fiber";
import ReactThreeTestRenderer from "@react-three/test-renderer";
import { describe, expect, it } from "vitest";
import type { OrbitControls as OrbitControlsImpl } from "three-stdlib";
import type { BodyId, BodyPosition } from "../domain/solarSystem";
import { CLOSE_UP_RADII, OVERVIEW_CAMERA_POSITION } from "../lib/cameraViews";
import { getVisualRadius } from "../lib/sceneSpace";
import SceneCamera from "./SceneCamera";

type Props = { focusedBody: BodyId | null; highlightedBodies: BodyId[]; positions: BodyPosition[] };

const NO_EVENT: BodyId[] = [];
const EARTH_RADIUS = getVisualRadius(6371);

// Positions on the equinox (x) axis map to scene x = AU * 3.2, which keeps expected numbers
// readable: bodies at 1 and 3 AU frame the midpoint (6.4, 0, 0) with the camera 10 above and in
// front of it.
function onXAxis(earthAu: number, otherAu: number, other: BodyId = "mars"): BodyPosition[] {
  return [
    { body: "earth", positionAu: { x: earthAu, y: 0, z: 0 } },
    { body: other, positionAu: { x: otherAu, y: 0, z: 0 } },
  ];
}

async function mountSceneCamera(initial: Props) {
  let getState: () => RootState = () => {
    throw new Error("scene not mounted");
  };
  const StoreProbe = () => {
    getState = useThree((state) => state.get);
    return null;
  };
  const scene = (props: Props) => (
    <>
      <SceneCamera {...props} />
      <StoreProbe />
    </>
  );
  const renderer = await ReactThreeTestRenderer.create(scene(initial));
  // Camera flights take under two seconds; run enough frames for any of them to land.
  const settle = () => renderer.advanceFrames(150, 1 / 60);
  await settle();

  // OrbitControls.update() round-trips through spherical coordinates; ignore float noise.
  const rounded = (vector: number[]) => vector.map((value) => Math.round(value * 1e6) / 1e6 + 0);
  const controls = () => getState().controls as OrbitControlsImpl;
  return {
    rerender: async (props: Props) => {
      await renderer.update(scene(props));
      await settle();
    },
    camera: () => rounded(getState().camera.position.toArray()),
    target: () => rounded(controls().target.toArray()),
    distanceToTarget: () => getState().camera.position.distanceTo(controls().target),
    minDistance: () => controls().minDistance,
  };
}

describe("SceneCamera event framing", () => {
  it("frames a newly loaded event pair", async () => {
    const view = await mountSceneCamera({ focusedBody: null, highlightedBodies: ["earth", "mars"], positions: onXAxis(1, 3) });

    expect(view.target()).toEqual([6.4, 0, 0]);
    expect(view.camera()).toEqual([6.4, 10, 10]);
  });

  it("leaves the camera to the user while playback moves the framed bodies", async () => {
    const event: BodyId[] = ["earth", "mars"];
    const view = await mountSceneCamera({ focusedBody: null, highlightedBodies: event, positions: onXAxis(1, 3) });

    await view.rerender({ focusedBody: null, highlightedBodies: event, positions: onXAxis(1.2, 3.4) });

    expect(view.target()).toEqual([6.4, 0, 0]);
    expect(view.camera()).toEqual([6.4, 10, 10]);
  });

  it("frames each newly loaded event, even for the same bodies", async () => {
    const view = await mountSceneCamera({ focusedBody: null, highlightedBodies: ["earth", "mars"], positions: onXAxis(1, 3) });

    await view.rerender({ focusedBody: null, highlightedBodies: ["earth", "mars"], positions: onXAxis(-1, -3) });

    expect(view.target()).toEqual([-6.4, 0, 0]);
  });

  it("frames the pair once a still-loading body's position arrives", async () => {
    const event: BodyId[] = ["earth", "ceres"];
    const view = await mountSceneCamera({ focusedBody: null, highlightedBodies: event, positions: onXAxis(1, 3, "ceres").slice(0, 1) });
    expect(view.target()).toEqual([0, 0, 0]);

    await view.rerender({ focusedBody: null, highlightedBodies: event, positions: onXAxis(1, 3, "ceres") });

    expect(view.target()).toEqual([6.4, 0, 0]);
  });
});

describe("SceneCamera body focus", () => {
  it("flies to a focused body and lets you zoom in close to it", async () => {
    const view = await mountSceneCamera({ focusedBody: null, highlightedBodies: NO_EVENT, positions: onXAxis(1, 3) });

    await view.rerender({ focusedBody: "earth", highlightedBodies: NO_EVENT, positions: onXAxis(1, 3) });

    expect(view.target()).toEqual([3.2, 0, 0]);
    expect(view.distanceToTarget()).toBeCloseTo(EARTH_RADIUS * CLOSE_UP_RADII, 6);
    expect(view.minDistance()).toBeLessThan(EARTH_RADIUS * 2);
  });

  it("follows the focused body as time moves it, keeping the same view of it", async () => {
    const view = await mountSceneCamera({ focusedBody: "earth", highlightedBodies: NO_EVENT, positions: onXAxis(1, 3) });
    const cameraBefore = view.camera();

    await view.rerender({ focusedBody: "earth", highlightedBodies: NO_EVENT, positions: onXAxis(1.5, 3) });

    expect(view.target()).toEqual([4.8, 0, 0]);
    expect(view.camera()).toEqual([cameraBefore[0] + 1.6, cameraBefore[1], cameraBefore[2]]);
  });

  it("returns to the overview, with the overview zoom limit, when focus is cleared", async () => {
    const view = await mountSceneCamera({ focusedBody: "earth", highlightedBodies: NO_EVENT, positions: onXAxis(1, 3) });

    await view.rerender({ focusedBody: null, highlightedBodies: NO_EVENT, positions: onXAxis(1, 3) });

    expect(view.target()).toEqual([0, 0, 0]);
    expect(view.camera()).toEqual(OVERVIEW_CAMERA_POSITION);
    expect(view.minDistance()).toBe(4);
  });

  it("stops following when a new event loads and frames the event instead", async () => {
    const view = await mountSceneCamera({ focusedBody: "earth", highlightedBodies: NO_EVENT, positions: onXAxis(1, 3) });

    // App clears the focus in the same update that loads the event.
    const event: BodyId[] = ["earth", "mars"];
    await view.rerender({ focusedBody: null, highlightedBodies: event, positions: onXAxis(1, 3) });
    expect(view.target()).toEqual([6.4, 0, 0]);

    // No longer following Earth: playback moving it leaves the camera where the event put it.
    await view.rerender({ focusedBody: null, highlightedBodies: event, positions: onXAxis(1.5, 3) });
    expect(view.target()).toEqual([6.4, 0, 0]);
  });
});
