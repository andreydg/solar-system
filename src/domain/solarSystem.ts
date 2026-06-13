export type BodyId =
  | "mercury"
  | "venus"
  | "earth"
  | "mars"
  | "jupiter"
  | "saturn"
  | "uranus"
  | "neptune";

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
  },
  {
    id: "venus",
    name: "Venus",
    color: "#e8c07d",
    radiusKm: 6051.8,
    orbitDays: 224.701,
  },
  {
    id: "earth",
    name: "Earth",
    color: "#4b9cff",
    radiusKm: 6371,
    orbitDays: 365.256,
  },
  {
    id: "mars",
    name: "Mars",
    color: "#d96745",
    radiusKm: 3389.5,
    orbitDays: 686.98,
  },
  {
    id: "jupiter",
    name: "Jupiter",
    color: "#d7aa72",
    radiusKm: 69911,
    orbitDays: 4332.59,
  },
  {
    id: "saturn",
    name: "Saturn",
    color: "#e2c47c",
    radiusKm: 58232,
    orbitDays: 10759.22,
  },
  {
    id: "uranus",
    name: "Uranus",
    color: "#91d7e3",
    radiusKm: 25362,
    orbitDays: 30688.5,
  },
  {
    id: "neptune",
    name: "Neptune",
    color: "#5b74d6",
    radiusKm: 24622,
    orbitDays: 60182,
  },
];

export const BODY_BY_ID = Object.fromEntries(
  BODIES.map((body) => [body.id, body]),
) as Record<BodyId, BodyMetadata>;

export function distanceAu(a: Vec3, b: Vec3) {
  const dx = a.x - b.x;
  const dy = a.y - b.y;
  const dz = a.z - b.z;

  return Math.hypot(dx, dy, dz);
}
