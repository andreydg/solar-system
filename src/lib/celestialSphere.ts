// Builds a "roughly correct" backdrop sky for the heliocentric scene.
//
// Planet positions come from astronomy-engine HelioVector, which returns J2000 *equatorial*
// (EQJ) coordinates. Star catalogues give RA/Dec in the same J2000 equatorial frame, so a
// star direction needs no obliquity rotation — only the same axis remap the scene applies to
// planet positions (toScenePoint: [x, z, -y]). The ecliptic therefore reads as tilted ~23.4°
// from horizontal, which is correct because "up" here is the celestial north pole.

export type Vec3 = [number, number, number];

// Far enough to sit behind the solar system (camera maxDistance is 220) but inside the camera
// far plane (1000).
export const SKY_RADIUS = 500;

const DEG2RAD = Math.PI / 180;

/** Equatorial J2000 (RA hours, Dec degrees) → unit direction in the scene's axis convention. */
export function equatorialToSceneDirection(raHours: number, decDeg: number): Vec3 {
  const ra = raHours * 15 * DEG2RAD;
  const dec = decDeg * DEG2RAD;
  // EQJ unit vector: x→March equinox, y→RA 6h, z→north celestial pole.
  const x = Math.cos(dec) * Math.cos(ra);
  const y = Math.cos(dec) * Math.sin(ra);
  const z = Math.sin(dec);
  // Same remap as toScenePoint in the scene.
  return [x, z, -y];
}

export function equatorialToScene(raHours: number, decDeg: number, radius = SKY_RADIUS): Vec3 {
  const [x, y, z] = equatorialToSceneDirection(raHours, decDeg);
  return [x * radius, y * radius, z * radius];
}

// Galactic → equatorial (J2000) rotation. Rows map a galactic unit vector to equatorial.
const GALACTIC_TO_EQUATORIAL: readonly [Vec3, Vec3, Vec3] = [
  [-0.0548755604, 0.4941094279, -0.867_666_149],
  [-0.8734370902, -0.44482963, -0.1980763734],
  [-0.4838350155, 0.7469822445, 0.4559837762],
];

/** Galactic (l, b degrees) → unit direction in the scene's axis convention. */
export function galacticToSceneDirection(lDeg: number, bDeg: number): Vec3 {
  const l = lDeg * DEG2RAD;
  const b = bDeg * DEG2RAD;
  const g: Vec3 = [Math.cos(b) * Math.cos(l), Math.cos(b) * Math.sin(l), Math.sin(b)];
  const m = GALACTIC_TO_EQUATORIAL;
  const ex = m[0][0] * g[0] + m[0][1] * g[1] + m[0][2] * g[2];
  const ey = m[1][0] * g[0] + m[1][1] * g[1] + m[1][2] * g[2];
  const ez = m[2][0] * g[0] + m[2][1] * g[1] + m[2][2] * g[2];
  return [ex, ez, -ey];
}

export type BrightStar = {
  name: string;
  raHours: number;
  decDeg: number;
  mag: number;
  color: string;
};

