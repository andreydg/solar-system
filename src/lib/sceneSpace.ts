import type { Vec3 } from "../domain/solarSystem";

export type ScenePoint = [number, number, number];

// Scene distances are a fixed multiple of astronomical units; body radii are exaggerated
// separately (getVisualRadius) so everything stays visible.
export const AU_TO_SCENE_UNITS = 3.2;

// The Sun's rendered radius; it sits at the scene origin.
export const SUN_RADIUS = 0.72;

/** J2000 equatorial AU (x → equinox, z → celestial north) to scene units, with scene +Y up. */
export function toScenePoint(positionAu: Vec3): ScenePoint {
  return [
    positionAu.x * AU_TO_SCENE_UNITS,
    positionAu.z * AU_TO_SCENE_UNITS,
    -positionAu.y * AU_TO_SCENE_UNITS,
  ];
}

/** Rendered radius in scene units: log-scaled from the real radius, enlarged when highlighted. */
export function getVisualRadius(radiusKm: number, highlighted = false, isCometBody = false) {
  const base = isCometBody
    ? Math.max(0.28, Math.log10(Math.max(radiusKm, 1)) * 0.12)
    : Math.max(0.16, Math.log10(Math.max(radiusKm, 0.01)) * 0.09 - 0.12);
  return highlighted ? Math.max(base * 1.75, isCometBody ? 0.42 : 0.32) : base;
}
