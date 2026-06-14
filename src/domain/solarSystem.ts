export type BodyId =
  | "mercury"
  | "venus"
  | "earth"
  | "mars"
  | "jupiter"
  | "saturn"
  | "uranus"
  | "neptune"
  | "ceres"
  | "vesta"
  | "encke"
  | "halley";

export type Vec3 = {
  x: number;
  y: number;
  z: number;
};

export type BodyMetadata = {
  id: BodyId;
  name: string;
  color: string;
  radiusKm: number;
  orbitDays: number;
  kind: "planet" | "asteroid" | "comet";
};

export type BodyPosition = {
  body: BodyId;
  positionAu: Vec3;
};

export type ClosestApproachResult = {
  bodyA: BodyId;
  bodyB: BodyId;
  time: Date;
  distanceAu: number;
  distanceKm: number;
  refinementStepDays: number;
};

export const AU_KM = 149_597_870.7;

export const BODIES: BodyMetadata[] = [
  {
    id: "mercury",
    name: "Mercury",
    color: "#b8b1a7",
    radiusKm: 2439.7,
    orbitDays: 87.969,
    kind: "planet",
  },
  {
    id: "venus",
    name: "Venus",
    color: "#e8c07d",
    radiusKm: 6051.8,
    orbitDays: 224.701,
    kind: "planet",
  },
  {
    id: "earth",
    name: "Earth",
    color: "#4b9cff",
    radiusKm: 6371,
    orbitDays: 365.256,
    kind: "planet",
  },
  {
    id: "mars",
    name: "Mars",
    color: "#d96745",
    radiusKm: 3389.5,
    orbitDays: 686.98,
    kind: "planet",
  },
  {
    id: "jupiter",
    name: "Jupiter",
    color: "#d7aa72",
    radiusKm: 69911,
    orbitDays: 4332.59,
    kind: "planet",
  },
  {
    id: "saturn",
    name: "Saturn",
    color: "#e2c47c",
    radiusKm: 58232,
    orbitDays: 10759.22,
    kind: "planet",
  },
  {
    id: "uranus",
    name: "Uranus",
    color: "#91d7e3",
    radiusKm: 25362,
    orbitDays: 30688.5,
    kind: "planet",
  },
  {
    id: "neptune",
    name: "Neptune",
    color: "#5b74d6",
    radiusKm: 24622,
    orbitDays: 60182,
    kind: "planet",
  },
  {
    id: "ceres",
    name: "Ceres",
    color: "#9ca3af",
    radiusKm: 473,
    orbitDays: 1680,
    kind: "asteroid",
  },
  {
    id: "vesta",
    name: "Vesta",
    color: "#d1d5db",
    radiusKm: 262,
    orbitDays: 1325,
    kind: "asteroid",
  },
  {
    id: "encke",
    name: "Encke",
    color: "#86efac",
    radiusKm: 4.8,
    orbitDays: 1204,
    kind: "comet",
  },
  {
    id: "halley",
    name: "Halley",
    color: "#cbd5e1",
    radiusKm: 11,
    orbitDays: 27520,
    kind: "comet",
  },
];

export const BODY_BY_ID = Object.fromEntries(
  BODIES.map((body) => [body.id, body]),
) as Record<BodyId, BodyMetadata>;

export const SMALL_BODY_IDS: BodyId[] = ["ceres", "vesta", "encke", "halley"];

export function isSmallBody(body: BodyId) {
  return SMALL_BODY_IDS.includes(body);
}

export function isComet(body: BodyId) {
  return BODY_BY_ID[body].kind === "comet";
}

export function distanceAu(a: Vec3, b: Vec3) {
  const dx = a.x - b.x;
  const dy = a.y - b.y;
  const dz = a.z - b.z;

  return Math.hypot(dx, dy, dz);
}
