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
import { getVisualRadius, toScenePoint } from "../lib/sceneSpace";

const FLIGHT_SECONDS = 1.4;
const OVERVIEW_MIN_DISTANCE = 4;
// How close you can zoom to a focused body, in multiples of its rendered radius.
const MIN_ZOOM_RADII = 1.3;

// What the camera should do next, decided from props and carried out on the next frame.
type CameraRequest =
  | { kind: "view"; view: CameraView }
  | { kind: "overview" }
  | { kind: "follow"; body: BodyId; radius: number };

type Flight = {
  from: CameraView;
  elapsed: number;
  // Re-evaluated every frame so a flight lands on a body that keeps moving; null if it vanished.
  goal: () => CameraView | null;
  onArrive: () => void;
};

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
  const following = useRef<{ body: BodyId; lastPoint: THREE.Vector3 } | null>(null);
  const latestPositions = useRef(positions);

  useEffect(() => {
    latestPositions.current = positions;
  }, [positions]);

  useEffect(() => {
    // A newly loaded event takes over the camera; App clears any focus in the same update.
    if (highlightedBodies.length === 2 && framedEvent.current !== highlightedBodies) {
      const first = positions.find((position) => position.body === highlightedBodies[0]);
      const second = positions.find((position) => position.body === highlightedBodies[1]);
      if (first && second) {
        framedEvent.current = highlightedBodies;
        shownFocus.current = focusedBody;
        request.current = {
          kind: "view",
          view: pairView(toScenePoint(first.positionAu), toScenePoint(second.positionAu)),
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
      radius: getVisualRadius(
        BODY_BY_ID[focusedBody].radiusKm,
        highlightedBodies.includes(focusedBody),
        isComet(focusedBody),
      ),
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
      following.current = null;
      const from = { position: camera.position.clone(), target: controls.target.clone() };
      if (next.kind === "follow") {
        const start = scenePointOf(next.body);
        if (start) {
          const closeUp = closeUpView(start.toArray(), camera.position, next.radius);
          const offset = closeUp.position.sub(closeUp.target);
          controls.minDistance = next.radius * MIN_ZOOM_RADII;
          flight.current = {
            from,
            elapsed: 0,
            goal: () => {
              const point = scenePointOf(next.body);
              return point ? { position: point.clone().add(offset), target: point } : null;
            },
            onArrive: () => {
              following.current = { body: next.body, lastPoint: scenePointOf(next.body) ?? start };
            },
          };
        }
      } else {
        const view = next.kind === "view" ? next.view : overviewView();
        flight.current = {
          from,
          elapsed: 0,
          goal: () => view,
          onArrive: () => {
            controls.minDistance = OVERVIEW_MIN_DISTANCE;
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
      const view = !goal ? null : progress >= 1 ? goal : interpolateView(current.from, goal, easeInOutCubic(progress));
      if (view) {
        controls.target.copy(view.target);
        camera.position.copy(view.position);
        camera.lookAt(controls.target);
      }
      if (!view || progress >= 1) {
        flight.current = null;
        controls.enabled = true;
        if (view) {
          current.onArrive();
        }
        controls.update();
      }
      return;
    }

    // Keep a followed body centred: shift the camera and target by however far it moved, so the
    // user's own orbit and zoom around it are preserved.
    const followed = following.current;
    if (followed) {
      const point = scenePointOf(followed.body);
      if (!point) {
        following.current = null;
        return;
      }
      const moved = point.clone().sub(followed.lastPoint);
      camera.position.add(moved);
      controls.target.add(moved);
      followed.lastPoint.copy(point);
    }
  });

  return (
    <OrbitControls
      makeDefault
      enableDamping
      dampingFactor={0.08}
      maxDistance={220}
      minDistance={OVERVIEW_MIN_DISTANCE}
    />
  );
}
