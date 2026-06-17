import { useEffect, useMemo } from "react";
import { useThree } from "@react-three/fiber";
import * as THREE from "three";
import { buildStarField, SKY_RADIUS } from "../lib/celestialSphere";

const vertexShader = `
  attribute vec3 aColor;
  attribute float aSize;
  uniform float uPixelRatio;
  varying vec3 vColor;
  void main() {
    vColor = aColor;
    vec4 mvPosition = modelViewMatrix * vec4(position, 1.0);
    gl_Position = projectionMatrix * mvPosition;
    // Constant screen-space size (no distance attenuation) — these are "infinitely" far.
    gl_PointSize = aSize * uPixelRatio;
  }
`;

const fragmentShader = `
  precision mediump float;
  varying vec3 vColor;
  void main() {
    vec2 uv = gl_PointCoord - vec2(0.5);
    float d = length(uv);
    if (d > 0.5) discard;
    float glow = smoothstep(0.5, 0.0, d);
    gl_FragColor = vec4(vColor * glow, glow);
  }
`;

export default function CelestialSphere() {
  const pixelRatio = useThree((state) => state.gl.getPixelRatio());

  const geometry = useMemo(() => {
    const { positions, colors, sizes } = buildStarField(SKY_RADIUS);
    const buffer = new THREE.BufferGeometry();
    buffer.setAttribute("position", new THREE.BufferAttribute(positions, 3));
    buffer.setAttribute("aColor", new THREE.BufferAttribute(colors, 3));
    buffer.setAttribute("aSize", new THREE.BufferAttribute(sizes, 1));
    return buffer;
  }, []);

  const material = useMemo(
    () =>
      new THREE.ShaderMaterial({
        uniforms: { uPixelRatio: { value: pixelRatio } },
        vertexShader,
        fragmentShader,
        transparent: true,
        depthWrite: false,
        blending: THREE.AdditiveBlending,
      }),
    [pixelRatio],
  );

  useEffect(() => () => geometry.dispose(), [geometry]);
  useEffect(() => () => material.dispose(), [material]);

  // frustumCulled off: the camera sits inside this large point sphere.
  return <points geometry={geometry} material={material} frustumCulled={false} />;
}
