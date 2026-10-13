import { useEffect, useRef } from "react";
import { CATALOG_MIN_TIME, CATALOG_MAX_TIME } from "./eventCatalogApi";

export function advanceClock(time: Date, elapsedMs: number, daysPerSecond: number) {
  const next = time.getTime() + Math.min(250, Math.max(0, elapsedMs)) * daysPerSecond * 86_400;
  return new Date(Math.min(CATALOG_MAX_TIME.getTime(), Math.max(CATALOG_MIN_TIME.getTime(), next)));
}

/** Follow display refresh while visible; cap stalls and never catch up hidden time. */
export function useSimulationClock(playing: boolean, speed: number, update: (fn: (time: Date) => Date) => void) {
  const speedRef = useRef(speed);
  useEffect(() => { speedRef.current = speed; }, [speed]);
  useEffect(() => {
    let frame = 0;
    let last: number | null = null;
    const tick = (now: number) => {
      if (document.hidden) { last = null; return; }
      if (last !== null && now > last) {
        const elapsed = now - last;
        update(time => advanceClock(time, elapsed, speedRef.current));
      }
      last = now;
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
  }, [playing, update]);
}