// ~20 brightest naked-eye stars (J2000), plus Polaris as a north-pole reference.
export const BRIGHT_STARS: BrightStar[] = [
  { name: "Sirius", raHours: 6.7525, decDeg: -16.716, mag: -1.46, color: "#cfe0ff" },
  { name: "Canopus", raHours: 6.3992, decDeg: -52.696, mag: -0.74, color: "#fff7ea" },
  { name: "Rigil Kentaurus", raHours: 14.66, decDeg: -60.835, mag: -0.27, color: "#fff1dd" },
  { name: "Arcturus", raHours: 14.261, decDeg: 19.182, mag: -0.05, color: "#ffd2a1" },
  { name: "Vega", raHours: 18.6156, decDeg: 38.784, mag: 0.03, color: "#d6e6ff" },
  { name: "Capella", raHours: 5.2782, decDeg: 45.998, mag: 0.08, color: "#fff1cf" },
  { name: "Rigel", raHours: 5.2423, decDeg: -8.202, mag: 0.13, color: "#cdd9ff" },
  { name: "Procyon", raHours: 7.655, decDeg: 5.225, mag: 0.34, color: "#f5f6ff" },
  { name: "Betelgeuse", raHours: 5.9195, decDeg: 7.407, mag: 0.5, color: "#ffb56b" },
  { name: "Achernar", raHours: 1.6286, decDeg: -57.237, mag: 0.46, color: "#c9d7ff" },
  { name: "Hadar", raHours: 14.0637, decDeg: -60.373, mag: 0.61, color: "#c9d7ff" },
  { name: "Altair", raHours: 19.8464, decDeg: 8.868, mag: 0.76, color: "#eef2ff" },
  { name: "Aldebaran", raHours: 4.5987, decDeg: 16.509, mag: 0.85, color: "#ffcf9b" },
  { name: "Antares", raHours: 16.4901, decDeg: -26.432, mag: 1.06, color: "#ffb07a" },
  { name: "Spica", raHours: 13.4199, decDeg: -11.161, mag: 0.97, color: "#c8d6ff" },
  { name: "Pollux", raHours: 7.7553, decDeg: 28.026, mag: 1.14, color: "#ffd9ad" },
  { name: "Fomalhaut", raHours: 22.9608, decDeg: -29.622, mag: 1.16, color: "#eaf0ff" },
  { name: "Deneb", raHours: 20.6905, decDeg: 45.28, mag: 1.25, color: "#dce7ff" },
  { name: "Regulus", raHours: 10.1395, decDeg: 11.967, mag: 1.4, color: "#d3e0ff" },
  { name: "Polaris", raHours: 2.5302, decDeg: 89.264, mag: 1.98, color: "#fff2d4" },
];

export type DeepSkyObject = {
  name: string;
  raHours: number;
  decDeg: number;
  angularSizeDeg: number;
  color: string;
  points: number;
};

// Naked-eye fuzzy objects visible from a dark site (or from space).
export const DEEP_SKY: DeepSkyObject[] = [
  { name: "Andromeda (M31)", raHours: 0.7123, decDeg: 41.269, angularSizeDeg: 3.0, color: "#cdd6e8", points: 220 },
  { name: "Large Magellanic Cloud", raHours: 5.3933, decDeg: -69.756, angularSizeDeg: 5.5, color: "#d7d2c4", points: 320 },
  { name: "Small Magellanic Cloud", raHours: 0.8767, decDeg: -72.8, angularSizeDeg: 3.0, color: "#d7d2c4", points: 160 },
  { name: "Pleiades (M45)", raHours: 3.79, decDeg: 24.117, angularSizeDeg: 1.2, color: "#cfe0ff", points: 70 },
];

export type StarField = {
  positions: Float32Array;
  colors: Float32Array;
  sizes: Float32Array;
};

type RawPoint = { dir: Vec3; color: Vec3; size: number };

function hexToRgb(hex: string): Vec3 {
  const value = hex.replace("#", "");
  return [
    parseInt(value.slice(0, 2), 16) / 255,
    parseInt(value.slice(2, 4), 16) / 255,
    parseInt(value.slice(4, 6), 16) / 255,
  ];
}

function scale(color: Vec3, factor: number): Vec3 {
  return [color[0] * factor, color[1] * factor, color[2] * factor];
}

