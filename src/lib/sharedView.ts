import { BODIES, isSmallBody, type BodyId } from "../domain/solarSystem";
import { EVENT_TYPES, isValidEventPair, type CatalogEventType } from "../domain/eventTypes";

export type SharedView = {
  time: Date; visibleBodies: BodyId[]; eventType: CatalogEventType;
  bodyA: BodyId; bodyB: BodyId; focus: BodyId | null;
};
const bodyIds = new Set<string>(BODIES.map(body => body.id));
export const defaultBodies = BODIES.filter(body => !isSmallBody(body.id)).map(body => body.id);

export function readSharedView(search: string): SharedView | null {
  if (search.length > 2048) return null;
  const params = new URLSearchParams(search);
  if (params.get("v") !== "1") return null;
  const rawTime = params.get("time") ?? "";
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{3})?Z$/.test(rawTime)) return null;
  const time = new Date(rawTime);
  if (!Number.isFinite(time.getTime()) || time.getUTCFullYear() < 1600 || time.getUTCFullYear() > 2999 ||
      time.toISOString().slice(0,19) !== rawTime.slice(0,19)) return null;
  const eventType = (params.get("event") ?? "closestApproach") as CatalogEventType;
  const bodyA = (params.get("a") ?? "earth") as BodyId;
  const bodyB = (params.get("b") ?? "mars") as BodyId;
  if (!EVENT_TYPES.some(type => type.id === eventType) || !bodyIds.has(bodyA) || !bodyIds.has(bodyB) ||
      !isValidEventPair(eventType, bodyA, bodyB)) return null;
  const visible = params.has("bodies") ? (params.get("bodies") || "").split(",").filter(Boolean) : defaultBodies;
  if (visible.some(body => !bodyIds.has(body))) return null;
  const focus = params.get("focus") as BodyId | null;
  if (focus && (!bodyIds.has(focus) || !visible.includes(focus))) return null;
  return { time, eventType, bodyA, bodyB, focus, visibleBodies: [...new Set(visible)] as BodyId[] };
}

export function shareUrl(view: SharedView, origin: string): string {
  const url = new URL("/", origin);
  url.search = new URLSearchParams({ v: "1", time: view.time.toISOString(), event: view.eventType,
    a: view.bodyA, b: view.bodyB, bodies: view.visibleBodies.join(","),
    ...(view.focus ? { focus: view.focus } : {}) }).toString();
  return url.href;
}
