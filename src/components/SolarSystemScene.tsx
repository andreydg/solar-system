import { Canvas, useThree } from "@react-three/fiber";
import { Html, Line, OrbitControls, useTexture } from "@react-three/drei";
import { useEffect, useMemo, useRef, Suspense } from "react";
import * as THREE from "three";
import { BODIES, BODY_BY_ID, type BodyId, type BodyPosition, type Vec3, distanceAu, isComet, isSmallBody } from "../domain/solarSystem";
import { sampleTrajectory } from "../lib/ephemeris";
import { chunkScenePoints, buildOrbitTrailSegments, type SmallBodyTrajectory } from "../lib/smallBodyTrajectory";
import { addDays } from "../lib/timeUtils";
import CelestialSphere from "./CelestialSphere";
import type { OrbitControls as OrbitControlsImpl } from "three-stdlib";

const AU_TO_SCENE_UNITS = 3.2;
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

type SolarSystemSceneProps = {
  currentTime: Date;
  highlightedBodies: BodyId[];
  positions: BodyPosition[];
  smallBodyTrajectories: Partial<Record<BodyId, SmallBodyTrajectory>>;
  visibleBodies: BodyId[];
};

export default function SolarSystemScene({
  currentTime,
  highlightedBodies,
  positions,
  smallBodyTrajectories,
  visibleBodies,
}: SolarSystemSceneProps) {
  const highlightedSet = useMemo(() => new Set(highlightedBodies), [highlightedBodies]);

  return (
    <Canvas camera={{ position: [0, 38, 42], fov: 48 }} dpr={[1, 2]}>
      <color attach="background" args={["#050505"]} />
      <ambientLight intensity={0.35} />
      <pointLight color="#fff2c0" intensity={900} position={[0, 0, 0]} />
      <CelestialSphere />

      <Suspense fallback={null}>
        <Sun />
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
            />
          ) : (
            <Planet
              highlighted={highlightedSet.has(position.body)}
              key={position.body}
              position={position}
            />
          ),
        )}
      </Suspense>

      <Html position={[-18, 14, -18]} transform>
        <div className="scene-date">{currentTime.toISOString().slice(0, 10)}</div>
      </Html>

      <OrbitControls enableDamping dampingFactor={0.08} maxDistance={220} minDistance={4} />
      <FocusOnBodies highlightedBodies={highlightedBodies} positions={positions} />
    </Canvas>
  );
}

