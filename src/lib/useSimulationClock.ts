import { useCallback, useEffect, useRef, useState } from "react";
import { CATALOG_MIN_TIME, CATALOG_MAX_TIME } from "./eventCatalogApi";

export function advanceClock(time: Date, elapsedMs: number, daysPerSecond: number) {
  const next = time.getTime() + Math.min(250, Math.max(0, elapsedMs)) * daysPerSecond * 86_400;
  return new Date(Math.min(CATALOG_MAX_TIME.getTime(), Math.max(CATALOG_MIN_TIME.getTime(), next)));
}

/** Frame-rate scene clock; React controls receive at most ten playback updates/second. */
export function useSimulationClock(initial: Date, playing: boolean, speed: number) {
  const [currentTime, publish] = useState(initial);
  const timeRef = useRef(initial);
  const running = useRef(playing);
  const speedRef = useRef(speed);
  const setCurrentTime = useCallback((time: Date) => {
    timeRef.current = time;
    publish(time);
  }, []);
  // Stop synchronously: already queued callbacks cannot advance a paused/shared view.
  const pauseClock = useCallback(() => {
    running.current = false;
    publish(timeRef.current);
    return timeRef.current;
  }, []);
  useEffect(() => { speedRef.current = speed; }, [speed]);
  useEffect(() => {
    running.current = playing;
    let frame = 0;
    let last: number | null = null;
    let publishedAt = 0;
    const tick = (now: number) => {
      if (!running.current || document.hidden) { last = null; return; }
      if (last !== null && now > last) {
        timeRef.current = advanceClock(timeRef.current, now - last, speedRef.current);
        if (now - publishedAt >= 100) {
          publish(timeRef.current);
          publishedAt = now;
        }
      } else publishedAt = now;
      last = now;
      frame = requestAnimationFrame(tick);
    };
    const restart = () => {
      cancelAnimationFrame(frame);
      last = null;
      if (running.current && !document.hidden) frame = requestAnimationFrame(tick);
    };
    restart();
    document.addEventListener("visibilitychange", restart);
    return () => {
      running.current = false;
      cancelAnimationFrame(frame);
      document.removeEventListener("visibilitychange", restart);
    };
  }, [playing]);
  return { currentTime, setCurrentTime, timeRef, pauseClock };
}
