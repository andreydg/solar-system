import * as THREE from "three";
import type { ScenePoint } from "./sceneSpace";

export type CameraView = {
  position: THREE.Vector3;
  target: THREE.Vector3;
};

export const OVERVIEW_CAMERA_POSITION: ScenePoint = [0, 38, 42];

// A close-up sits this many rendered radii from the body's centre.
export const CLOSE_UP_RADII = 5;

const UP = new THREE.Vector3(0, 1, 0);

export function overviewView(): CameraView {
  return { position: new THREE.Vector3(...OVERVIEW_CAMERA_POSITION), target: new THREE.Vector3(0, 0, 0) };
}

/** Frames two bodies (a loaded event) from above and in front of their midpoint. */
export function pairView(pointA: ScenePoint, pointB: ScenePoint): CameraView {
  const a = new THREE.Vector3(...pointA);
  const b = new THREE.Vector3(...pointB);
  const target = a.clone().add(b).multiplyScalar(0.5);
  const spread = a.distanceTo(b);
  const offset = new THREE.Vector3(0, Math.max(spread * 0.75, 10), Math.max(spread * 0.65, 10));
  return { position: target.clone().add(offset), target };
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
