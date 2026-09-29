import { Canvas, useFrame } from "@react-three/fiber";
import { Html, Line, useCursor, useTexture } from "@react-three/drei";
import { useEffect, useMemo, useRef, useState, Suspense } from "react";
import * as THREE from "three";
import { BODIES, BODY_BY_ID, type BodyId, type BodyPosition, distanceAu, isComet, isSmallBody } from "../domain/solarSystem";
import { OVERVIEW_CAMERA_POSITION } from "../lib/cameraViews";
import { getNorthPole, sampleTrajectory } from "../lib/ephemeris";
import { pickVisibleLabels, type LabelBox } from "../lib/labelLayout";
import { AU_TO_SCENE_UNITS, getVisualRadius, SUN_RADIUS, toScenePoint, type ScenePoint } from "../lib/sceneSpace";
import { chunkScenePoints, buildOrbitTrailSegments, type SmallBodyTrajectory } from "../lib/smallBodyTrajectory";
import { addDays } from "../lib/timeUtils";
import CelestialSphere from "./CelestialSphere";
import SceneCamera from "./SceneCamera";

const ORBIT_SAMPLE_COUNT = 192;
const TRAIL_EPOCH = new Date("2026-01-01T00:00:00Z");

const PLANET_TEXTURES: Record<string, string> = {
  mercury: "/textures/mercurymap.jpg",
  venus: "/textures/venusmap.jpg",
  earth: "/textures/earthmap1k.jpg",
  mars: "/textures/marsmap1k.jpg",
  jupiter: "/textures/jupitermap.jpg",
  saturn: "/textures/saturnmap.jpg",
  uranus: "/textures/uranusmap.jpg",
  neptune: "/textures/neptunemap.jpg",
};

// Limb haze for planets with visible atmospheres; intensity is relative, tuned by eye.
const ATMOSPHERES: Partial<Record<BodyId, { color: string; intensity: number }>> = {
  venus: { color: "#f5deb0", intensity: 0.75 },
  earth: { color: "#5fa8ff", intensity: 0.95 },
  uranus: { color: "#a8e6ef", intensity: 0.6 },
  neptune: { color: "#6f8cff", intensity: 0.7 },
};
const ATMOSPHERE_SCALE = 1.1;

// Click targets never shrink below this radius on screen, so small, distant bodies stay easy to hit.
const HIT_RADIUS_PX = 14;
// A pointer that moved further than this between press and release was dragging the camera.
const CLICK_SLOP_PX = 4;
// How far a label's centre sits above its anchor; matches `.planet-label { translate }` (0.85rem).
const LABEL_LIFT_PX = 0.85 * 16;
const scratchVector = new THREE.Vector3();

const atmosphereVertexShader = `
  varying vec3 vWorldNormal;
  varying vec3 vWorldPosition;
  void main() {
    vec4 worldPosition = modelMatrix * vec4(position, 1.0);
    vWorldPosition = worldPosition.xyz;
    vWorldNormal = normalize(mat3(modelMatrix) * normal);
    gl_Position = projectionMatrix * viewMatrix * worldPosition;
  }
`;

// Haze on a shell slightly larger than the planet: nothing at the shell's silhouette, peaking at
// the planet's limb, thinning out across the disk, and only on the sunlit side (the Sun sits at
// the scene origin).
const atmosphereFragmentShader = `
  uniform vec3 uColor;
  uniform float uIntensity;
  uniform float uLimbFacing;
  varying vec3 vWorldNormal;
  varying vec3 vWorldPosition;
  void main() {
    vec3 normal = normalize(vWorldNormal);
    float facing = clamp(dot(normal, normalize(cameraPosition - vWorldPosition)), 0.0, 1.0);
    float outer = smoothstep(0.0, uLimbFacing, facing);
    float inner = exp(-max(facing - uLimbFacing, 0.0) * 8.0);
    float daylight = smoothstep(-0.45, 0.35, dot(normal, normalize(-vWorldPosition)));
    gl_FragColor = vec4(uColor, outer * outer * inner * mix(0.08, 1.0, daylight) * uIntensity);
    #include <colorspace_fragment>
  }
`;

