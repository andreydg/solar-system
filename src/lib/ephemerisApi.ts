import type { BodyId, BodyPosition } from "../domain/solarSystem";
import { SMALL_BODY_IDS } from "../domain/solarSystem";
import type { SmallBodyTrajectory } from "./smallBodyTrajectory";

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
    positionAu: {
      x: entry.x,
      y: entry.y,
      z: entry.z,
    },
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
    positionAu: {
      x: point.x,
      y: point.y,
      z: point.z,
    },
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
