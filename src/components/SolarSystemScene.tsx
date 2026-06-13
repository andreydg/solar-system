import { Canvas } from "@react-three/fiber";
import { Html, Line, OrbitControls, Stars } from "@react-three/drei";
import { useMemo } from "react";
import { BODIES, BODY_BY_ID, type BodyPosition, type Vec3 } from "../domain/solarSystem";
import { sampleTrajectory } from "../lib/ephemeris";

const AU_TO_SCENE_UNITS = 3.2;
const ORBIT_SAMPLE_COUNT = 192;
const TRAIL_EPOCH = new Date("2026-01-01T00:00:00Z");

type SolarSystemSceneProps = {
  currentTime: Date;
  positions: BodyPosition[];
};

export default function SolarSystemScene({ currentTime, positions }: SolarSystemSceneProps) {
  return (
    <Canvas camera={{ position: [0, 38, 42], fov: 48 }} dpr={[1, 2]}>
      <color attach="background" args={["#030712"]} />
      <ambientLight intensity={0.35} />
      <pointLight color="#fff2c0" intensity={900} position={[0, 0, 0]} />
      <Stars count={3500} depth={80} factor={4} fade radius={120} speed={0.25} />

      <Sun />
      <OrbitTrails visibleBodyIds={positions.map((position) => position.body)} />
      {positions.map((position) => (
        <Planet key={position.body} position={position} />
      ))}

      <Html position={[-18, 14, -18]} transform>
        <div className="scene-date">{currentTime.toISOString().slice(0, 10)}</div>
      </Html>

      <OrbitControls enableDamping dampingFactor={0.08} maxDistance={130} minDistance={8} />
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

function Planet({ position }: { position: BodyPosition }) {
  const body = BODY_BY_ID[position.body];
  const scenePosition = toScenePoint(position.positionAu);
  const visualRadius = getVisualRadius(body.radiusKm);

  return (
    <group position={scenePosition}>
      <mesh>
        <sphereGeometry args={[visualRadius, 32, 32]} />
        <meshStandardMaterial color={body.color} roughness={0.8} />
      </mesh>
      {position.body === "saturn" ? (
        <mesh rotation={[Math.PI / 2.25, 0.35, 0]}>
          <ringGeometry args={[visualRadius * 1.45, visualRadius * 2.3, 72]} />
          <meshBasicMaterial color="#d5bd83" opacity={0.42} transparent />
        </mesh>
      ) : null}
      <Html center distanceFactor={10} position={[0, visualRadius + 0.38, 0]}>
        <span className="planet-label">{body.name}</span>
      </Html>
    </group>
  );
}

function toScenePoint(positionAu: Vec3): [number, number, number] {
  return [
    positionAu.x * AU_TO_SCENE_UNITS,
    positionAu.z * AU_TO_SCENE_UNITS,
    -positionAu.y * AU_TO_SCENE_UNITS,
  ];
}

function getVisualRadius(radiusKm: number) {
  return Math.max(0.11, Math.log10(radiusKm) * 0.09 - 0.22);
}

function addDays(time: Date, days: number) {
  return new Date(time.getTime() + days * 24 * 60 * 60 * 1000);
}