type SolarSystemSceneProps = {
  currentTime: Date;
  focusedBody: BodyId | null;
  highlightedBodies: BodyId[];
  positions: BodyPosition[];
  smallBodyTrajectories: Partial<Record<BodyId, SmallBodyTrajectory>>;
  visibleBodies: BodyId[];
  /** Fly to and follow a body; null returns to the Sun-centred overview. */
  onFocusBody: (body: BodyId | null) => void;
};

export default function SolarSystemScene({
  currentTime,
  focusedBody,
  highlightedBodies,
  positions,
  smallBodyTrajectories,
  visibleBodies,
  onFocusBody,
}: SolarSystemSceneProps) {
  const highlightedSet = useMemo(() => new Set(highlightedBodies), [highlightedBodies]);
  // Event bodies' labels win over the Sun's, which wins over the rest (bigger bodies first). The
  // focused body has no label: it would only cover the close-up.
  const labels: LabelSpec[] = [
    { key: "sun", name: "Sun", anchor: [0, SUN_RADIUS, 0], highlighted: false, priority: 2e6, onSelect: () => onFocusBody(null) },
    ...positions
      .filter((position) => position.body !== focusedBody)
      .map((position) => {
        const body = BODY_BY_ID[position.body];
        const highlighted = highlightedSet.has(position.body);
        const [x, y, z] = toScenePoint(position.positionAu);
        const radius = getVisualRadius(body.radiusKm, highlighted, isComet(position.body));
        return {
          key: position.body,
          name: body.name,
          anchor: [x, y + radius, z] as ScenePoint,
          highlighted,
          priority: (highlighted ? 3e6 : 0) + body.radiusKm,
          onSelect: () => onFocusBody(position.body),
        };
      }),
  ];

  return (
    <Canvas camera={{ position: OVERVIEW_CAMERA_POSITION, fov: 48 }} dpr={[1, 2]}>
      <color attach="background" args={["#050505"]} />
      <ambientLight intensity={0.35} />
      {/* No distance falloff: physical 1/r² spans ~6,000x from Mercury to Neptune, which blew the
          inner planets out to white and left the ice giants black. */}
      <pointLight color="#fff2c0" decay={0} intensity={5} position={[0, 0, 0]} />
      <CelestialSphere />

      <Suspense fallback={null}>
        <Sun onSelect={() => onFocusBody(null)} />
        <OrbitTrails
          smallBodyTrajectories={smallBodyTrajectories}
          visibleBodies={visibleBodies}
        />
        <EventPairLine highlightedBodies={highlightedBodies} positions={positions} />
        {positions.map((position) =>
          isComet(position.body) ? (
            <Comet
              highlighted={highlightedSet.has(position.body)}
              key={position.body}
              position={position}
              onSelect={() => onFocusBody(position.body)}
            />
          ) : (
            <Planet
              highlighted={highlightedSet.has(position.body)}
              key={position.body}
              position={position}
              onSelect={() => onFocusBody(position.body)}
            />
          ),
        )}
      </Suspense>
      <BodyLabels labels={labels} />

      <Html position={[-18, 14, -18]} transform>
        <div className="scene-date">{currentTime.toISOString().slice(0, 10)}</div>
      </Html>

      <SceneCamera focusedBody={focusedBody} highlightedBodies={highlightedBodies} positions={positions} />
    </Canvas>
  );
}

function Sun({ onSelect }: { onSelect: () => void }) {
  const texture = useTexture("/textures/sunmap.jpg", (loaded) => {
    (loaded as THREE.Texture).colorSpace = THREE.SRGBColorSpace;
  });
  return (
    <group>
      <mesh>
        <sphereGeometry args={[SUN_RADIUS, 64, 64]} />
        <meshBasicMaterial map={texture} color="#ffffff" />
      </mesh>
      <FocusHitArea radius={SUN_RADIUS} onSelect={onSelect} />
    </group>
  );
}

