import { Canvas, useThree } from "@react-three/fiber";
import { Html, Line, OrbitControls, Stars } from "@react-three/drei";
import { useEffect, useMemo, useRef } from "react";
import { BODIES, BODY_BY_ID, type BodyId, type BodyPosition, type Vec3 } from "../domain/solarSystem";
import { sampleTrajectory } from "../lib/ephemeris";
import type { OrbitControls as OrbitControlsImpl } from "three-stdlib";

const AU_TO_SCENE_UNITS = 3.2;
const ORBIT_SAMPLE_COUNT = 192;
const TRAIL_EPOCH = new Date("2026-01-01T00:00:00Z");

type SolarSystemSceneProps = {
  currentTime: Date;
  highlightedBodies: BodyId[];
  positions: BodyPosition[];
};

export default function SolarSystemScene({
  currentTime,
  highlightedBodies,
  positions,
}: SolarSystemSceneProps) {
  const highlightedSet = useMemo(() => new Set(highlightedBodies), [highlightedBodies]);

  return (
    <Canvas camera={{ position: [0, 38, 42], fov: 48 }} dpr={[1, 2]}>
      <color attach="background" args={["#030712"]} />
      <ambientLight intensity={0.35} />
      <pointLight color="#fff2c0" intensity={900} position={[0, 0, 0]} />
      <Stars count={3500} depth={80} factor={4} fade radius={120} speed={0.25} />

      <Sun />
      <OrbitTrails visibleBodyIds={positions.map((position) => position.body)} />
      <EventPairLine highlightedBodies={highlightedBodies} positions={positions} />
      {positions.map((position) => (
        <Planet
          highlighted={highlightedSet.has(position.body)}
          key={position.body}
          position={position}
        />
      ))}

      <Html position={[-18, 14, -18]} transform>
        <div className="scene-date">{currentTime.toISOString().slice(0, 10)}</div>
      </Html>

      <OrbitControls enableDamping dampingFactor={0.08} maxDistance={220} minDistance={4} />
      <FocusOnBodies highlightedBodies={highlightedBodies} positions={positions} />
    </Canvas>
  );
}

function Sun() {
  return (
    <mesh>
      <sphereGeometry args={[0.72, 48, 48]} />
      <meshBasicMaterial color="#ffd166" />
      <Html center distanceFactor={12} position={[0, 1.15, 0]}>
        <span className="planet-label">Sun</span>
      </Html>
    </mesh>
  );
}

function OrbitTrails({ visibleBodyIds }: { visibleBodyIds: string[] }) {
  const visibleSet = useMemo(() => new Set(visibleBodyIds), [visibleBodyIds]);

  const trails = useMemo(
    () =>
      BODIES.map((body) => {
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
    </>
  );
}

function Planet({ highlighted, position }: { highlighted: boolean; position: BodyPosition }) {
  const body = BODY_BY_ID[position.body];
  const scenePosition = toScenePoint(position.positionAu);
  const visualRadius = getVisualRadius(body.radiusKm, highlighted);

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
          color={body.color}
          emissive={highlighted ? body.color : "#000000"}
          emissiveIntensity={highlighted ? 0.45 : 0}
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

  return (
    <Line
      color="#93c5fd"
      lineWidth={1.5}
      opacity={0.75}
      points={[toScenePoint(first.positionAu), toScenePoint(second.positionAu)]}
      transparent
    />
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

function getVisualRadius(radiusKm: number, highlighted = false) {
  const base = Math.max(0.11, Math.log10(radiusKm) * 0.09 - 0.22);
  return highlighted ? Math.max(base * 1.75, 0.3) : base;
}

function addDays(time: Date, days: number) {
  return new Date(time.getTime() + days * 24 * 60 * 60 * 1000);
}