function Sun() {
  const texture = useTexture("/textures/sunmap.jpg", (loaded) => {
    (loaded as THREE.Texture).colorSpace = THREE.SRGBColorSpace;
  });
  return (
    <mesh>
      <sphereGeometry args={[0.72, 64, 64]} />
      <meshBasicMaterial 
        map={texture} 
        color="#ffffff" 
      />
      <Html center distanceFactor={12} position={[0, 1.15, 0]}>
        <span className="planet-label">Sun</span>
      </Html>
    </mesh>
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

function Comet({ highlighted, position }: { highlighted: boolean; position: BodyPosition }) {
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
            <meshBasicMaterial color={body.color} opacity={0.14} transparent />
          </mesh>
          <mesh rotation={[Math.PI / 2, 0, 0]}>
            <ringGeometry args={[nucleusRadius * 1.35, nucleusRadius * 1.65, 48]} />
            <meshBasicMaterial color={body.color} opacity={0.85} transparent />
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
      <Html center distanceFactor={10} position={[0, nucleusRadius + 0.42, 0]}>
        <span className={highlighted ? "planet-label planet-label-highlighted" : "planet-label"}>
          {body.name}
        </span>
      </Html>
    </group>
  );
}

function Planet({ highlighted, position }: { highlighted: boolean; position: BodyPosition }) {
  const body = BODY_BY_ID[position.body];
  const scenePosition = toScenePoint(position.positionAu);
  const visualRadius = getVisualRadius(body.radiusKm, highlighted, false);
  const textureUrl = PLANET_TEXTURES[position.body] || "/textures/moon.jpg";
  const texture = useTexture(textureUrl, (loaded) => {
    (loaded as THREE.Texture).colorSpace = THREE.SRGBColorSpace;
  });

  return (
    <group position={scenePosition}>
      {highlighted ? (
        <>
          <mesh>
            <sphereGeometry args={[visualRadius * 2.4, 24, 24]} />
            <meshBasicMaterial color={body.color} opacity={0.14} transparent />
          </mesh>
          <mesh rotation={[Math.PI / 2, 0, 0]}>
            <ringGeometry args={[visualRadius * 1.35, visualRadius * 1.65, 48]} />
            <meshBasicMaterial color={body.color} opacity={0.85} transparent />
          </mesh>
        </>
      ) : null}
      <mesh>
        <sphereGeometry args={[visualRadius, 32, 32]} />
        <meshStandardMaterial
          map={texture}
          emissive={highlighted ? body.color : "#000000"}
          emissiveIntensity={highlighted ? 0.35 : 0}
          roughness={0.8}
        />
      </mesh>
      {position.body === "saturn" ? (
        <mesh rotation={[Math.PI / 2.25, 0.35, 0]}>
          <ringGeometry args={[visualRadius * 1.45, visualRadius * 2.3, 72]} />
          <meshBasicMaterial color="#d5bd83" opacity={0.42} transparent />
        </mesh>
      ) : null}
      <Html center distanceFactor={10} position={[0, visualRadius + 0.38, 0]}>
        <span className={highlighted ? "planet-label planet-label-highlighted" : "planet-label"}>
          {body.name}
        </span>
      </Html>
    </group>
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
      <Html center distanceFactor={14} position={midpoint}>
        <span className="pair-distance-label">{separationAu.toFixed(4)} AU</span>
      </Html>
    </>
  );
}

function FocusOnBodies({
  highlightedBodies,
  positions,
}: {
  highlightedBodies: BodyId[];
  positions: BodyPosition[];
}) {
  const { camera, controls } = useThree();
  const lastFocusKey = useRef<string | null>(null);

  useEffect(() => {
    if (highlightedBodies.length !== 2) {
      lastFocusKey.current = null;
      return;
    }

    const first = positions.find((position) => position.body === highlightedBodies[0]);
    const second = positions.find((position) => position.body === highlightedBodies[1]);

    if (!first || !second || !controls) {
      return;
    }

    const focusKey = `${highlightedBodies.join("-")}-${first.positionAu.x}-${second.positionAu.z}`;
    if (lastFocusKey.current === focusKey) {
      return;
    }

    lastFocusKey.current = focusKey;

    const pointA = toScenePoint(first.positionAu);
    const pointB = toScenePoint(second.positionAu);
    const midpoint: [number, number, number] = [
      (pointA[0] + pointB[0]) / 2,
      (pointA[1] + pointB[1]) / 2,
      (pointA[2] + pointB[2]) / 2,
    ];
    const spread = Math.hypot(
      pointA[0] - pointB[0],
      pointA[1] - pointB[1],
      pointA[2] - pointB[2],
    );
    const cameraHeight = Math.max(spread * 0.75, 10);
    const cameraDepth = Math.max(spread * 0.65, 10);
    const orbitControls = controls as OrbitControlsImpl;

    orbitControls.target.set(midpoint[0], midpoint[1], midpoint[2]);
    camera.position.set(midpoint[0], midpoint[1] + cameraHeight, midpoint[2] + cameraDepth);
    orbitControls.update();
  }, [camera, controls, highlightedBodies, positions]);

  return null;
}

function toScenePoint(positionAu: Vec3): [number, number, number] {
  return [
    positionAu.x * AU_TO_SCENE_UNITS,
    positionAu.z * AU_TO_SCENE_UNITS,
    -positionAu.y * AU_TO_SCENE_UNITS,
  ];
}

function getVisualRadius(radiusKm: number, highlighted = false, isCometBody = false) {
  const base = isCometBody
    ? Math.max(0.28, Math.log10(Math.max(radiusKm, 1)) * 0.12)
    : Math.max(0.16, Math.log10(Math.max(radiusKm, 0.01)) * 0.09 - 0.12);
  return highlighted ? Math.max(base * 1.75, isCometBody ? 0.42 : 0.32) : base;
}

function normalizeVector(vector: [number, number, number]): [number, number, number] {
  const length = Math.hypot(vector[0], vector[1], vector[2]) || 1;
  return [vector[0] / length, vector[1] / length, vector[2] / length];
}

function scaleVector(vector: [number, number, number], scale: number): [number, number, number] {
  return [vector[0] * scale, vector[1] * scale, vector[2] * scale];
}