function OrbitTrails({
  smallBodyTrajectories,
  visibleBodies,
}: {
  smallBodyTrajectories: Partial<Record<BodyId, SmallBodyTrajectory>>;
  visibleBodies: BodyId[];
}) {
  const visibleSet = useMemo(() => new Set(visibleBodies), [visibleBodies]);

  const trails = useMemo(
    () =>
      BODIES.filter((body) => !isSmallBody(body.id)).map((body) => {
        const halfOrbitDays = body.orbitDays / 2;
        const start = addDays(TRAIL_EPOCH, -halfOrbitDays);
        const end = addDays(TRAIL_EPOCH, halfOrbitDays);
        const points = sampleTrajectory(body.id, start, end, ORBIT_SAMPLE_COUNT).map(toScenePoint);

        return {
          body,
          points,
        };
      }),
    [],
  );

  const smallBodyTrailLines = useMemo(
    () =>
      BODIES.filter((body) => isSmallBody(body.id))
        .map((body) => {
          const segments = buildOrbitTrailSegments(smallBodyTrajectories[body.id] ?? []);
          return {
            body,
            segmentGroups: segments.flatMap((segment) =>
              chunkScenePoints(segment.map(toScenePoint)).map((points) => ({ body, points })),
            ),
          };
        })
        .filter(({ segmentGroups }) => segmentGroups.length > 0),
    [smallBodyTrajectories],
  );

  return (
    <>
      {trails
        .filter(({ body }) => visibleSet.has(body.id))
        .map(({ body, points }) => (
          <Line
            color={body.color}
            key={body.id}
            lineWidth={1}
            opacity={0.32}
            points={points}
            transparent
          />
        ))}
      {smallBodyTrailLines
        .filter(({ body }) => visibleSet.has(body.id))
        .flatMap(({ segmentGroups }) =>
          segmentGroups.map(({ body, points }, index) => (
            <Line
              color={body.color}
              key={`${body.id}-trail-${index}`}
              lineWidth={1}
              opacity={0.32}
              points={points}
              transparent
            />
          )),
        )}
    </>
  );
}

type BodyProps = {
  highlighted: boolean;
  position: BodyPosition;
  onSelect: () => void;
};

export function Comet({ highlighted, position, onSelect }: BodyProps) {
  const body = BODY_BY_ID[position.body];
  const scenePosition = toScenePoint(position.positionAu);
  const nucleusRadius = getVisualRadius(body.radiusKm, highlighted, true);
  const distanceAu = Math.hypot(
    position.positionAu.x,
    position.positionAu.y,
    position.positionAu.z,
  );
  const tailLength = Math.min(3.4, Math.max(0.9, 1.35 / Math.max(distanceAu, 0.25))) * AU_TO_SCENE_UNITS;
  const tailWidth = nucleusRadius * 1.8;
  const tailDirection = normalizeVector(scenePosition);
  const tailQuaternion = useMemo(() => {
    const quaternion = new THREE.Quaternion();
    quaternion.setFromUnitVectors(
      new THREE.Vector3(0, 1, 0),
      new THREE.Vector3(...tailDirection),
    );
    return quaternion;
  }, [tailDirection]);

  return (
    <group position={scenePosition}>
      {highlighted ? (
        <>
          <mesh>
            <sphereGeometry args={[nucleusRadius * 2.4, 24, 24]} />
            <meshBasicMaterial color={body.color} opacity={0.14} transparent depthWrite={false} />
          </mesh>
          <mesh rotation={[Math.PI / 2, 0, 0]}>
            <ringGeometry args={[nucleusRadius * 1.35, nucleusRadius * 1.65, 48]} />
            <meshBasicMaterial color={body.color} opacity={0.85} transparent depthWrite={false} />
          </mesh>
        </>
      ) : null}
      <mesh position={scaleVector(tailDirection, tailLength * 0.45)} quaternion={tailQuaternion}>
        <coneGeometry args={[tailWidth, tailLength, 24, 1, true]} />
        <meshBasicMaterial
          color={body.color}
          opacity={0.42}
          transparent
          depthWrite={false}
          side={THREE.DoubleSide}
        />
      </mesh>
      <mesh position={scaleVector(tailDirection, tailLength * 0.72)} quaternion={tailQuaternion}>
        <coneGeometry args={[tailWidth * 0.55, tailLength * 0.75, 20, 1, true]} />
        <meshBasicMaterial
          color="#e2e8f0"
          opacity={0.18}
          transparent
          depthWrite={false}
          side={THREE.DoubleSide}
        />
      </mesh>
      <mesh>
        <sphereGeometry args={[nucleusRadius, 24, 24]} />
        <meshStandardMaterial
          color={body.color}
          emissive={highlighted ? body.color : "#334155"}
          emissiveIntensity={highlighted ? 0.55 : 0.25}
          roughness={0.65}
        />
      </mesh>
      <FocusHitArea radius={nucleusRadius} onSelect={onSelect} />
    </group>
  );
}

