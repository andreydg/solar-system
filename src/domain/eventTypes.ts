import { BODIES, SMALL_BODY_IDS, type BodyId } from "./solarSystem";

export type CatalogEventType =
  | "brightestApproach"
  | "closestApproach"
  | "conjunction"
  | "farthestApproach"
  | "greatestElongation"
  | "opposition"
  | "perihelion"
  | "retrogradeEnd"
  | "retrogradeStart"
  | "stationary"
  | "transit";

export type EventPairing = "any" | "earth" | "earthInner" | "smallBody";

export type EventTypeDefinition = {
  description: string;
  id: CatalogEventType;
  innerTargets?: BodyId[];
  label: string;
  pairing: EventPairing;
  smallBodyTargets?: BodyId[];
};

export const EVENT_TYPES: EventTypeDefinition[] = [
  {
    id: "closestApproach",
    label: "Closest approach",
    pairing: "any",
    description:
      "When two bodies are nearest in space. Works for any catalog body pair and reports the separation distance.",
  },
  {
    id: "farthestApproach",
    label: "Farthest approach",
    pairing: "any",
    description:
      "When two bodies are farthest apart in space during their orbital cycle. The opposite of closest approach.",
  },
  {
    id: "opposition",
    label: "Opposition",
    pairing: "earth",
    description:
      "When a body appears opposite the Sun in Earth's sky (~180° apart). Often the best time to observe outer planets, asteroids, and comets.",
  },
  {
    id: "conjunction",
    label: "Conjunction",
    pairing: "earth",
    description:
      "When a body appears near the Sun in Earth's sky (~0° apart). Usually hard to see because of daylight.",
  },
  {
    id: "greatestElongation",
    label: "Greatest elongation",
    pairing: "earthInner",
    innerTargets: ["mercury", "venus"],
    description:
      "When Mercury or Venus is farthest from the Sun in the sky. Best time to see an inner planet in the evening or morning.",
  },
  {
    id: "stationary",
    label: "Stationary",
    pairing: "earth",
    description:
      "When a body's apparent motion across the sky briefly stops before changing direction, as seen from Earth.",
  },
  {
    id: "retrogradeStart",
    label: "Retrograde start",
    pairing: "earth",
    description:
      "When a body begins moving westward relative to the stars, reversing its usual eastward motion.",
  },
  {
    id: "retrogradeEnd",
    label: "Retrograde end",
    pairing: "earth",
    description:
      "When a body resumes its normal eastward motion after a retrograde loop.",
  },
  {
    id: "transit",
    label: "Transit",
    pairing: "earthInner",
    innerTargets: ["mercury", "venus"],
    description:
      "When Mercury or Venus passes directly in front of the Sun as seen from Earth. Requires an inferior conjunction, not when the planet is behind the Sun.",
  },
  {
    id: "brightestApproach",
    label: "Brightest approach",
    pairing: "earth",
    description:
      "When a planet appears brightest from Earth, based on distance and viewing geometry. Good for planning observations.",
  },
  {
    id: "perihelion",
    label: "Perihelion",
    pairing: "smallBody",
    smallBodyTargets: SMALL_BODY_IDS,
    description:
      "When a comet or asteroid is closest to the Sun. Reports the heliocentric distance at perihelion.",
  },
];

export const EVENT_TYPE_BY_ID = Object.fromEntries(
  EVENT_TYPES.map((eventType) => [eventType.id, eventType]),
) as Record<CatalogEventType, EventTypeDefinition>;

export function requiresEarth(type: CatalogEventType) {
  return EVENT_TYPE_BY_ID[type].pairing !== "any";
}

export function getEventTargetOptions(type: CatalogEventType): BodyId[] {
  const definition = EVENT_TYPE_BY_ID[type];

  if (definition.pairing === "any") {
    return BODIES.map((body) => body.id);
  }

  if (definition.pairing === "earthInner") {
    return definition.innerTargets ?? [];
  }

  if (definition.pairing === "smallBody") {
    return definition.smallBodyTargets ?? SMALL_BODY_IDS;
  }

  return BODIES.map((body) => body.id).filter((body) => body !== "earth");
}

export function locksEarthAsBodyA(type: CatalogEventType) {
  return EVENT_TYPE_BY_ID[type].pairing !== "any";
}

export function isValidEventPair(type: CatalogEventType, bodyA: BodyId, bodyB: BodyId) {
  if (bodyA === bodyB) {
    return false;
  }

  const definition = EVENT_TYPE_BY_ID[type];

  if (definition.pairing === "any") {
    return true;
  }

  if (bodyA !== "earth") {
    return false;
  }

  if (definition.pairing === "earthInner") {
    return definition.innerTargets?.includes(bodyB) ?? false;
  }

  if (definition.pairing === "smallBody") {
    return definition.smallBodyTargets?.includes(bodyB) ?? false;
  }

  return bodyB !== "earth";
}

export function formatEventTypeLabel(type: CatalogEventType) {
  return EVENT_TYPE_BY_ID[type].label.toLowerCase();
}

export function formatEventPairingNote(type: CatalogEventType) {
  const definition = EVENT_TYPE_BY_ID[type];

  switch (definition.pairing) {
    case "any":
      return "Any two catalog bodies";
    case "earth":
      return "Earth observer + one target body";
    case "earthInner":
      return "Earth observer + Mercury or Venus";
    case "smallBody":
      return "Earth observer + Ceres, Vesta, Encke, or Halley";
  }
}

export const JPL_VALIDATED_EVENT_TYPES = new Set<CatalogEventType>([
  "closestApproach",
  "farthestApproach",
  "opposition",
  "conjunction",
  "greatestElongation",
  "perihelion",
]);
