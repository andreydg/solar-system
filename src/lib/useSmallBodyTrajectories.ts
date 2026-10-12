import { useEffect, useRef, useState } from "react";
import { isSmallBody, type BodyId } from "../domain/solarSystem";
import { getBackendBodyTrajectory } from "./ephemerisApi";
import type { SmallBodyTrajectory } from "./smallBodyTrajectory";

export default function useSmallBodyTrajectories(visibleBodies: BodyId[]) {
  const cache = useRef<Partial<Record<BodyId, SmallBodyTrajectory>>>({});
  const [trajectories, setTrajectories] = useState<Partial<Record<BodyId, SmallBodyTrajectory>>>({});
  const [errors, setErrors] = useState<Partial<Record<BodyId, string>>>({});
  // No prewarming, no polling: requested bodies only, each success retained independently.
  useEffect(() => {
    const controller = new AbortController();
    for (const body of visibleBodies.filter(isSmallBody)) {
      if (cache.current[body]) continue;
      void getBackendBodyTrajectory(body, controller.signal).then(trajectory => {
        if (controller.signal.aborted) return;
        if (trajectory.length < 2) throw new Error("Missing trajectory");
        cache.current = { ...cache.current, [body]: trajectory };
        setTrajectories(cache.current);
        setErrors(previous => ({ ...previous, [body]: undefined }));
      }).catch(() => {
        if (!controller.signal.aborted) setErrors(previous => ({ ...previous, [body]: "Some small-body paths could not load. Toggle the body off and on to retry." }));
      });
    }
    return () => controller.abort();
  }, [visibleBodies]);
  return { trajectories, error: visibleBodies.map(body => errors[body]).find(Boolean) ?? null };
}
