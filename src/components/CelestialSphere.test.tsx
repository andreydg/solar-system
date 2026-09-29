import { useThree, type RootState } from "@react-three/fiber";
import ReactThreeTestRenderer from "@react-three/test-renderer";
import type { ReactNode } from "react";
import { describe, expect, it } from "vitest";
import type { BodyPosition } from "../domain/solarSystem";
import CelestialSphere from "./CelestialSphere";
import SceneCamera from "./SceneCamera";

async function mount(scene: (probe: ReactNode) => ReactNode) {
  let getState: () => RootState = () => {
    throw new Error("scene not mounted");
  };
  const StoreProbe = () => {
    getState = useThree((state) => state.get);
    return null;
  };
  const renderer = await ReactThreeTestRenderer.create(scene(<StoreProbe />));
  return { renderer, getState: () => getState(), StoreProbe };
}

describe("CelestialSphere", () => {
  // Event framing can zoom the camera out well past its usual 220-unit limit. A sky fixed at the
  // origin (radius 500) would shift under the camera and could even end up in front of it.
  it("stays centred on the camera wherever it moves", async () => {
    const { renderer, getState } = await mount((probe) => (
      <>
        <CelestialSphere />
        {probe}
      </>
    ));

    getState().camera.position.set(300, -40, 120);
    await renderer.advanceFrames(1, 1 / 60);

    const sky = renderer.scene.children[0].instance;
    expect(sky.position.toArray()).toEqual([300, -40, 120]);
  });

  it("catches up with the camera in the same frame SceneCamera moves it", async () => {
    // Mounted in the app's order: the sky before the camera rig. If the rig moved the camera after
    // the sky had already copied it, the sky would trail a frame behind during flights and follows.
    const earthAt = (x: number): BodyPosition[] => [{ body: "earth", positionAu: { x, y: 0, z: 0 } }];
    const scene = (positions: BodyPosition[], probe: ReactNode) => (
      <>
        <CelestialSphere />
        <SceneCamera focusedBody="earth" highlightedBodies={[]} positions={positions} />
        {probe}
      </>
    );
    const { renderer, getState, StoreProbe } = await mount((probe) => scene(earthAt(1), probe));
    await renderer.advanceFrames(150, 1 / 60); // fly in and start following

    await renderer.update(scene(earthAt(1.5), <StoreProbe />));
    await renderer.advanceFrames(1, 1 / 60); // the follow moves the camera this frame

    const sky = renderer.scene.children[0].instance;
    expect(sky.position.toArray()).toEqual(getState().camera.position.toArray());
  });
});
