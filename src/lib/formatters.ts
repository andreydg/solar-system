import { AU_KM, BODY_BY_ID, type BodyId } from "../domain/solarSystem";
import {
  formatEventTypeLabel,
  locksEarthAsBodyA,
  type CatalogEventType,
} from "../domain/eventTypes";

export function formatDate(time: Date): string {
  return new Intl.DateTimeFormat("en", {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone: "UTC",
  }).format(time);
}

export function formatDistance(distanceKm: number): string {
  if (distanceKm > AU_KM) {
    return `${(distanceKm / 1_000_000).toFixed(1)} million`;
  }

  return Math.round(distanceKm).toLocaleString();
}

export function formatSource(source: string): string {
  switch (source) {
    case "JPL_HORIZONS":
      return "JPL";
    case "VSOP87A_APPROX":
      return "VSOP87A";
    default:
      return source.replace(/_/g, " ");
  }
}

export function formatEventTitle(
  event: { bodyA: BodyId; bodyB: BodyId; type?: CatalogEventType },
  fallbackType?: CatalogEventType,
): string {
  const type = event.type ?? fallbackType;
  if (!type) {
    return "Event";
  }

  if (type === "perihelion") {
    const target = event.bodyA === "earth" ? event.bodyB : event.bodyA;
    return `${formatEventTypeLabel(type)}: ${BODY_BY_ID[target].name}`;
  }

  if (locksEarthAsBodyA(type)) {
    const target = event.bodyA === "earth" ? event.bodyB : event.bodyA;
    return `${formatEventTypeLabel(type)}: ${BODY_BY_ID[target].name} (from Earth)`;
  }

  return `${formatEventTypeLabel(type)}: ${BODY_BY_ID[event.bodyA].name} to ${BODY_BY_ID[event.bodyB].name}`;
}
