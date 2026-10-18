import { useFrame } from "@react-three/fiber";
import { useLayoutEffect, useRef, type RefObject } from "react";
import { isSmallBody, type BodyPosition, type BodyId } from "../domain/solarSystem";
import { getBodyPositions } from "../lib/ephemeris";
import { interpolateTrajectory, type SmallBodyTrajectory } from "../lib/smallBodyTrajectory";

/** Scene-owned mutable positions, updated before bodies, controls, camera and labels. */
export default function SceneMotion({ timeRef, positions, trajectories }: {
  timeRef: RefObject<Date>;
  positions: BodyPosition[];
  trajectories: Partial<Record<BodyId, SmallBodyTrajectory>>;
}) {
  const livePositions = useRef(positions);
  const lastTime = useRef<number | null>(null);
  useLayoutEffect(() => { livePositions.current = positions; lastTime.current = null; }, [positions, trajectories]);
  useFrame(() => {
    const time = timeRef.current.getTime();
    if (lastTime.current === time) return;
    lastTime.current = time;
    for (const position of livePositions.current) {
      const points = trajectories[position.body];
      const next = isSmallBody(position.body)
        ? points && interpolateTrajectory(points, timeRef.current, position.body)
        : getBodyPositions([position.body], timeRef.current)[0]?.positionAu;
      // Scene-owned clones, never App state; Three consumes them imperatively each frame.
      // eslint-disable-next-line react-hooks/immutability
      if (next) position.positionAu = next;
    }
  }, -2);
  return null;
}
