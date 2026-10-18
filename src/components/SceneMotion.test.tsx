import { useThree, type RootState } from "@react-three/fiber";
import { create } from "@react-three/test-renderer";
import * as THREE from "three";
import type { OrbitControls } from "three-stdlib";
import { expect, it, vi } from "vitest";
import { getBodyPositions } from "../lib/ephemeris";
import { toScenePoint } from "../lib/sceneSpace";
import { Comet, Planet } from "./SolarSystemScene";
import SceneMotion from "./SceneMotion";
import SceneCamera from "./SceneCamera";

vi.mock("@react-three/drei", async original => ({
  ...(await original<typeof import("@react-three/drei")>()),
  useTexture: () => new THREE.Texture(), Html: () => null, useCursor: () => {},
}));

it("moves planets, comets and the following camera every frame without a React render", async () => {
  const timeRef = { current: new Date("2026-01-01Z") };
  const positions = [...getBodyPositions(["earth"], timeRef.current),
    { body: "halley" as const, positionAu: { x: 1, y: 0, z: 0 } }];
  const trajectories = { halley: [
    { time: timeRef.current, positionAu: { x: 1, y: 0, z: 0 } },
    { time: new Date("2027-01-01Z"), positionAu: { x: 0, y: 3, z: 0 } },
  ] };
  let renders = 0, state!: () => RootState;
  function Scene() {
    renders++;
    state = useThree(s => s.get);
    return <>
      <SceneMotion timeRef={timeRef} positions={positions} trajectories={trajectories} />
      <group name="earth"><Planet position={positions[0]} highlighted={false} onSelect={() => {}} /></group>
      <group name="halley"><Comet position={positions[1]} highlighted={false} onSelect={() => {}} /></group>
      <SceneCamera positions={positions} highlightedBodies={[]} focusedBody="earth" />
    </>;
  }
  const renderer = await create(<Scene />);
  await renderer.advanceFrames(150, 1/60); // finish initial camera flight
  const controls = state().controls as OrbitControls;
  const offset = state().camera.position.clone().sub(controls.target);
  let previous = positions[0].positionAu;
  for (let frame = 1; frame <= 60; frame++) {
    timeRef.current = new Date(Date.UTC(2026, 0, frame + 1));
    await renderer.advanceFrames(1, 1/60);
    expect(positions[0].positionAu).not.toEqual(previous);
    previous = positions[0].positionAu;
    const expected = toScenePoint(getBodyPositions(["earth"], timeRef.current)[0].positionAu);
    const earth = renderer.scene.instance.getObjectByName("earth")!.children[0];
    const comet = renderer.scene.instance.getObjectByName("halley")!.children[0];
    expect(earth.position.toArray()).toEqual(expected);
    expect(comet.position.toArray()).toEqual(toScenePoint(positions[1].positionAu));
    expect(controls.target.distanceTo(earth.position)).toBeLessThan(1e-8);
    expect(state().camera.position.clone().sub(controls.target).distanceTo(offset)).toBeLessThan(1e-8);
    const tail = comet.children.find(o => (o as THREE.Mesh).geometry?.type === "ConeGeometry")!;
    const direction = new THREE.Vector3(0, 1, 0).applyQuaternion(tail.quaternion);
    expect(direction.distanceTo(comet.position.clone().normalize())).toBeLessThan(1e-8);
  }
  expect(renders).toBe(1);
  Object.defineProperty(state().gl.domElement, "ownerDocument", { value: new EventTarget() });
  await renderer.unmount();
});
