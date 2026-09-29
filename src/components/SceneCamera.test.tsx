import { useThree, type RootState } from "@react-three/fiber";
import ReactThreeTestRenderer from "@react-three/test-renderer";
import * as THREE from "three";
import { describe, expect, it } from "vitest";
import type { OrbitControls as OrbitControlsImpl } from "three-stdlib";
import type { BodyId, BodyPosition } from "../domain/solarSystem";
import { OVERVIEW_CAMERA_POSITION } from "../lib/cameraViews";
import { getBodyPositions } from "../lib/ephemeris";
import { getVisualRadius, toScenePoint } from "../lib/sceneSpace";
import SceneCamera from "./SceneCamera";

type Props = { focusedBody: BodyId | null; highlightedBodies: BodyId[]; positions: BodyPosition[] };

const NO_EVENT: BodyId[] = [];
const EARTH_RADIUS = getVisualRadius(6371);
const MARS_RADIUS = getVisualRadius(3389.5);

// Positions on the equinox (x) axis map to scene x = AU * 3.2, which keeps expected numbers
// readable: bodies at 1 and 3 AU frame the midpoint (6.4, 0, 0).
function onXAxis(earthAu: number, otherAu: number, other: BodyId = "mars"): BodyPosition[] {
  return [
    { body: "earth", positionAu: { x: earthAu, y: 0, z: 0 } },
    { body: other, positionAu: { x: otherAu, y: 0, z: 0 } },
  ];
}

// Mounts the camera rig with the production lens (48° vertical FOV) on a canvas of the given size.
async function mountSceneCamera(initial: Props, canvas = { width: 1280, height: 800 }) {
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
  const renderer = await ReactThreeTestRenderer.create(scene(initial), { ...canvas, camera: { fov: 48 } });
  // Camera flights take under two seconds; this runs enough frames for any of them to land.
  const advance = (frames: number) => renderer.advanceFrames(frames, 1 / 60);
  const settle = () => advance(150);
  await settle();

  // OrbitControls.update() round-trips through spherical coordinates; ignore float noise.
  const rounded = (vector: number[]) => vector.map((value) => Math.round(value * 1e6) / 1e6 + 0);
  const camera = () => getState().camera as THREE.PerspectiveCamera;
  const controls = () => getState().controls as OrbitControlsImpl;
  return {
    rerender: async (props: Props, { thenSettle = true } = {}) => {
      await renderer.update(scene(props));
      if (thenSettle) {
        await settle();
      }
    },
    advance,
    settle,
    camera: () => rounded(camera().position.toArray()),
    target: () => rounded(controls().target.toArray()),
    distanceToTarget: () => camera().position.distanceTo(controls().target),
    minDistance: () => controls().minDistance,
    near: () => camera().near,
    // Scroll-zoom as far in as the controls allow.
    zoomToLimit: async () => {
      const offset = camera().position.clone().sub(controls().target);
      camera().position.copy(controls().target).addScaledVector(offset.normalize(), controls().minDistance * 0.5);
      await advance(5);
    },
    // Where a body lands in normalized device coordinates; the viewport spans [-1, 1].
    project: (position: BodyPosition) => {
      camera().updateMatrixWorld();
      return new THREE.Vector3(...toScenePoint(position.positionAu)).project(camera());
    },
  };
}

