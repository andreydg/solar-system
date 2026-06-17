import { useEffect, useMemo } from "react";
import { useThree } from "@react-three/fiber";
import * as THREE from "three";
import { buildStarField, SCENE_TO_GALACTIC_ROWMAJOR, SKY_RADIUS } from "../lib/celestialSphere";

const starVertexShader = `
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

const starFragmentShader = `
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

const milkyWayVertexShader = `
  varying vec3 vDir;
  void main() {
    vDir = position;
    gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
  }
`;

// Procedural galactic glow: brighten toward the galactic plane (b≈0) and the centre (l≈0),
// with cloud texture and a dark dust lane. Driven entirely by the scene→galactic transform,
// so the band sits in the astronomically correct place as you orbit.
const milkyWayFragmentShader = `
  precision highp float;
  varying vec3 vDir;
  uniform mat3 uSceneToGalactic;
  uniform float uIntensity;

  float hash(vec3 p) {
    p = fract(p * 0.3183099 + 0.1);
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
  }
  float vnoise(vec3 x) {
    vec3 i = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash(i + vec3(0.0, 0.0, 0.0)), hash(i + vec3(1.0, 0.0, 0.0)), f.x),
                   mix(hash(i + vec3(0.0, 1.0, 0.0)), hash(i + vec3(1.0, 1.0, 0.0)), f.x), f.y),
               mix(mix(hash(i + vec3(0.0, 0.0, 1.0)), hash(i + vec3(1.0, 0.0, 1.0)), f.x),
                   mix(hash(i + vec3(0.0, 1.0, 1.0)), hash(i + vec3(1.0, 1.0, 1.0)), f.x), f.y), f.z);
  }
  float fbm(vec3 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
      v += a * vnoise(p);
      p *= 2.02;
      a *= 0.5;
    }
    return v;
  }

  void main() {
    vec3 gal = normalize(uSceneToGalactic * normalize(vDir));
    float b = asin(clamp(gal.z, -1.0, 1.0));
    float cosb = sqrt(max(1.0 - gal.z * gal.z, 1e-4));
    float cosl = clamp(gal.x / cosb, -1.0, 1.0);
    float centerness = 0.5 + 0.5 * cosl;

    float bandTight = exp(-(b * b) / 0.03);
    float bandBroad = exp(-(b * b) / 0.18);
    float band = bandTight * (0.45 + 0.95 * centerness) + bandBroad * (0.12 + 0.3 * centerness);

    float clouds = fbm(gal * 6.0);
    float fine = fbm(gal * 18.0);
    band *= mix(0.55, 1.4, clouds) * mix(0.85, 1.1, fine);

    float dust = smoothstep(0.4, 0.7, fbm(gal * 3.5 + 4.0));
    band *= mix(1.0, 0.4, dust * smoothstep(0.28, 0.0, abs(b)) * centerness);

    vec3 warm = vec3(1.0, 0.9, 0.72);
    vec3 cool = vec3(0.66, 0.76, 1.0);
    vec3 col = mix(cool, warm, centerness * centerness);

    float intensity = max(band, 0.0) * uIntensity;
    gl_FragColor = vec4(col * intensity, 1.0);
  }
`;

export default function CelestialSphere() {
  const pixelRatio = useThree((state) => state.gl.getPixelRatio());

  const starGeometry = useMemo(() => {
    const { positions, colors, sizes } = buildStarField(SKY_RADIUS);
    const buffer = new THREE.BufferGeometry();
    buffer.setAttribute("position", new THREE.BufferAttribute(positions, 3));
    buffer.setAttribute("aColor", new THREE.BufferAttribute(colors, 3));
    buffer.setAttribute("aSize", new THREE.BufferAttribute(sizes, 1));
    return buffer;
  }, []);

  const starMaterial = useMemo(
    () =>
      new THREE.ShaderMaterial({
        uniforms: { uPixelRatio: { value: pixelRatio } },
        vertexShader: starVertexShader,
        fragmentShader: starFragmentShader,
        transparent: true,
        depthWrite: false,
        blending: THREE.AdditiveBlending,
      }),
    [pixelRatio],
  );

  const milkyWayMaterial = useMemo(() => {
    const a = SCENE_TO_GALACTIC_ROWMAJOR;
    const matrix = new THREE.Matrix3();
    matrix.set(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8]);
    return new THREE.ShaderMaterial({
      uniforms: {
        uSceneToGalactic: { value: matrix },
        uIntensity: { value: 0.44 },
      },
      vertexShader: milkyWayVertexShader,
      fragmentShader: milkyWayFragmentShader,
      side: THREE.BackSide,
      transparent: true,
      depthWrite: false,
      blending: THREE.AdditiveBlending,
    });
  }, []);

  useEffect(() => () => starGeometry.dispose(), [starGeometry]);
  useEffect(() => () => starMaterial.dispose(), [starMaterial]);
  useEffect(() => () => milkyWayMaterial.dispose(), [milkyWayMaterial]);

  return (
    <group>
      {/* Diffuse galactic band, slightly inside the star shell. */}
      <mesh material={milkyWayMaterial} frustumCulled={false} renderOrder={-1}>
        <sphereGeometry args={[SKY_RADIUS - 20, 64, 48]} />
      </mesh>
      {/* Stars, deep-sky patches and the ambient field. */}
      <points geometry={starGeometry} material={starMaterial} frustumCulled={false} />
    </group>
  );
}
