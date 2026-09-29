import * as THREE from "three";
import type { ScenePoint } from "./sceneSpace";

export type CameraView = {
  position: THREE.Vector3;
  target: THREE.Vector3;
};

/** The camera's vertical field of view (degrees) and width / height aspect ratio. */
export type Lens = {
  fov: number;
  aspect: number;
};

export const OVERVIEW_CAMERA_POSITION: ScenePoint = [0, 38, 42];

// A close-up sits this many rendered radii from the body's centre.
export const CLOSE_UP_RADII = 5;

const UP = new THREE.Vector3(0, 1, 0);
// Event pairs are seen from above and in front of their midpoint.
const PAIR_VIEW_DIRECTION = new THREE.Vector3(0, 0.75, 0.65).normalize();
// Close pairs still get some of their surroundings.
const MIN_PAIR_DISTANCE = 14;
// Keep the bodies within this fraction of the half-width and half-height, clear of the edges.
const FRAME_FILL = 0.8;

export function overviewView(): CameraView {
  return { position: new THREE.Vector3(...OVERVIEW_CAMERA_POSITION), target: new THREE.Vector3(0, 0, 0) };
}

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

/**
 * Close-up of a body: a lit three-quarter view (the Sun sits at the scene origin) from the side
 * of the body the camera is already on, so the flight there doesn't swing around it.
 */
export function closeUpView(bodyPoint: ScenePoint, cameraPosition: THREE.Vector3, radius: number): CameraView {
  const target = new THREE.Vector3(...bodyPoint);
  const toSun = target.clone().negate().normalize();
  const toCamera = cameraPosition.clone().sub(target).normalize();
  const side = new THREE.Vector3().crossVectors(toSun, UP).normalize();
  if (side.dot(toCamera) < 0) {
    side.negate();
  }
  const direction = side.addScaledVector(toSun, 0.55).addScaledVector(UP, 0.25).normalize();
  return { position: target.clone().addScaledVector(direction, radius * CLOSE_UP_RADII), target };
}

/**
 * A view part-way (t in [0, 1]) between two others. The target moves in a straight line while the
 * camera swings around it and zooms at a steady rate (distance interpolated in log space), so a
 * long zoom from the overview into a close-up doesn't rush its final stretch.
 */
export function interpolateView(from: CameraView, to: CameraView, t: number): CameraView {
  const target = from.target.clone().lerp(to.target, t);
  const fromOffset = from.position.clone().sub(from.target);
  const toOffset = to.position.clone().sub(to.target);
  const fromDistance = Math.max(fromOffset.length(), 1e-6);
  const toDistance = Math.max(toOffset.length(), 1e-6);
  const swing = new THREE.Quaternion().setFromUnitVectors(fromOffset.normalize(), toOffset.normalize());
  const direction = fromOffset.applyQuaternion(new THREE.Quaternion().slerp(swing, t));
  const distance = Math.exp(THREE.MathUtils.lerp(Math.log(fromDistance), Math.log(toDistance), t));
  return { position: target.clone().addScaledVector(direction, distance), target };
}

export function easeInOutCubic(t: number) {
  return t < 0.5 ? 4 * t * t * t : 1 - (-2 * t + 2) ** 3 / 2;
}
