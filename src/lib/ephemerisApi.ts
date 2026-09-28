import type { BodyId, BodyPosition, Vec3 } from "../domain/solarSystem";
import { SMALL_BODY_IDS } from "../domain/solarSystem";
import type { SmallBodyTrajectory } from "./smallBodyTrajectory";

// The backend's vectors (JPL Horizons, VSOP elements) are J2000 *ecliptic*, while the scene and
// astronomy-engine's planets are J2000 *equatorial*. The frames differ by a rotation about the
// equinox (x) axis through the J2000 obliquity: 84381.448″ (IAU 1976), the value Horizons uses to
// define its ecliptic. (astronomy-engine uses the IAU 2006 84381.406″; 0.04″ apart.)
const J2000_OBLIQUITY_RAD = (84381.448 / 3600) * (Math.PI / 180);
const COS_OBLIQUITY = Math.cos(J2000_OBLIQUITY_RAD);
const SIN_OBLIQUITY = Math.sin(J2000_OBLIQUITY_RAD);

export function eclipticToEquatorial({ x, y, z }: Vec3): Vec3 {
  return {
    x,
    y: y * COS_OBLIQUITY - z * SIN_OBLIQUITY,
    z: y * SIN_OBLIQUITY + z * COS_OBLIQUITY,
  };
}

type PositionResponse = {
  body: BodyId;
  source: string;
  x: number;
  y: number;
  z: number;
};

type TrajectoryPointResponse = {
  timeUtc: string;
  x: number;
  y: number;
  z: number;
};

export async function getBackendBodyPositions(bodies: BodyId[], time: Date): Promise<BodyPosition[]> {
  if (bodies.length === 0) {
    return [];
  }

  const params = new URLSearchParams({ time: time.toISOString() });
  for (const body of bodies) {
    params.append("bodies", body);
  }

  const response = await fetch(`/api/ephemeris/positions?${params.toString()}`);
  if (!response.ok) {
    throw new Error(`Ephemeris request failed with ${response.status}`);
  }

  const payload = (await response.json()) as PositionResponse[];
  return payload.map((entry) => ({
    body: entry.body,
    positionAu: eclipticToEquatorial(entry),
  }));
}

export async function getBackendBodyTrajectory(body: BodyId): Promise<SmallBodyTrajectory> {
  const response = await fetch(`/api/ephemeris/trajectory?body=${encodeURIComponent(body)}`);
  if (!response.ok) {
    throw new Error(`Trajectory request failed with ${response.status}`);
  }

  const payload = (await response.json()) as TrajectoryPointResponse[];
  const points = payload.map((point) => ({
    time: new Date(point.timeUtc),
    positionAu: eclipticToEquatorial(point),
  }));
  return points.sort((left, right) => left.time.getTime() - right.time.getTime());
}

export async function prewarmSmallBodyTrajectories(): Promise<Partial<Record<BodyId, SmallBodyTrajectory>>> {
  const entries = await Promise.all(
    SMALL_BODY_IDS.map(async (body) => {
      try {
        const trajectory = await getBackendBodyTrajectory(body);
        return [body, trajectory] as const;
      } catch {
        return [body, []] as const;
      }
    }),
  );

  return Object.fromEntries(entries);
}