export function Planet({ highlighted, position, onSelect }: BodyProps) {
  const body = BODY_BY_ID[position.body];
  const scenePosition = toScenePoint(position.positionAu);
  const visualRadius = getVisualRadius(body.radiusKm, highlighted, false);
  const textureUrl = PLANET_TEXTURES[position.body] || "/textures/moon.jpg";
  const texture = useTexture(textureUrl, (loaded) => {
    (loaded as THREE.Texture).colorSpace = THREE.SRGBColorSpace;
  });
  // Point the sphere's +Y (the texture's north) along the real rotation pole. The pole drifts
  // only fractions of a degree per century, so one epoch serves every date the app shows.
  const poleQuaternion = useMemo(() => {
    const quaternion = new THREE.Quaternion();
    const pole = getNorthPole(position.body, TRAIL_EPOCH);
    if (pole) {
      quaternion.setFromUnitVectors(
        new THREE.Vector3(0, 1, 0),
        new THREE.Vector3(...normalizeVector(toScenePoint(pole))),
      );
    }
    return quaternion;
  }, [position.body]);
  const atmosphere = ATMOSPHERES[position.body];

  return (
    <group position={scenePosition}>
      {highlighted ? (
        <>
          <mesh>
            <sphereGeometry args={[visualRadius * 2.4, 24, 24]} />
            <meshBasicMaterial color={body.color} opacity={0.14} transparent depthWrite={false} />
          </mesh>
          <mesh rotation={[Math.PI / 2, 0, 0]}>
            <ringGeometry args={[visualRadius * 1.35, visualRadius * 1.65, 48]} />
            <meshBasicMaterial color={body.color} opacity={0.85} transparent depthWrite={false} />
          </mesh>
        </>
      ) : null}
      <group quaternion={poleQuaternion}>
        <mesh>
          <sphereGeometry args={[visualRadius, 64, 48]} />
          <meshStandardMaterial
            map={texture}
            emissive={highlighted ? body.color : "#000000"}
            emissiveIntensity={highlighted ? 0.35 : 0}
            roughness={0.8}
          />
        </mesh>
        {position.body === "saturn" ? <SaturnRings visualRadius={visualRadius} /> : null}
      </group>
      {atmosphere ? (
        <Atmosphere color={atmosphere.color} intensity={atmosphere.intensity} radius={visualRadius} />
      ) : null}
      <FocusHitArea radius={visualRadius} onSelect={onSelect} />
    </group>
  );
}

/**
 * Invisible click target around a body that never gets smaller than HIT_RADIUS_PX on screen.
 * Raycasting ignores `visible`, so it costs no draw call.
 */
function FocusHitArea({ radius, onSelect }: { radius: number; onSelect: () => void }) {
  const mesh = useRef<THREE.Mesh>(null);
  const [hovered, setHovered] = useState(false);
  useCursor(hovered);

  useFrame(({ camera, size }) => {
    const hitArea = mesh.current;
    if (!hitArea || !(camera instanceof THREE.PerspectiveCamera)) {
      return;
    }
    const distance = camera.position.distanceTo(hitArea.getWorldPosition(scratchVector));
    const unitsPerPixel = (2 * distance * Math.tan(THREE.MathUtils.degToRad(camera.fov / 2))) / size.height;
    hitArea.scale.setScalar(Math.max(radius, HIT_RADIUS_PX * unitsPerPixel));
  });

  return (
    <mesh
      ref={mesh}
      visible={false}
      onClick={(event) => {
        if (event.delta > CLICK_SLOP_PX) {
          return;
        }
        event.stopPropagation();
        onSelect();
      }}
      onPointerOut={() => setHovered(false)}
      onPointerOver={(event) => {
        event.stopPropagation();
        setHovered(true);
      }}
    >
      <sphereGeometry args={[1, 16, 12]} />
    </mesh>
  );
}

