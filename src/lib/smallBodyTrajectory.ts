import { BODY_BY_ID, type BodyId, type Vec3 } from "../domain/solarSystem";
import { prewarmSmallBodyTrajectories } from "./ephemerisApi";

const DAY_MS = 86_400_000;

export type TimedTrajectoryPoint = {
  positionAu: Vec3;
  time: Date;
};

export type SmallBodyTrajectory = TimedTrajectoryPoint[];

export function sortTrajectory(trajectory: SmallBodyTrajectory): SmallBodyTrajectory {
  return [...trajectory].sort((left, right) => left.time.getTime() - right.time.getTime());
}

export function splitTrajectorySegments(trajectory: SmallBodyTrajectory): Vec3[][] {
  if (trajectory.length < 2) {
    return [];
  }

  const sorted = sortTrajectory(trajectory);
  const segments: Vec3[][] = [[sorted[0].positionAu]];
  const maxGapMs = 45 * 86_400_000;

  for (let index = 1; index < sorted.length; index += 1) {
    const previous = sorted[index - 1];
    const current = sorted[index];
    const gapMs = current.time.getTime() - previous.time.getTime();

    if (gapMs > maxGapMs) {
      if (segments[segments.length - 1].length > 1) {
        segments.push([current.positionAu]);
      } else {
        segments[segments.length - 1] = [current.positionAu];
      }
      continue;
    }

    segments[segments.length - 1].push(current.positionAu);
  }

  return segments.filter((segment) => segment.length > 1);
}

export function chunkScenePoints(points: [number, number, number][], chunkSize = 512): [number, number, number][][] {
  if (points.length < 2) {
    return [];
  }

  const chunks: [number, number, number][][] = [];
  for (let index = 0; index < points.length - 1; index += chunkSize - 1) {
    const chunk = points.slice(index, index + chunkSize);
    if (chunk.length > 1) {
      chunks.push(chunk);
    }
  }

  return chunks;
}

export function buildOrbitTrailSegments(trajectory: SmallBodyTrajectory): Vec3[][] {
  return splitTrajectorySegments(trajectory);
}

function resolveQueryTime(
  trajectory: SmallBodyTrajectory,
  time: Date,
  orbitDays: number,
): Date {
  const sorted = sortTrajectory(trajectory);
  const first = sorted[0];
  const last = sorted[sorted.length - 1];

  if (time.getTime() >= first.time.getTime() && time.getTime() <= last.time.getTime()) {
    return time;
  }

  const daysFromFirst = (time.getTime() - first.time.getTime()) / DAY_MS;
  const wrappedDays = ((daysFromFirst % orbitDays) + orbitDays) % orbitDays;
  const phasedMs = first.time.getTime() + wrappedDays * DAY_MS;

  if (phasedMs <= last.time.getTime()) {
    return new Date(phasedMs);
  }

  const spanDays = (last.time.getTime() - first.time.getTime()) / DAY_MS;
  const offsetDays = ((wrappedDays % (spanDays + 1)) + (spanDays + 1)) % (spanDays + 1);
  return new Date(first.time.getTime() + offsetDays * DAY_MS);
}

function interpolateAtTime(trajectory: SmallBodyTrajectory, time: Date): Vec3 {
  const sorted = sortTrajectory(trajectory);
  const targetMs = time.getTime();
  const last = sorted[sorted.length - 1];

  for (let index = 0; index < sorted.length - 1; index += 1) {
    const start = sorted[index];
    const end = sorted[index + 1];
    const startMs = start.time.getTime();
    const endMs = end.time.getTime();

    if (targetMs < startMs || targetMs > endMs) {
      continue;
    }

    if (endMs === startMs) {
      return start.positionAu;
    }

    const ratio = (targetMs - startMs) / (endMs - startMs);
    return {
      x: start.positionAu.x + ratio * (end.positionAu.x - start.positionAu.x),
      y: start.positionAu.y + ratio * (end.positionAu.y - start.positionAu.y),
      z: start.positionAu.z + ratio * (end.positionAu.z - start.positionAu.z),
    };
  }

  return last.positionAu;
}

export function interpolateTrajectory(
  trajectory: SmallBodyTrajectory,
  time: Date,
  body?: BodyId,
): Vec3 | null {
  if (trajectory.length === 0) {
    return null;
  }

  const orbitDays = body ? BODY_BY_ID[body].orbitDays : null;
  const queryTime =
    orbitDays !== null ? resolveQueryTime(trajectory, time, orbitDays) : time;

  const sorted = sortTrajectory(trajectory);
  const first = sorted[0];
  const last = sorted[sorted.length - 1];
  if (
    orbitDays === null &&
    (queryTime.getTime() < first.time.getTime() || queryTime.getTime() > last.time.getTime())
  ) {
    return null;
  }

  return interpolateAtTime(trajectory, queryTime);
}

export function trajectoryCoversTime(trajectory: SmallBodyTrajectory, time: Date): boolean {
  const sorted = sortTrajectory(trajectory);
  if (sorted.length === 0) {
    return false;
  }

  const targetMs = time.getTime();
  return (
    targetMs >= sorted[0].time.getTime() &&
    targetMs <= sorted[sorted.length - 1].time.getTime()
  );
}

export async function loadSmallBodyTrajectoriesWithRetry(
  attempts = 6,
  delayMs = 2500,
): Promise<Partial<Record<BodyId, SmallBodyTrajectory>>> {
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    const trajectories = await prewarmSmallBodyTrajectories();
    const loadedCount = Object.values(trajectories).filter((trajectory) => (trajectory?.length ?? 0) > 1).length;

    if (loadedCount >= 4) {
      return trajectories;
    }

    await new Promise((resolve) => window.setTimeout(resolve, delayMs));
  }

  return prewarmSmallBodyTrajectories();
}
