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

// Row-major matrix mapping a scene-space direction back to galactic coordinates
// (inverse of the chain above). Used by the Milky Way glow shader to know, per fragment,
// how close a direction is to the galactic plane / centre. Derived as M(equ->gal) · P,
// where P maps scene [x,y,z] back to equatorial [x,-z,y].
export const SCENE_TO_GALACTIC_ROWMAJOR: readonly number[] = [
  -0.0548755604, -0.4838350155, 0.8734370902,
  0.4941094279, 0.7469822445, 0.44482963,
  -0.867_666_149, 0.4559837762, 0.1980763734,
];

/** Scene direction → galactic (l degrees, b degrees). */
export function sceneDirectionToGalactic(dir: Vec3): { lDeg: number; bDeg: number } {
  const m = SCENE_TO_GALACTIC_ROWMAJOR;
  const gx = m[0] * dir[0] + m[1] * dir[1] + m[2] * dir[2];
  const gy = m[3] * dir[0] + m[4] * dir[1] + m[5] * dir[2];
  const gz = m[6] * dir[0] + m[7] * dir[1] + m[8] * dir[2];
  const bDeg = (Math.asin(Math.max(-1, Math.min(1, gz))) * 180) / Math.PI;
  let lDeg = (Math.atan2(gy, gx) * 180) / Math.PI;
  if (lDeg < 0) {
    lDeg += 360;
  }
  return { lDeg, bDeg };
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
  // Orion (belt + shoulders/feet)
  { name: "Bellatrix", raHours: 5.4188, decDeg: 6.35, mag: 1.64, color: "#cdd9ff" },
  { name: "Alnilam", raHours: 5.6036, decDeg: -1.202, mag: 1.69, color: "#cdd9ff" },
  { name: "Alnitak", raHours: 5.6793, decDeg: -1.943, mag: 1.74, color: "#cdd9ff" },
  { name: "Mintaka", raHours: 5.5334, decDeg: -0.299, mag: 2.25, color: "#cdd9ff" },
  { name: "Saiph", raHours: 5.7959, decDeg: -9.67, mag: 2.07, color: "#cdd9ff" },
  // Ursa Major — the Big Dipper
  { name: "Dubhe", raHours: 11.0621, decDeg: 61.751, mag: 1.79, color: "#ffcf9b" },
  { name: "Merak", raHours: 11.0307, decDeg: 56.382, mag: 2.37, color: "#eef2ff" },
  { name: "Phecda", raHours: 11.8972, decDeg: 53.695, mag: 2.44, color: "#eef2ff" },
  { name: "Megrez", raHours: 12.257, decDeg: 57.033, mag: 3.31, color: "#eef2ff" },
  { name: "Alioth", raHours: 12.9004, decDeg: 55.96, mag: 1.77, color: "#eef2ff" },
  { name: "Mizar", raHours: 13.3987, decDeg: 54.925, mag: 2.04, color: "#eef2ff" },
  { name: "Alkaid", raHours: 13.7923, decDeg: 49.313, mag: 1.86, color: "#cdd9ff" },
  { name: "Kochab", raHours: 14.8451, decDeg: 74.156, mag: 2.08, color: "#ffcf9b" },
  // Cassiopeia — the W
  { name: "Schedar", raHours: 0.6751, decDeg: 56.537, mag: 2.24, color: "#ffcf9b" },
  { name: "Caph", raHours: 0.153, decDeg: 59.15, mag: 2.28, color: "#fff3d6" },
  { name: "Gamma Cas", raHours: 0.9451, decDeg: 60.717, mag: 2.47, color: "#cdd9ff" },
  { name: "Ruchbah", raHours: 1.4304, decDeg: 60.235, mag: 2.68, color: "#f5f6ff" },
  { name: "Segin", raHours: 1.9067, decDeg: 63.67, mag: 3.35, color: "#cdd9ff" },
  // Crux — the Southern Cross
  { name: "Acrux", raHours: 12.4433, decDeg: -63.099, mag: 0.77, color: "#cdd9ff" },
  { name: "Mimosa", raHours: 12.7953, decDeg: -59.689, mag: 1.25, color: "#cdd9ff" },
  { name: "Gacrux", raHours: 12.5194, decDeg: -57.113, mag: 1.63, color: "#ffb56b" },
  { name: "Imai", raHours: 12.2525, decDeg: -58.749, mag: 2.79, color: "#cdd9ff" },
  // Cygnus — the Northern Cross
  { name: "Sadr", raHours: 20.3705, decDeg: 40.257, mag: 2.23, color: "#fff3d6" },
  { name: "Gienah", raHours: 20.7704, decDeg: 33.97, mag: 2.48, color: "#ffcf9b" },
  { name: "Delta Cygni", raHours: 19.7495, decDeg: 45.131, mag: 2.87, color: "#cdd9ff" },
  { name: "Albireo", raHours: 19.5121, decDeg: 27.96, mag: 3.18, color: "#ffcf9b" },
  // Scorpius
  { name: "Shaula", raHours: 17.5601, decDeg: -37.104, mag: 1.62, color: "#cdd9ff" },
  { name: "Sargas", raHours: 17.6219, decDeg: -42.998, mag: 1.86, color: "#fff3d6" },
  { name: "Dschubba", raHours: 16.0056, decDeg: -22.622, mag: 2.29, color: "#cdd9ff" },
  // Leo
  { name: "Denebola", raHours: 11.8177, decDeg: 14.572, mag: 2.11, color: "#eef2ff" },
  { name: "Algieba", raHours: 10.3328, decDeg: 19.841, mag: 2.28, color: "#ffcf9b" },
  // Gemini / Centaurus / Carina / Pegasus / Andromeda anchors
  { name: "Castor", raHours: 7.5766, decDeg: 31.888, mag: 1.58, color: "#eef2ff" },
  { name: "Menkent", raHours: 14.1113, decDeg: -36.37, mag: 2.06, color: "#ffcf9b" },
  { name: "Miaplacidus", raHours: 9.22, decDeg: -69.717, mag: 1.67, color: "#d6e6ff" },
  { name: "Avior", raHours: 8.3752, decDeg: -59.51, mag: 1.86, color: "#ffcf9b" },
  { name: "Alpheratz", raHours: 0.1398, decDeg: 29.09, mag: 2.06, color: "#eef2ff" },
  { name: "Scheat", raHours: 23.0629, decDeg: 28.083, mag: 2.42, color: "#ffb56b" },
  { name: "Markab", raHours: 23.0794, decDeg: 15.205, mag: 2.49, color: "#dce7ff" },
  { name: "Mirach", raHours: 1.1622, decDeg: 35.621, mag: 2.05, color: "#ffb56b" },
  { name: "Hamal", raHours: 2.1191, decDeg: 23.462, mag: 2.0, color: "#ffcf9b" },
  { name: "Algol", raHours: 3.1361, decDeg: 40.956, mag: 2.12, color: "#dce7ff" },
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
  { name: "Andromeda (M31)", raHours: 0.7123, decDeg: 41.269, angularSizeDeg: 3.0, color: "#cdd6e8", points: 240 },
  { name: "Large Magellanic Cloud", raHours: 5.3933, decDeg: -69.756, angularSizeDeg: 5.5, color: "#d7d2c4", points: 360 },
  { name: "Small Magellanic Cloud", raHours: 0.8767, decDeg: -72.8, angularSizeDeg: 3.0, color: "#d7d2c4", points: 180 },
  { name: "Pleiades (M45)", raHours: 3.79, decDeg: 24.117, angularSizeDeg: 1.2, color: "#cfe0ff", points: 70 },
  { name: "Orion Nebula (M42)", raHours: 5.5882, decDeg: -5.391, angularSizeDeg: 0.7, color: "#e6c6d4", points: 120 },
  { name: "Eta Carinae Nebula", raHours: 10.7522, decDeg: -59.866, angularSizeDeg: 1.3, color: "#ffd0c2", points: 130 },
  { name: "Omega Centauri", raHours: 13.4463, decDeg: -47.479, angularSizeDeg: 0.4, color: "#fff1d6", points: 70 },
  { name: "47 Tucanae", raHours: 0.4014, decDeg: -72.081, angularSizeDeg: 0.35, color: "#fff1d6", points: 55 },
  { name: "Beehive (M44)", raHours: 8.6701, decDeg: 19.667, angularSizeDeg: 1.0, color: "#eef2ff", points: 55 },
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

function ambientFieldPoints(count = 3200): RawPoint[] {
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
