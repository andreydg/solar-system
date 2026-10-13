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
  validationStatus: "pending" | "validated" | "failed" | "unavailable";
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
  validationStatus: "pending" | "validated" | "failed" | "unavailable";
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
  from?: string;
  to?: string;
  descending?: boolean;
  cursor?: string;
};

export type CatalogEventPage = { events: CatalogEventResult[]; nextCursor: string | null };

export async function getValidatedEvents(query: ValidatedEventQuery = {}): Promise<CatalogEventPage> {
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
  if (query.from) params.set("from", query.from);
  if (query.to) params.set("to", query.to);
  if (query.cursor) params.set("cursor", query.cursor);
  params.set("descending", String(query.descending ?? false));
  params.set("limit", "50");

  const queryString = params.toString();
  const response = await fetch(`/api/events/validated${queryString ? `?${queryString}` : ""}`);
  await assertOk(response);
  const events = (await response.json()) as ApiEvent[];
  return { events: events.map(toCatalogEventResult), nextCursor: response.headers.get("X-Next-Cursor") };
}

export const SEARCH_HORIZON_YEARS = 1000;
export const CATALOG_MIN_TIME = new Date("1600-01-01T00:00:00Z");
export const CATALOG_MAX_TIME = new Date("2999-12-31T23:59:59Z");
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
  const absoluteEnd = minDate(addYears(after, SEARCH_HORIZON_YEARS), CATALOG_MAX_TIME);
  let windowStart = maxDate(after, CATALOG_MIN_TIME);

  while (windowStart < absoluteEnd) {
    const windowEnd = minDate(addYears(windowStart, GENERATE_WINDOW_YEARS), absoluteEnd);
    const queryResult = await queryEvents(type, bodyA, bodyB, windowStart, windowEnd, false);

    if (queryResult.length > 0) {
      return toCatalogEventResult(queryResult[0]);
    }

    const generated = (await generateEvents(type, bodyA, bodyB, windowStart, windowEnd))
      .filter(event => new Date(event.timeUtc) > after && new Date(event.timeUtc) >= windowStart && new Date(event.timeUtc) <= windowEnd)
      .sort((a, b) => Date.parse(a.timeUtc) - Date.parse(b.timeUtc));
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
  const absoluteStart = maxDate(addYears(before, -SEARCH_HORIZON_YEARS), CATALOG_MIN_TIME);
  let windowEnd = minDate(before, CATALOG_MAX_TIME);

  while (windowEnd > absoluteStart) {
    const windowStart = maxDate(addYears(windowEnd, -GENERATE_WINDOW_YEARS), absoluteStart);
    const queryResult = await queryEvents(type, bodyA, bodyB, windowStart, windowEnd, true);

    if (queryResult.length > 0) {
      // Results are ordered ascending by time; the last one is the latest before `before`.
      return toCatalogEventResult(queryResult[queryResult.length - 1]);
    }

    const generated = (await generateEvents(type, bodyA, bodyB, windowStart, windowEnd))
      .filter(event => new Date(event.timeUtc) < before && new Date(event.timeUtc) >= windowStart && new Date(event.timeUtc) <= windowEnd)
      .sort((a, b) => Date.parse(a.timeUtc) - Date.parse(b.timeUtc));
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
  descending: boolean,
) {
  const params = new URLSearchParams({
    type,
    bodyA,
    bodyB,
    from: start.toISOString(),
    to: end.toISOString(),
    limit: "1",
    descending: String(descending),
  });

  // A filtered/corrupt document can leave an empty page with more data.
  for (let page = 0; page < 20; page++) {
    const response = await fetch(`/api/events?${params.toString()}`);
    await assertOk(response);
    const events = (await response.json()) as ApiEvent[];
    if (events.length) return events;
    const cursor = response.headers.get("X-Next-Cursor");
    if (!cursor) return [];
    params.set("cursor", cursor);
  }
  // A bounded regeneration repairs corrupt records and searches this same window.
  // Next/Prev has no narrower-range control, so do not strand users at this cap.
  return [];
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
