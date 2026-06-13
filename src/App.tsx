import { useEffect, useMemo, useRef, useState } from "react";
import ControlPanel from "./components/ControlPanel";
import SolarSystemScene from "./components/SolarSystemScene";
import { BODIES, type BodyId, type ClosestApproachResult } from "./domain/solarSystem";
import { findClosestApproach, getBodyPositions } from "./lib/ephemeris";

const INITIAL_DATE = new Date();
const DEFAULT_VISIBLE_BODIES = BODIES.map((body) => body.id);

export default function App() {
  const [currentTime, setCurrentTime] = useState(INITIAL_DATE);
  const [isPlaying, setIsPlaying] = useState(true);
  const [speedDaysPerSecond, setSpeedDaysPerSecond] = useState(10);
  const [visibleBodies, setVisibleBodies] = useState<BodyId[]>(DEFAULT_VISIBLE_BODIES);
  const [eventResult, setEventResult] = useState<ClosestApproachResult | null>(null);
  const [isSearching, setIsSearching] = useState(false);
  const lastFrameMs = useRef<number | null>(null);

  useEffect(() => {
    let animationFrameId = 0;

    const tick = (frameMs: number) => {
      if (isPlaying) {
        const lastMs = lastFrameMs.current ?? frameMs;
        const elapsedSeconds = (frameMs - lastMs) / 1000;
        const elapsedDays = elapsedSeconds * speedDaysPerSecond;

        setCurrentTime((time) => addDays(time, elapsedDays));
      }

      lastFrameMs.current = frameMs;
      animationFrameId = window.requestAnimationFrame(tick);
    };

    animationFrameId = window.requestAnimationFrame(tick);

    return () => window.cancelAnimationFrame(animationFrameId);
  }, [isPlaying, speedDaysPerSecond]);

  const positions = useMemo(
    () => getBodyPositions(visibleBodies, currentTime),
    [currentTime, visibleBodies],
  );

  const handleToggleBody = (body: BodyId) => {
    setVisibleBodies((bodies) =>
      bodies.includes(body)
        ? bodies.filter((visibleBody) => visibleBody !== body)
        : [...bodies, body],
    );
  };

  const handleSearchClosestApproach = () => {
    setIsSearching(true);

    window.setTimeout(() => {
      const start = currentTime;
      const end = addDays(currentTime, 365 * 3);
      const result = findClosestApproach("earth", "mars", start, end);

      setCurrentTime(result.time);
      setEventResult(result);
      setIsPlaying(false);
      setIsSearching(false);
    }, 0);
  };

  return (
    <main className="app-shell">
      <section className="hero-panel" aria-label="Solar system simulation">
        <SolarSystemScene currentTime={currentTime} positions={positions} />
      </section>

      <ControlPanel
        currentTime={currentTime}
        eventResult={eventResult}
        isPlaying={isPlaying}
        isSearching={isSearching}
        speedDaysPerSecond={speedDaysPerSecond}
        visibleBodies={visibleBodies}
        onDateChange={setCurrentTime}
        onPlayToggle={() => setIsPlaying((playing) => !playing)}
        onSearchClosestApproach={handleSearchClosestApproach}
        onSpeedChange={setSpeedDaysPerSecond}
        onToggleBody={handleToggleBody}
      />
    </main>
  );
}

function addDays(time: Date, days: number) {
  return new Date(time.getTime() + days * 24 * 60 * 60 * 1000);
}
