import type { CatalogEventType } from "../domain/eventTypes";
import type { BodyId } from "../domain/solarSystem";
import { addYears } from "../lib/timeUtils";

export type { CatalogEventType };

type ApiEvent = {
  id: string;
  type: CatalogEventType;
  bodyA: BodyId;
  bodyB: BodyId;
  timeUtc: string;
  distanceAu: number | null;
  distanceKm: number | null;
  angleDeg: number | null;
  magnitude: number | null;
  source: string;
  validationStatus: "pending" | "validated" | "failed";
  computedTimeUtc: string;
  computedDistanceAu: number | null;
  computedAngleDeg: number | null;
  computedMagnitude: number | null;
  computedSource: string;
  validatedTimeUtc: string | null;
  validatedDistanceAu: number | null;
  validatedAngleDeg: number | null;
  validatedMagnitude: number | null;
  validatedSource: string | null;
  jplCheckedAtUtc: string | null;
  jplDeltaKm: number | null;
  jplRawSummary: string | null;
};

export type CatalogEventResult = {
  angleDeg: number | null;
  bodyA: BodyId;
  bodyB: BodyId;
  computedSource: string;
  computedTime: Date;
  distanceAu: number | null;
  distanceKm: number | null;
  id: string;
  jplCheckedAtUtc: Date | null;
  jplDeltaKm: number | null;
  jplRawSummary: string | null;
  magnitude: number | null;
  source: string;
  time: Date;
  type: CatalogEventType;
  validatedSource: string | null;
  validationStatus: "pending" | "validated" | "failed";
};

export type CatalogOptions = {
  eventTypes: CatalogEventType[];
  bodies: BodyId[];
  notes: string[];
};

export async function getCatalogOptions(): Promise<CatalogOptions> {
  const response = await fetch("/api/catalog/options");
  await assertOk(response);
  return (await response.json()) as CatalogOptions;
}

export type ValidatedEventQuery = {
  type?: CatalogEventType;
  bodyA?: BodyId;
  bodyB?: BodyId;
};

export async function getValidatedEvents(query: ValidatedEventQuery = {}): Promise<CatalogEventResult[]> {
  const params = new URLSearchParams();
  if (query.type) {
    params.set("type", query.type);
  }
  if (query.bodyA) {
    params.set("bodyA", query.bodyA);
  }
  if (query.bodyB) {
    params.set("bodyB", query.bodyB);
  }

  const queryString = params.toString();
  const response = await fetch(`/api/events/validated${queryString ? `?${queryString}` : ""}`);
  await assertOk(response);
  const events = (await response.json()) as ApiEvent[];
  return events.map(toCatalogEventResult);
}

export const SEARCH_HORIZON_YEARS = 1000;
const GENERATE_WINDOW_YEARS = 25;

export function isJplSource(source: string | null | undefined) {
  return source === "JPL_HORIZONS";
}

export async function findNextEvent(
  type: CatalogEventType,
  bodyA: BodyId,
  bodyB: BodyId,
  after: Date,
): Promise<CatalogEventResult | null> {
  const absoluteEnd = addYears(after, SEARCH_HORIZON_YEARS);
  let windowStart = after;

  while (windowStart < absoluteEnd) {
    const windowEnd = minDate(addYears(windowStart, GENERATE_WINDOW_YEARS), absoluteEnd);
    const queryResult = await queryEvents(type, bodyA, bodyB, windowStart, windowEnd);

    if (queryResult.length > 0) {
      return toCatalogEventResult(queryResult[0]);
    }

    const generated = await generateEvents(type, bodyA, bodyB, windowStart, windowEnd);
    if (generated.length > 0) {
      return toCatalogEventResult(generated[0]);
    }

    windowStart = windowEnd;
  }

  return null;
}

export async function findPreviousEvent(
  type: CatalogEventType,
  bodyA: BodyId,
  bodyB: BodyId,
  before: Date,
): Promise<CatalogEventResult | null> {
  const absoluteStart = addYears(before, -SEARCH_HORIZON_YEARS);
  let windowEnd = before;

  while (windowEnd > absoluteStart) {
    const windowStart = maxDate(addYears(windowEnd, -GENERATE_WINDOW_YEARS), absoluteStart);
    const queryResult = await queryEvents(type, bodyA, bodyB, windowStart, windowEnd);

    if (queryResult.length > 0) {
      // Results are ordered ascending by time; the last one is the latest before `before`.
      return toCatalogEventResult(queryResult[queryResult.length - 1]);
    }

    const generated = await generateEvents(type, bodyA, bodyB, windowStart, windowEnd);
    if (generated.length > 0) {
      return toCatalogEventResult(generated[generated.length - 1]);
    }

    windowEnd = windowStart;
  }

  return null;
}

async function queryEvents(
  type: CatalogEventType,
  bodyA: BodyId,
  bodyB: BodyId,
  start: Date,
  end: Date,
) {
  const params = new URLSearchParams({
    type,
    bodyA,
    bodyB,
    from: start.toISOString(),
    to: end.toISOString(),
  });

  const response = await fetch(`/api/events?${params.toString()}`);
  await assertOk(response);
  return (await response.json()) as ApiEvent[];
}

async function generateEvents(
  type: CatalogEventType,
  bodyA: BodyId,
  bodyB: BodyId,
  start: Date,
  end: Date,
) {
  const response = await fetch("/api/events/generate", {
    body: JSON.stringify({
      type,
      bodyA,
      bodyB,
      from: start.toISOString(),
      to: end.toISOString(),
    }),
    headers: {
      "Content-Type": "application/json",
    },
    method: "POST",
  });

  await assertOk(response);
  return (await response.json()) as ApiEvent[];
}

function toCatalogEventResult(event: ApiEvent): CatalogEventResult {
  if (
    (event.type === "closestApproach" || event.type === "farthestApproach")
    && (event.distanceAu === null || event.distanceKm === null)
  ) {
    throw new Error(`Catalog event ${event.id} did not include distance data`);
  }

  return {
    angleDeg: event.angleDeg,
    bodyA: event.bodyA,
    bodyB: event.bodyB,
    computedSource: event.computedSource,
    computedTime: new Date(event.computedTimeUtc),
    distanceAu: event.distanceAu,
    distanceKm: event.distanceKm,
    id: event.id,
    jplCheckedAtUtc: event.jplCheckedAtUtc ? new Date(event.jplCheckedAtUtc) : null,
    jplDeltaKm: event.jplDeltaKm,
    jplRawSummary: event.jplRawSummary,
    magnitude: event.magnitude,
    source: event.source,
    time: new Date(event.timeUtc),
    type: event.type,
    validatedSource: event.validatedSource,
    validationStatus: event.validationStatus,
  };
}

function minDate(left: Date, right: Date) {
  return left < right ? left : right;
}

function maxDate(left: Date, right: Date) {
  return left > right ? left : right;
}

async function assertOk(response: Response) {
  if (!response.ok) {
    let msg = `Event catalog request failed with ${response.status}`;
    try {
      const body = await response.json() as Record<string, unknown>;
      if (typeof body.message === "string") {
        msg = body.message;
      }
    } catch {
      // ignore
    }
    throw new Error(msg);
  }
}
