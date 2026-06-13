import { useEffect, useMemo, useRef, useState } from "react";
import ControlPanel from "./components/ControlPanel";
import SolarSystemScene from "./components/SolarSystemScene";
import { BODIES, type BodyId, type ClosestApproachResult } from "./domain/solarSystem";
import {
  EVENT_TYPE_BY_ID,
  getEventTargetOptions,
  isValidEventPair,
  locksEarthAsBodyA,
  type CatalogEventType,
} from "./domain/eventTypes";
import { findClosestApproach, getBodyPositions } from "./lib/ephemeris";
import { findNextEvent, SEARCH_HORIZON_YEARS, type CatalogEventResult } from "./lib/eventCatalogApi";

const INITIAL_DATE = new Date();
const DEFAULT_VISIBLE_BODIES = BODIES.map((body) => body.id);

export default function App() {
  const [currentTime, setCurrentTime] = useState(INITIAL_DATE);
  const [isPlaying, setIsPlaying] = useState(true);
  const [speedDaysPerSecond, setSpeedDaysPerSecond] = useState(10);
  const [visibleBodies, setVisibleBodies] = useState<BodyId[]>(DEFAULT_VISIBLE_BODIES);
  const [eventType, setEventType] = useState<CatalogEventType>("closestApproach");
  const [eventBodyA, setEventBodyA] = useState<BodyId>("earth");
  const [eventBodyB, setEventBodyB] = useState<BodyId>("mars");
  const [eventResult, setEventResult] = useState<
    (ClosestApproachResult & { angleDeg: null; magnitude: null; type: "closestApproach" }) | CatalogEventResult | null
  >(null);
  const [isSearching, setIsSearching] = useState(false);
  const [eventStatus, setEventStatus] = useState<string | null>(null);
  const lastFrameMs = useRef<number | null>(null);

  const eventPairIsValid = useMemo(
    () => isValidEventPair(eventType, eventBodyA, eventBodyB),
    [eventBodyA, eventBodyB, eventType],
  );

  const highlightedBodies = useMemo<BodyId[]>(() => {
    if (!eventResult) {
      return [];
    }

    return [eventResult.bodyA, eventResult.bodyB];
  }, [eventResult]);

  const ensureBodiesVisible = (bodyA: BodyId, bodyB: BodyId) => {
    setVisibleBodies((bodies) => {
      const next = new Set(bodies);
      next.add(bodyA);
      next.add(bodyB);
      return BODIES.map((body) => body.id).filter((body) => next.has(body));
    });
  };

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

  const normalizeEventBodies = (type: CatalogEventType, bodyA: BodyId, bodyB: BodyId) => {
    const definition = EVENT_TYPE_BY_ID[type];

    if (definition.pairing === "any") {
      return { bodyA, bodyB };
    }

    const preferredTarget = bodyA === "earth" ? bodyB : bodyB === "earth" ? bodyA : bodyB;
    const allowedTargets = getEventTargetOptions(type);
    const nextBodyB = allowedTargets.includes(preferredTarget)
      ? preferredTarget
      : (allowedTargets[0] ?? "mars");

    return { bodyA: "earth" as const, bodyB: nextBodyB };
  };

  const handleEventTypeChange = (type: CatalogEventType) => {
    setEventType(type);
    setEventResult(null);
    setEventStatus(null);

    const normalized = normalizeEventBodies(type, eventBodyA, eventBodyB);
    setEventBodyA(normalized.bodyA);
    setEventBodyB(normalized.bodyB);
  };

  const handleEventBodyAChange = (body: BodyId) => {
    setEventBodyA(body);
    setEventResult(null);
    setEventStatus(null);

    const normalized = normalizeEventBodies(eventType, body, eventBodyB);
    setEventBodyA(normalized.bodyA);
    setEventBodyB(normalized.bodyB);
  };

  const handleEventBodyBChange = (body: BodyId) => {
    setEventBodyB(body);
    setEventResult(null);
    setEventStatus(null);

    const normalized = normalizeEventBodies(eventType, eventBodyA, body);
    setEventBodyA(normalized.bodyA);
    setEventBodyB(normalized.bodyB);
  };

  const handleSearchEvent = async (after?: Date) => {
    if (!eventPairIsValid) {
      setEventStatus("Choose a valid body pair for this event type.");
      return;
    }

    const searchAfter = after ?? currentTime;
    setIsSearching(true);
    setEventStatus("Searching for next event...");

    try {
      const result = await findNextEvent(eventType, eventBodyA, eventBodyB, searchAfter);

      if (!result) {
        setEventStatus(`No matching events found in the next ${SEARCH_HORIZON_YEARS} years.`);
        return;
      }

      setCurrentTime(result.time);
      ensureBodiesVisible(result.bodyA, result.bodyB);
      setEventResult(result);
      setEventStatus(
        result.validationStatus === "validated"
          ? "Loaded validated event."
          : "Loaded provisional event.",
      );
    } catch (error) {
      if (eventType !== "closestApproach") {
        setEventStatus(
          error instanceof Error ? error.message : "Event search failed.",
        );
        return;
      }

      const end = addYears(searchAfter, 5);
      const result = findClosestApproach(eventBodyA, eventBodyB, searchAfter, end);

      setCurrentTime(result.time);
      ensureBodiesVisible(result.bodyA, result.bodyB);
      setEventResult({
        ...result,
        angleDeg: null,
        magnitude: null,
        type: "closestApproach",
      });
      setEventStatus(
        `Backend unavailable; used browser calculation. ${
          error instanceof Error ? error.message : "Unknown backend error"
        }`,
      );
    } finally {
      setIsSearching(false);
      setIsPlaying(false);
    }
  };

  const handleNextEvent = () => {
    const searchAfter = eventResult ? addDays(eventResult.time, 1) : currentTime;
    void handleSearchEvent(searchAfter);
  };

  return (
    <main className="app-shell">
      <section className="hero-panel" aria-label="Solar system simulation">
        <SolarSystemScene
          currentTime={currentTime}
          highlightedBodies={highlightedBodies}
          positions={positions}
        />
      </section>

      <ControlPanel
        currentTime={currentTime}
        eventPairIsValid={eventPairIsValid}
        eventResult={eventResult}
        eventStatus={eventStatus}
        eventBodyA={eventBodyA}
        eventBodyB={eventBodyB}
        eventType={eventType}
        isPlaying={isPlaying}
        isSearching={isSearching}
        locksEarthAsBodyA={locksEarthAsBodyA(eventType)}
        speedDaysPerSecond={speedDaysPerSecond}
        visibleBodies={visibleBodies}
        onDateChange={setCurrentTime}
        onEventBodyAChange={handleEventBodyAChange}
        onEventBodyBChange={handleEventBodyBChange}
        onEventTypeChange={handleEventTypeChange}
        onPlayToggle={() => setIsPlaying((playing) => !playing)}
        onNextEvent={handleNextEvent}
        onSpeedChange={setSpeedDaysPerSecond}
        onToggleBody={handleToggleBody}
      />
    </main>
  );
}

function addDays(time: Date, days: number) {
  return new Date(time.getTime() + days * 24 * 60 * 60 * 1000);
}

function addYears(time: Date, years: number) {
  const next = new Date(time);
  next.setUTCFullYear(next.getUTCFullYear() + years);
  return next;
}