describe("SceneCamera event framing", () => {
  it("frames a newly loaded event pair around its midpoint", async () => {
    const positions = onXAxis(1, 3);
    const view = await mountSceneCamera({ focusedBody: null, highlightedBodies: ["earth", "mars"], positions });

    expect(view.target()).toEqual([6.4, 0, 0]);
    for (const position of positions) {
      const onScreen = view.project(position);
      expect(Math.max(Math.abs(onScreen.x), Math.abs(onScreen.y))).toBeLessThan(1);
    }
  });

  it("keeps both event bodies on screen in a narrow window", async () => {
    // Earth and Neptune at their 2026-09-25 closest approach on a 600×800 canvas: fixed camera
    // offsets put Earth at x ≈ -1.54, off the left edge.
    const positions = getBodyPositions(["earth", "neptune"], new Date("2026-09-25T00:00:00Z"));
    const view = await mountSceneCamera(
      { focusedBody: null, highlightedBodies: ["earth", "neptune"], positions },
      { width: 600, height: 800 },
    );

    for (const position of positions) {
      const onScreen = view.project(position);
      expect(Math.abs(onScreen.x), `${position.body} x`).toBeLessThan(1);
      expect(Math.abs(onScreen.y), `${position.body} y`).toBeLessThan(1);
    }
  });

  it("leaves the camera to the user while playback moves the framed bodies", async () => {
    const event: BodyId[] = ["earth", "mars"];
    const view = await mountSceneCamera({ focusedBody: null, highlightedBodies: event, positions: onXAxis(1, 3) });
    const framed = view.camera();

    await view.rerender({ focusedBody: null, highlightedBodies: event, positions: onXAxis(1.2, 3.4) });

    expect(view.target()).toEqual([6.4, 0, 0]);
    expect(view.camera()).toEqual(framed);
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
    expect(view.distanceToTarget()).toBeCloseTo(EARTH_RADIUS * 5, 6);
    expect(view.minDistance()).toBeLessThan(EARTH_RADIUS * 2);
  });

  it("keeps the surface in front of the near plane at full zoom", async () => {
    // At 1.3 radii from Mars's centre its surface is ~0.06 away, inside the default 0.1 near plane.
    const view = await mountSceneCamera({ focusedBody: "mars", highlightedBodies: NO_EVENT, positions: onXAxis(1, 3) });

    await view.zoomToLimit();

    expect(view.distanceToTarget()).toBeCloseTo(view.minDistance(), 6);
    expect(view.near()).toBeLessThan(view.distanceToTarget() - MARS_RADIUS);
  });

  it("restores the default near plane back in the overview", async () => {
    const view = await mountSceneCamera({ focusedBody: "mars", highlightedBodies: NO_EVENT, positions: onXAxis(1, 3) });
    await view.zoomToLimit();

    await view.rerender({ focusedBody: null, highlightedBodies: NO_EVENT, positions: onXAxis(1, 3) });

    expect(view.near()).toBe(0.1);
  });

  it("follows the focused body as time moves it, keeping the same view of it", async () => {
    const view = await mountSceneCamera({ focusedBody: "earth", highlightedBodies: NO_EVENT, positions: onXAxis(1, 3) });
    const cameraBefore = view.camera();

    await view.rerender({ focusedBody: "earth", highlightedBodies: NO_EVENT, positions: onXAxis(1.5, 3) });

    expect(view.target()).toEqual([4.8, 0, 0]);
    expect(view.camera()).toEqual([cameraBefore[0] + 1.6, cameraBefore[1], cameraBefore[2]]);
  });

  it("resumes following when a briefly missing position comes back", async () => {
    // A small body's live position can drop out for an update (e.g. a failed fetch).
    const view = await mountSceneCamera({ focusedBody: "ceres", highlightedBodies: NO_EVENT, positions: onXAxis(1, 3, "ceres") });
    expect(view.target()).toEqual([9.6, 0, 0]);

    await view.rerender({ focusedBody: "ceres", highlightedBodies: NO_EVENT, positions: onXAxis(1, 3, "ceres").slice(0, 1) });
    await view.rerender({ focusedBody: "ceres", highlightedBodies: NO_EVENT, positions: onXAxis(1, 3.5, "ceres") });

    expect(view.target()).toEqual([11.2, 0, 0]);
  });

  it("still lands on and follows a body whose position drops out mid-flight", async () => {
    const view = await mountSceneCamera({ focusedBody: null, highlightedBodies: NO_EVENT, positions: onXAxis(1, 3, "ceres") });

    await view.rerender({ focusedBody: "ceres", highlightedBodies: NO_EVENT, positions: onXAxis(1, 3, "ceres") }, { thenSettle: false });
    await view.advance(20);
    await view.rerender({ focusedBody: "ceres", highlightedBodies: NO_EVENT, positions: onXAxis(1, 3, "ceres").slice(0, 1) });
    await view.rerender({ focusedBody: "ceres", highlightedBodies: NO_EVENT, positions: onXAxis(1, 3.5, "ceres") });

    expect(view.target()).toEqual([11.2, 0, 0]);
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