type LabelSpec = {
  key: string;
  name: string;
  /** The top of the body; the label sits just above it. */
  anchor: ScenePoint;
  highlighted: boolean;
  priority: number;
  onSelect: () => void;
};

/**
 * Body names as buttons that fly to their body. They keep a fixed on-screen size (drei's
 * distanceFactor made them unreadable in the overview and huge up close), and each frame any
 * label that would overlap a higher-priority one is hidden until zooming in separates them.
 */
function BodyLabels({ labels }: { labels: LabelSpec[] }) {
  const buttons = useRef(new Map<string, HTMLButtonElement>());
  const latestLabels = useRef(labels);

  useEffect(() => {
    latestLabels.current = labels;
  }, [labels]);

  useFrame(({ camera, size }) => {
    const boxes: LabelBox[] = [];
    for (const label of latestLabels.current) {
      const button = buttons.current.get(label.key);
      if (!button) {
        continue;
      }
      const projected = scratchVector.set(...label.anchor).project(camera);
      if (projected.z > 1) {
        button.style.visibility = "hidden"; // behind the camera
        continue;
      }
      const width = button.offsetWidth;
      const height = button.offsetHeight;
      const x = (projected.x * 0.5 + 0.5) * size.width;
      const y = (-projected.y * 0.5 + 0.5) * size.height - LABEL_LIFT_PX;
      boxes.push({ key: label.key, priority: label.priority, x: x - width / 2, y: y - height / 2, width, height });
    }
    const visible = pickVisibleLabels(boxes);
    for (const { key } of boxes) {
      buttons.current.get(key)!.style.visibility = visible.has(key) ? "visible" : "hidden";
    }
  });

  return labels.map((label) => (
    <Html center key={label.key} position={label.anchor}>
      <button
        className={label.highlighted ? "planet-label planet-label-highlighted" : "planet-label"}
        ref={(button) => {
          if (button) {
            buttons.current.set(label.key, button);
          } else {
            buttons.current.delete(label.key);
          }
        }}
        // Hidden until the first layout pass decides whether it fits.
        style={{ visibility: "hidden" }}
        type="button"
        onClick={(event) => {
          // The scene's own click handling sits underneath the label.
          event.stopPropagation();
          label.onSelect();
        }}
      >
        {label.name}
      </button>
    </Html>
  ));
}

// Radial brightness/opacity profile of Saturn's rings: faint C ring, bright B ring, the dark
// Cassini Division, the A ring with the Encke gap, and a soft outer edge.
function buildSaturnRingTexture(): THREE.CanvasTexture {
  const width = 1024;
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = 8;
  const ctx = canvas.getContext("2d");
  if (ctx) {
    for (let x = 0; x < width; x += 1) {
      const t = x / (width - 1); // 0 = inner edge, 1 = outer edge
      let alpha: number;
      if (t < 0.05) {
        alpha = 0; // inner clear zone
      } else if (t < 0.22) {
        alpha = 0.12 + 0.18 * ((t - 0.05) / 0.17); // C ring (faint)
      } else if (t < 0.6) {
        alpha = 0.62 + 0.12 * Math.sin(t * 90); // B ring (bright, banded)
      } else if (t < 0.66) {
        alpha = 0.05; // Cassini Division
      } else if (t < 0.95) {
        alpha = 0.42 + 0.1 * Math.sin(t * 120); // A ring
      } else {
        alpha = Math.max(0, 0.4 * (1 - (t - 0.95) / 0.05)); // outer fade
      }
      if (t > 0.9 && t < 0.915) {
        alpha *= 0.25; // Encke gap
      }
      alpha = Math.max(0, Math.min(0.9, alpha));

      const shade = 198 + 26 * Math.sin(t * 60);
      const r = Math.min(255, Math.round(shade));
      const g = Math.min(255, Math.round(shade * 0.9));
      const b = Math.min(255, Math.round(shade * 0.66));
      ctx.fillStyle = `rgba(${r}, ${g}, ${b}, ${alpha})`;
      ctx.fillRect(x, 0, 1, canvas.height);
    }
  }
  const texture = new THREE.CanvasTexture(canvas);
  texture.colorSpace = THREE.SRGBColorSpace;
  return texture;
}

