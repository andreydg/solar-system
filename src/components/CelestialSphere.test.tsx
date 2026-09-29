import { useThree, type RootState } from "@react-three/fiber";
import ReactThreeTestRenderer from "@react-three/test-renderer";
import { describe, expect, it } from "vitest";
import CelestialSphere from "./CelestialSphere";

describe("CelestialSphere", () => {
  // Event framing can zoom the camera out well past its usual 220-unit limit. A sky fixed at the
  // origin (radius 500) would shift under the camera and could even end up in front of it.
  it("stays centred on the camera wherever it moves", async () => {
    let getState: () => RootState = () => {
      throw new Error("scene not mounted");
    };
    const StoreProbe = () => {
      getState = useThree((state) => state.get);
      return null;
    };
    const renderer = await ReactThreeTestRenderer.create(
      <>
        <CelestialSphere />
        <StoreProbe />
      </>,
    );

    getState().camera.position.set(300, -40, 120);
    await renderer.advanceFrames(1, 1 / 60);

    const sky = renderer.scene.children[0].instance;
    expect(sky.position.toArray()).toEqual([300, -40, 120]);
  });
});
