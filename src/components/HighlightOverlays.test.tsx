import ReactThreeTestRenderer from "@react-three/test-renderer";
import * as THREE from "three";
import { describe, expect, it, vi } from "vitest";
import type { BodyPosition } from "../domain/solarSystem";
import { Comet, Planet } from "./SolarSystemScene";

vi.mock("@react-three/drei", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@react-three/drei")>()),
  // Node has no image loading or DOM: hand bodies a blank texture and skip the HTML labels.
  useTexture: () => new THREE.Texture(),
  Html: () => null,
}));

function transparentMaterials(root: THREE.Object3D) {
  const materials: THREE.Material[] = [];
  root.traverse((object) => {
    const material = (object as THREE.Mesh).material;
    if (material && !Array.isArray(material) && material.transparent) {
      materials.push(material);
    }
  });
  return materials;
}

const AT_ONE_AU = (body: BodyPosition["body"]): BodyPosition => ({ body, positionAu: { x: 1, y: 0, z: 0 } });

describe("highlighted bodies", () => {
  // Transparent meshes at the same spot draw in creation order. A body mounted already
  // highlighted (e.g. re-shown after picking an event) creates its selection overlays before its
  // atmosphere; if those overlays wrote depth, the atmosphere behind their surface would vanish.
  it("keep every transparent layer of a planet out of the depth buffer", async () => {
    const renderer = await ReactThreeTestRenderer.create(<Planet highlighted position={AT_ONE_AU("earth")} onSelect={() => {}} />);
    const layers = transparentMaterials(renderer.scene.instance);

    expect(layers.length).toBeGreaterThanOrEqual(3); // selection sphere, selection ring, atmosphere
    expect(layers.filter((material) => material.depthWrite)).toEqual([]);
  });

  it("keep every transparent layer of a comet out of the depth buffer", async () => {
    const renderer = await ReactThreeTestRenderer.create(<Comet highlighted position={AT_ONE_AU("halley")} onSelect={() => {}} />);

    expect(transparentMaterials(renderer.scene.instance).filter((material) => material.depthWrite)).toEqual([]);
  });
});