function SaturnRings({ visualRadius }: { visualRadius: number }) {
  const inner = visualRadius * 1.28;
  const outer = visualRadius * 2.3;

  const geometry = useMemo(() => {
    const geo = new THREE.RingGeometry(inner, outer, 128, 1);
    // Remap UVs so the texture's U axis runs radially (RingGeometry's default UVs are square).
    const position = geo.attributes.position;
    const uv = geo.attributes.uv;
    const vertex = new THREE.Vector3();
    for (let i = 0; i < position.count; i += 1) {
      vertex.fromBufferAttribute(position, i);
      const radius = Math.hypot(vertex.x, vertex.y);
      uv.setXY(i, (radius - inner) / (outer - inner), 0.5);
    }
    uv.needsUpdate = true;
    return geo;
  }, [inner, outer]);

  const texture = useMemo(() => buildSaturnRingTexture(), []);

  useEffect(() => () => geometry.dispose(), [geometry]);
  useEffect(() => () => texture.dispose(), [texture]);

  // RingGeometry lies in XY; turn it into the planet's equatorial (XZ) plane. The parent group
  // carries Saturn's real pole, so the rings open and close over its 29-year orbit.
  return (
    <mesh geometry={geometry} rotation={[-Math.PI / 2, 0, 0]}>
      <meshBasicMaterial map={texture} side={THREE.DoubleSide} transparent depthWrite={false} />
    </mesh>
  );
}

function Atmosphere({ color, intensity, radius }: { color: string; intensity: number; radius: number }) {
  const material = useMemo(
    () =>
      new THREE.ShaderMaterial({
        uniforms: {
          uColor: { value: new THREE.Color(color) },
          uIntensity: { value: intensity },
          // How squarely the shell faces the camera just outside the planet's limb.
          uLimbFacing: { value: Math.sqrt(1 - 1 / ATMOSPHERE_SCALE ** 2) },
        },
        vertexShader: atmosphereVertexShader,
        fragmentShader: atmosphereFragmentShader,
        transparent: true,
        depthWrite: false,
        blending: THREE.AdditiveBlending,
      }),
    [color, intensity],
  );

  useEffect(() => () => material.dispose(), [material]);

  return (
    <mesh material={material}>
      <sphereGeometry args={[radius * ATMOSPHERE_SCALE, 64, 48]} />
    </mesh>
  );
}

function EventPairLine({
  highlightedBodies,
  positions,
}: {
  highlightedBodies: BodyId[];
  positions: BodyPosition[];
}) {
  if (highlightedBodies.length !== 2) {
    return null;
  }

  const first = positions.find((position) => position.body === highlightedBodies[0]);
  const second = positions.find((position) => position.body === highlightedBodies[1]);

  if (!first || !second) {
    return null;
  }

  const pointA = toScenePoint(first.positionAu);
  const pointB = toScenePoint(second.positionAu);
  const separationAu = distanceAu(first.positionAu, second.positionAu);
  const midpoint: [number, number, number] = [
    (pointA[0] + pointB[0]) / 2,
    (pointA[1] + pointB[1]) / 2,
    (pointA[2] + pointB[2]) / 2,
  ];

  return (
    <>
      <Line
        color="#93c5fd"
        lineWidth={1.5}
        opacity={0.75}
        points={[pointA, pointB]}
        transparent
      />
      <Html center position={midpoint}>
        <span className="pair-distance-label">{separationAu.toFixed(4)} AU</span>
      </Html>
    </>
  );
}

function normalizeVector(vector: [number, number, number]): [number, number, number] {
  const length = Math.hypot(vector[0], vector[1], vector[2]) || 1;
  return [vector[0] / length, vector[1] / length, vector[2] / length];
}

function scaleVector(vector: [number, number, number], scale: number): [number, number, number] {
  return [vector[0] * scale, vector[1] * scale, vector[2] * scale];
}
