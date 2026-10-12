import { useEffect } from "react";
import { CATALOG_MIN_TIME, CATALOG_MAX_TIME } from "./eventCatalogApi";

export function advanceClock(time: Date, elapsedMs: number, daysPerSecond: number) {
  const next = time.getTime() + Math.min(250, Math.max(0, elapsedMs)) * daysPerSecond * 86_400;
  return new Date(Math.min(CATALOG_MAX_TIME.getTime(), Math.max(CATALOG_MIN_TIME.getTime(), next)));
}

/** At most 10 React updates/sec; no loop while paused or hidden, no catch-up jump. */
export function useSimulationClock(playing: boolean, speed: number, update: (fn: (time: Date) => Date) => void) {
  useEffect(() => {
    let frame = 0;
    let last: number | null = null;
    const tick = (now: number) => {
      if (document.hidden) { last = null; return; }
      if (last === null) last = now;
      if (now - last >= 100) { const elapsed = now - last; update(time => advanceClock(time, elapsed, speed)); last = now; }
      frame = requestAnimationFrame(tick);
    };
    const restart = () => {
      cancelAnimationFrame(frame);
      last = null;
      if (playing && !document.hidden) frame = requestAnimationFrame(tick);
    };
    restart();
    document.addEventListener("visibilitychange", restart);
    return () => { cancelAnimationFrame(frame); document.removeEventListener("visibilitychange", restart); };
  }, [playing, speed, update]);
}
