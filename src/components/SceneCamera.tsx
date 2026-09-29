import { OrbitControls } from "@react-three/drei";
import { useFrame } from "@react-three/fiber";
import { useEffect, useRef } from "react";
import * as THREE from "three";
import type { OrbitControls as OrbitControlsImpl } from "three-stdlib";
import { BODY_BY_ID, isComet, type BodyId, type BodyPosition } from "../domain/solarSystem";
import {
  closeUpView,
  easeInOutCubic,
  interpolateView,
  overviewView,
  pairView,
  type CameraView,
} from "../lib/cameraViews";
import { getVisualRadius, SUN_RADIUS, toScenePoint, type ScenePoint } from "../lib/sceneSpace";

const FLIGHT_SECONDS = 1.4;
const OVERVIEW_MIN_DISTANCE = 4;
// How far out the user can zoom. Framing a wide event pair on a narrow canvas can need more, so
// that framing raises it; flying anywhere else restores it.
const DEFAULT_MAX_DISTANCE = 220;
// How close you can zoom to a focused body, in multiples of its rendered radius.
const MIN_ZOOM_RADII = 1.3;
// The camera's usual near plane. Close to a body it is pulled in to half the gap to the nearest
// surface, which at full zoom is well inside this, so the surface isn't clipped away.
const DEFAULT_NEAR = 0.1;
const MIN_NEAR = 0.001;
// Frame-loop slot for moving the camera: after drei's OrbitControls applies user input (-1) and
// before everything that reads the camera at the default 0 (sky, labels, hit areas, drei Html),
// so none of those trail a frame behind during flights and follows.
const CAMERA_FRAME_PRIORITY = -0.5;

// What the camera should do next, decided from props and carried out on the next frame.
type CameraRequest =
  | { kind: "pair"; points: [ScenePoint, ScenePoint]; radius: number }
  | { kind: "overview" }
  | { kind: "follow"; body: BodyId; radius: number };

type Flight = {
  from: CameraView;
  elapsed: number;
  // Re-evaluated every frame so a flight lands on a body that keeps moving.
  goal: () => CameraView;
  onArrive: () => void;
};

// The body the camera is flying to or following. `lastPoint` is where it was last seen, so a
// position that is briefly missing (e.g. a failed live fetch) pauses the follow instead of ending it.
type Focus = { body: BodyId; lastPoint: THREE.Vector3 };

type SceneCameraProps = {
  focusedBody: BodyId | null;
  highlightedBodies: BodyId[];
  positions: BodyPosition[];
};

/**
 * Orbit controls plus the app-driven camera moves: framing each newly loaded event, flying to
 * a focused body and following it as time runs, and flying back to the overview.
 */
