import { useEffect, useRef, useState } from "react";
import { isSmallBody, BODY_BY_ID, type BodyId } from "../domain/solarSystem";
import { getBackendBodyTrajectory } from "./ephemerisApi";
import { HttpError, NetworkError, RequestTimeoutError } from "./http";
import type { SmallBodyTrajectory } from "./smallBodyTrajectory";

function waitForRetry(ms: number, signal: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    if (signal.aborted) { reject(signal.reason); return; }
    const abort = () => { clearTimeout(timer); reject(signal.reason); };
    const timer = setTimeout(() => { signal.removeEventListener("abort", abort); resolve(); }, ms);
    signal.addEventListener("abort", abort, { once: true });
  });
}

export async function loadTrajectoryWithRetry(body: BodyId, signal: AbortSignal) {
  for (let attempt = 0; ; attempt++) {
    try {
      const trajectory = await getBackendBodyTrajectory(body, signal);
      if (trajectory.length < 2) throw new Error("Missing trajectory");
      return trajectory;
    } catch (error) {
      if (signal.aborted) throw signal.reason;
      const transient = error instanceof NetworkError || error instanceof RequestTimeoutError
        || error instanceof HttpError && (error.status === 429 || error.status >= 500);
      const retryAfter = error instanceof HttpError ? error.retryAfterMs ?? 0 : 0;
      if (!transient || attempt >= 2 || retryAfter > 120_000) throw error;
      await waitForRetry(Math.max(500 * 2 ** attempt, retryAfter), signal);
    }
  }
}

export function trajectoryCoverageNotice(visibleBodies: BodyId[], trajectories: Partial<Record<BodyId, SmallBodyTrajectory>>, time: Date) {
  const approximate = visibleBodies.filter(body => {
    const points = trajectories[body];
    return points?.length && (time < points[0].time || time > points[points.length - 1].time);
  });
  return approximate.length ? `${approximate.map(body => BODY_BY_ID[body].name).join(", ")}: repeated-orbit approximation outside the JPL snapshot range.` : null;
}

export default function useSmallBodyTrajectories(visibleBodies: BodyId[]) {
  const cache = useRef<Partial<Record<BodyId, SmallBodyTrajectory>>>({});
  const loading = useRef(new Map<BodyId, AbortController>());
  const [trajectories, setTrajectories] = useState<Partial<Record<BodyId, SmallBodyTrajectory>>>({});
  const [errors, setErrors] = useState<Partial<Record<BodyId, string>>>({});
  const wantedKey = visibleBodies.filter(isSmallBody).sort().join(",");
  useEffect(() => {
    const wanted = new Set(wantedKey ? wantedKey.split(",") as BodyId[] : []);
    for (const [body, controller] of loading.current) {
      if (!wanted.has(body)) { controller.abort(); loading.current.delete(body); }
    }
    for (const body of wanted) {
      if (cache.current[body] || loading.current.has(body)) continue;
      const controller = new AbortController();
      loading.current.set(body, controller);
      void loadTrajectoryWithRetry(body, controller.signal).then(trajectory => {
        if (controller.signal.aborted) return;
        cache.current = { ...cache.current, [body]: trajectory };
        setTrajectories(cache.current);
        setErrors(previous => ({ ...previous, [body]: undefined }));
      }).catch(() => {
        if (!controller.signal.aborted) setErrors(previous => ({
          ...previous, [body]: `${BODY_BY_ID[body].name}'s path could not load. Toggle it off and on to retry.`,
        }));
      }).finally(() => {
        if (loading.current.get(body) === controller) loading.current.delete(body);
      });
    }
  }, [wantedKey]);
  useEffect(() => {
    const requests = loading.current;
    return () => { requests.forEach(controller => controller.abort()); requests.clear(); };
  }, []);
  return { trajectories, error: visibleBodies.map(body => errors[body]).find(Boolean) ?? null };
}
