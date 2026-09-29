import * as THREE from "three";

export type ScenePoint = [number, number, number];

export type CameraView = {
  position: THREE.Vector3;
  target: THREE.Vector3;
};

/** The camera's vertical field of view (degrees) and width / height aspect ratio. */
export type Lens = {
  fov: number;
  aspect: number;
};

const UP = new THREE.Vector3(0, 1, 0);
// Event pairs are seen from above and in front of their midpoint.
const PAIR_VIEW_DIRECTION = new THREE.Vector3(0, 0.75, 0.65).normalize();
// Close pairs still get some of their surroundings.
const MIN_PAIR_DISTANCE = 14;
// Keep the bodies within this fraction of the half-width and half-height, clear of the edges.
const FRAME_FILL = 0.8;

/**
 * Frames two bodies (drawn up to `radius` in size) from above and in front of their midpoint,
 * backing off until both fit the lens's frustum. On a narrow canvas the horizontal field of view
 * is the tight one, so it is fitted too rather than assumed.
 */
export function pairView(pointA: ScenePoint, pointB: ScenePoint, radius: number, lens: Lens): CameraView {
  const a = new THREE.Vector3(...pointA);
  const target = a.clone().add(new THREE.Vector3(...pointB)).multiplyScalar(0.5);
  const half = a.sub(target); // midpoint → pointA; pointB mirrors it

  const forward = PAIR_VIEW_DIRECTION.clone().negate();
  const right = new THREE.Vector3().crossVectors(forward, UP).normalize();
  const up = new THREE.Vector3().crossVectors(right, forward);
  const tanVertical = Math.tan(THREE.MathUtils.degToRad(lens.fov / 2)) * FRAME_FILL;
  const tanHorizontal = tanVertical * lens.aspect;

  // A body at `target ± half` sits `D ± half·forward` in front of a camera `D` back from the
  // target, so the nearer one sets the distance needed to fit it sideways and vertically.
  const sideways = Math.abs(half.dot(right)) + radius;
  const vertical = Math.abs(half.dot(up)) + radius;
  const distance = Math.max(
    MIN_PAIR_DISTANCE,
    Math.abs(half.dot(forward)) + Math.max(sideways / tanHorizontal, vertical / tanVertical),
  );

  return { position: target.clone().addScaledVector(PAIR_VIEW_DIRECTION, distance), target };
}