export default function SceneCamera({ focusedBody, highlightedBodies, positions }: SceneCameraProps) {
  // App recreates highlightedBodies for every loaded event, so the array doubles as the event's
  // identity: each event is framed once, and playback afterwards leaves the camera to the user.
  const framedEvent = useRef<BodyId[] | null>(null);
  const shownFocus = useRef<BodyId | null>(null);
  const request = useRef<CameraRequest | null>(null);
  const flight = useRef<Flight | null>(null);
  const focus = useRef<Focus | null>(null);
  const latestPositions = useRef(positions);
  const latestHighlighted = useRef(highlightedBodies);

  useEffect(() => {
    latestPositions.current = positions;
    latestHighlighted.current = highlightedBodies;
  }, [positions, highlightedBodies]);

  useEffect(() => {
    // A newly loaded event takes over the camera; App clears any focus in the same update.
    if (highlightedBodies.length === 2 && framedEvent.current !== highlightedBodies) {
      const first = positions.find((position) => position.body === highlightedBodies[0]);
      const second = positions.find((position) => position.body === highlightedBodies[1]);
      if (first && second) {
        framedEvent.current = highlightedBodies;
        shownFocus.current = focusedBody;
        request.current = {
          kind: "pair",
          points: [toScenePoint(first.positionAu), toScenePoint(second.positionAu)],
          // Event bodies are drawn highlighted (enlarged); leave room for the bigger of the two.
          radius: Math.max(...highlightedBodies.map((body) => renderedRadius(body, true))),
        };
        return;
      }
    }

    if (focusedBody === shownFocus.current) {
      return;
    }
    if (focusedBody === null) {
      shownFocus.current = null;
      request.current = { kind: "overview" };
      return;
    }
    if (!positions.some((position) => position.body === focusedBody)) {
      return; // not loaded yet: fly there once its position arrives
    }
    shownFocus.current = focusedBody;
    request.current = {
      kind: "follow",
      body: focusedBody,
      radius: renderedRadius(focusedBody, highlightedBodies.includes(focusedBody)),
    };
  }, [focusedBody, highlightedBodies, positions]);

  useFrame((state, delta) => {
    const controls = state.controls as OrbitControlsImpl | null;
    if (!controls) {
      return;
    }
    const camera = state.camera;
    const scenePointOf = (body: BodyId) => {
      const position = latestPositions.current.find((entry) => entry.body === body);
      return position ? new THREE.Vector3(...toScenePoint(position.positionAu)) : null;
    };

    const next = request.current;
    if (next) {
      request.current = null;
      focus.current = null;
      flight.current = null;
      const from = { position: camera.position.clone(), target: controls.target.clone() };
      if (next.kind === "follow") {
        const start = scenePointOf(next.body);
        if (start) {
          const closeUp = closeUpView(start.toArray(), camera.position, next.radius);
          const offset = closeUp.position.sub(closeUp.target);
          const target: Focus = { body: next.body, lastPoint: start };
          focus.current = target;
          controls.minDistance = next.radius * MIN_ZOOM_RADII;
          flight.current = {
            from,
            elapsed: 0,
            goal: () => {
              const point = scenePointOf(target.body);
              if (point) {
                target.lastPoint.copy(point);
              }
              return { position: target.lastPoint.clone().add(offset), target: target.lastPoint.clone() };
            },
            onArrive: () => {
              controls.maxDistance = DEFAULT_MAX_DISTANCE;
            },
          };
        }
      } else {
        const perspective = camera as THREE.PerspectiveCamera;
        const lens = perspective.isPerspectiveCamera
          ? { fov: perspective.fov, aspect: perspective.aspect }
          : { fov: 48, aspect: 1 };
        const view = next.kind === "pair" ? pairView(next.points[0], next.points[1], next.radius, lens) : overviewView();
        flight.current = {
          from,
          elapsed: 0,
          goal: () => view,
          onArrive: () => {
            // Set the limits before the landing update(), which would otherwise pull a wide
            // framing back inside the default zoom-out limit and crop a body.
            controls.minDistance = OVERVIEW_MIN_DISTANCE;
            controls.maxDistance = Math.max(DEFAULT_MAX_DISTANCE, view.position.distanceTo(view.target));
          },
        };
      }
      // The flight drives the camera until it lands; user input would only fight it.
      controls.enabled = flight.current === null;
    }

    const current = flight.current;
    if (current) {
      current.elapsed += delta;
      const progress = Math.min(current.elapsed / FLIGHT_SECONDS, 1);
      const goal = current.goal();
      const view = progress >= 1 ? goal : interpolateView(current.from, goal, easeInOutCubic(progress));
      controls.target.copy(view.target);
      camera.position.copy(view.position);
      camera.lookAt(controls.target);
      if (progress >= 1) {
        flight.current = null;
        controls.enabled = true;
        current.onArrive();
        controls.update();
      }
    } else if (focus.current) {
      // Keep a followed body centred: shift the camera and target by however far it moved, so the
      // user's own orbit and zoom around it are preserved. While its position is missing, hold
      // still and catch up with the whole move once it's back.
      const followed = focus.current;
      const point = scenePointOf(followed.body);
      if (point) {
        const moved = point.clone().sub(followed.lastPoint);
        camera.position.add(moved);
        controls.target.add(moved);
        followed.lastPoint.copy(point);
      }
    }

    keepNearPlaneInFrontOfSurfaces(camera, latestPositions.current, latestHighlighted.current);
  }, CAMERA_FRAME_PRIORITY);

  return (
    <OrbitControls
      makeDefault
      enableDamping
      dampingFactor={0.08}
      maxDistance={DEFAULT_MAX_DISTANCE}
      minDistance={OVERVIEW_MIN_DISTANCE}
    />
  );
}

function renderedRadius(body: BodyId, highlighted: boolean) {
  return getVisualRadius(BODY_BY_ID[body].radiusKm, highlighted, isComet(body));
}

const scratchPoint = new THREE.Vector3();

// Pulls the near plane in to half the gap to the nearest body's surface (the Sun included), and
// back out to the default away from them, so close-ups aren't clipped and depth precision is kept.
function keepNearPlaneInFrontOfSurfaces(camera: THREE.Camera, positions: BodyPosition[], highlighted: BodyId[]) {
  const perspective = camera as THREE.PerspectiveCamera;
  if (!perspective.isPerspectiveCamera) {
    return;
  }
  let gap = perspective.position.length() - SUN_RADIUS;
  for (const position of positions) {
    const center = scratchPoint.set(...toScenePoint(position.positionAu));
    gap = Math.min(gap, perspective.position.distanceTo(center) - renderedRadius(position.body, highlighted.includes(position.body)));
  }
  const near = THREE.MathUtils.clamp(gap * 0.5, MIN_NEAR, DEFAULT_NEAR);
  if (Math.abs(perspective.near - near) > 1e-9) {
    perspective.near = near;
    perspective.updateProjectionMatrix();
  }
}