// Deterministic PRNG so the field is stable across renders.
function mulberry32(seed: number): () => number {
  let state = seed;
  return () => {
    state |= 0;
    state = (state + 0x6d2b79f5) | 0;
    let t = Math.imul(state ^ (state >>> 15), 1 | state);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function gaussian(rng: () => number): number {
  const u = Math.max(rng(), 1e-9);
  const v = rng();
  return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
}

function normalize(v: Vec3): Vec3 {
  const length = Math.hypot(v[0], v[1], v[2]) || 1;
  return [v[0] / length, v[1] / length, v[2] / length];
}

// Two unit vectors orthogonal to `dir`, for scattering a blob on the sphere.
function tangentBasis(dir: Vec3): [Vec3, Vec3] {
  const reference: Vec3 = Math.abs(dir[1]) < 0.95 ? [0, 1, 0] : [1, 0, 0];
  const u = normalize([
    dir[1] * reference[2] - dir[2] * reference[1],
    dir[2] * reference[0] - dir[0] * reference[2],
    dir[0] * reference[1] - dir[1] * reference[0],
  ]);
  const v = normalize([
    dir[1] * u[2] - dir[2] * u[1],
    dir[2] * u[0] - dir[0] * u[2],
    dir[0] * u[1] - dir[1] * u[0],
  ]);
  return [u, v];
}

function magnitudeToSize(mag: number): number {
  return Math.min(9, Math.max(3, 7.5 - 1.2 * mag));
}

function magnitudeToBrightness(mag: number): number {
  return Math.min(1, Math.max(0.45, 1.05 - 0.13 * (mag + 1.5)));
}

function brightStarPoints(): RawPoint[] {
  return BRIGHT_STARS.map((star) => ({
    dir: equatorialToSceneDirection(star.raHours, star.decDeg),
    color: scale(hexToRgb(star.color), magnitudeToBrightness(star.mag)),
    size: magnitudeToSize(star.mag),
  }));
}

function milkyWayPoints(count = 2600): RawPoint[] {
  const rng = mulberry32(0x5f3a21);
  const points: RawPoint[] = [];
  for (let i = 0; i < count; i += 1) {
    // 30% bias toward the bright galactic-centre bulge near l = 0 (Sagittarius).
    const towardCentre = rng() < 0.3;
    const l = towardCentre ? gaussian(rng) * 28 : rng() * 360;
    const b = gaussian(rng) * (towardCentre ? 5 : 7.5);
    const brightness = 0.18 + rng() * 0.22;
    const warm = 0.9 + rng() * 0.2;
    points.push({
      dir: galacticToSceneDirection(l, b),
      color: [brightness * warm, brightness * 0.98, brightness * 1.05],
      size: 1 + rng() * 1.1,
    });
  }
  return points;
}

function ambientFieldPoints(count = 1100): RawPoint[] {
  const rng = mulberry32(0x1234abcd);
  const points: RawPoint[] = [];
  for (let i = 0; i < count; i += 1) {
    // Uniform on the sphere.
    const z = rng() * 2 - 1;
    const phi = rng() * 2 * Math.PI;
    const r = Math.sqrt(Math.max(0, 1 - z * z));
    const dir = normalize([r * Math.cos(phi), z, r * Math.sin(phi)]);
    const brightness = 0.22 + rng() * 0.3;
    const blue = 0.95 + rng() * 0.18;
    points.push({
      dir,
      color: [brightness * 0.95, brightness * 0.97, brightness * blue],
      size: 1 + rng() * 0.8,
    });
  }
  return points;
}

function deepSkyPoints(): RawPoint[] {
  const rng = mulberry32(0x0ce5a7);
  const points: RawPoint[] = [];
  for (const object of DEEP_SKY) {
    const centre = equatorialToSceneDirection(object.raHours, object.decDeg);
    const [u, v] = tangentBasis(centre);
    const spread = object.angularSizeDeg * DEG2RAD;
    const base = hexToRgb(object.color);
    for (let i = 0; i < object.points; i += 1) {
      const du = gaussian(rng) * spread;
      const dv = gaussian(rng) * spread;
      const dir = normalize([
        centre[0] + u[0] * du + v[0] * dv,
        centre[1] + u[1] * du + v[1] * dv,
        centre[2] + u[2] * du + v[2] * dv,
      ]);
      // Fainter toward the edges of the blob for a soft glow.
      const falloff = Math.exp(-(du * du + dv * dv) / (2 * spread * spread));
      points.push({
        dir,
        color: scale(base, 0.22 + 0.4 * falloff),
        size: 1 + rng() * 1.4,
      });
    }
  }
  return points;
}

/** Combined backdrop: ambient field + Milky Way + deep-sky objects + bright named stars. */
export function buildStarField(radius = SKY_RADIUS): StarField {
  const all = [
    ...ambientFieldPoints(),
    ...milkyWayPoints(),
    ...deepSkyPoints(),
    ...brightStarPoints(),
  ];

  const positions = new Float32Array(all.length * 3);
  const colors = new Float32Array(all.length * 3);
  const sizes = new Float32Array(all.length);

  all.forEach((point, index) => {
    positions[index * 3] = point.dir[0] * radius;
    positions[index * 3 + 1] = point.dir[1] * radius;
    positions[index * 3 + 2] = point.dir[2] * radius;
    colors[index * 3] = point.color[0];
    colors[index * 3 + 1] = point.color[1];
    colors[index * 3 + 2] = point.color[2];
    sizes[index] = point.size;
  });

  return { positions, colors, sizes };
}
