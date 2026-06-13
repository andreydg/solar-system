import * as Astronomy from "astronomy-engine";
import {
  AU_KM,
  type BodyId,
  type BodyPosition,
  type ClosestApproachResult,
  distanceAu,
} from "../domain/solarSystem";

type AstronomyBody = unknown;

type AstronomyVector = {
  x: number;
  y: number;
  z: number;
};

const astronomyApi = Astronomy as typeof Astronomy & {
  Body: Record<string, AstronomyBody>;
  HelioVector: (body: AstronomyBody, time: Date) => AstronomyVector;
};

const ASTRO_BODY_BY_ID: Record<BodyId, AstronomyBody> = {
  mercury: astronomyApi.Body.Mercury,
  venus: astronomyApi.Body.Venus,
  earth: astronomyApi.Body.Earth,
  mars: astronomyApi.Body.Mars,
  jupiter: astronomyApi.Body.Jupiter,
  saturn: astronomyApi.Body.Saturn,
  uranus: astronomyApi.Body.Uranus,
  neptune: astronomyApi.Body.Neptune,
};

export function getBodyPosition(body: BodyId, time: Date): BodyPosition {
  const vector = astronomyApi.HelioVector(ASTRO_BODY_BY_ID[body], time);

  return {
    body,
    positionAu: {
      x: vector.x,
      y: vector.y,
      z: vector.z,
    },
  };
}

export function getBodyPositions(bodies: BodyId[], time: Date): BodyPosition[] {
  return bodies.map((body) => getBodyPosition(body, time));
}

export function sampleTrajectory(
  body: BodyId,
  start: Date,
  end: Date,
  sampleCount: number,
) {
  const count = Math.max(2, sampleCount);
  const startMs = start.getTime();
  const endMs = end.getTime();
  const stepMs = (endMs - startMs) / (count - 1);

  return Array.from({ length: count }, (_, index) => {
    const time = new Date(startMs + stepMs * index);
    return getBodyPosition(body, time).positionAu;
  });
}

export function findClosestApproach(
  bodyA: BodyId,
  bodyB: BodyId,
  start: Date,
  end: Date,
): ClosestApproachResult {
  let windowStart = start;
  let windowEnd = end;
  let stepDays = 5;
  let best = findClosestInWindow(bodyA, bodyB, windowStart, windowEnd, stepDays);

  for (const nextStepDays of [1, 0.25, 1 / 24]) {
    windowStart = addDays(best.time, -stepDays * 2);
    windowEnd = addDays(best.time, stepDays * 2);
    stepDays = nextStepDays;
    best = findClosestInWindow(bodyA, bodyB, windowStart, windowEnd, stepDays);
  }

  return {
    bodyA,
    bodyB,
    time: best.time,
    distanceAu: best.distanceAu,
    distanceKm: best.distanceAu * AU_KM,
    refinementStepDays: stepDays,
  };
}

function findClosestInWindow(
  bodyA: BodyId,
  bodyB: BodyId,
  start: Date,
  end: Date,
  stepDays: number,
) {
  const stepMs = stepDays * 24 * 60 * 60 * 1000;
  const startMs = start.getTime();
  const endMs = end.getTime();
  let bestTime = start;
  let bestDistance = Number.POSITIVE_INFINITY;

  for (let timeMs = startMs; timeMs <= endMs; timeMs += stepMs) {
    const time = new Date(timeMs);
    const positionA = getBodyPosition(bodyA, time).positionAu;
    const positionB = getBodyPosition(bodyB, time).positionAu;
    const distance = distanceAu(positionA, positionB);

    if (distance < bestDistance) {
      bestDistance = distance;
      bestTime = time;
    }
  }

  return {
    time: bestTime,
    distanceAu: bestDistance,
  };
}

function addDays(time: Date, days: number) {
  return new Date(time.getTime() + days * 24 * 60 * 60 * 1000);
}
