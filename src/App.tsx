import { useEffect, useMemo, useRef, useState } from "react";
import SceneBoundary from "./components/SceneBoundary";
import { canUseBrowserFallback } from "./lib/http";
import ControlPanel from "./components/ControlPanel";

import { BODIES, BODY_BY_ID, isSmallBody, type BodyId, type BodyPosition, type ClosestApproachResult } from "./domain/solarSystem";
import {
  EVENT_TYPE_BY_ID,
  getEventTargetOptions,
  isValidEventPair,
  locksEarthAsBodyA,
  type CatalogEventType,
} from "./domain/eventTypes";
import { findClosestApproach, getBodyPositions } from "./lib/ephemeris";
import useSmallBodyTrajectories, { trajectoryCoverageNotice } from "./lib/useSmallBodyTrajectories";
import { useSimulationClock } from "./lib/useSimulationClock";
import { interpolateTrajectory } from "./lib/smallBodyTrajectory";
import { findNextEvent, findPreviousEvent, SEARCH_HORIZON_YEARS, type CatalogEventResult } from "./lib/eventCatalogApi";
import { addDays, addYears } from "./lib/timeUtils";
import { createEventNavigation, recordEventNavigation, stepEventNavigation, type EventDirection } from "./lib/eventNavigation";

type DisplayEvent = (ClosestApproachResult & { angleDeg: null; magnitude: null; type: "closestApproach" }) | CatalogEventResult;
type EventSnapshot = { result: DisplayEvent; status: string | null };

const INITIAL_DATE = new Date();
const DEFAULT_VISIBLE_BODIES = BODIES.filter((body) => !isSmallBody(body.id)).map((body) => body.id);

export default function App() {
  const [isPlaying, setIsPlaying] = useState(() => !window.matchMedia("(prefers-reduced-motion: reduce)").matches);
  const [speedDaysPerSecond, setSpeedDaysPerSecond] = useState(10);
  const [visibleBodies, setVisibleBodies] = useState<BodyId[]>(DEFAULT_VISIBLE_BODIES);
  const [eventType, setEventType] = useState<CatalogEventType>("closestApproach");
  const [eventBodyA, setEventBodyA] = useState<BodyId>("earth");
  const [eventBodyB, setEventBodyB] = useState<BodyId>("mars");
  const [eventResult, setEventResult] = useState<DisplayEvent | null>(null);
  const eventHistory = useRef(createEventNavigation<EventSnapshot>());
  const [isSearching, setIsSearching] = useState(false);
  const [eventStatus, setEventStatus] = useState<string | null>(null);
  const { trajectories: smallBodyTrajectories, error: trajectoryError } = useSmallBodyTrajectories(visibleBodies);
  // The body the camera is following, if any; null is the Sun-centred overview.
  const [focusedBody, setFocusedBody] = useState<BodyId | null>(null);
  const searchController = useRef<AbortController | null>(null);
  useEffect(() => () => searchController.current?.abort(), []);
  const cancelSearch = () => {
    searchController.current?.abort();
    setIsSearching(false);
  };
  const { currentTime, setCurrentTime, timeRef, pauseClock } = useSimulationClock(INITIAL_DATE, isPlaying, speedDaysPerSecond);
  const pausePlayback = () => { pauseClock(); setIsPlaying(false); };

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

  const visibleSmallBodies = useMemo(() => visibleBodies.filter(isSmallBody), [visibleBodies]);

  const interpolatedSmallBodyPositions = useMemo(
    () =>
      visibleSmallBodies
        .map((body) => {
          const trajectory = smallBodyTrajectories[body];
          if (!trajectory?.length) {
            return null;
          }

          const positionAu = interpolateTrajectory(trajectory, currentTime, body);
          if (!positionAu) {
            return null;
          }

          return { body, positionAu };
        })
        .filter((position): position is BodyPosition => position !== null),
    [currentTime, smallBodyTrajectories, visibleSmallBodies],
  );

  const positions = useMemo(() => {
    const majorBodies = visibleBodies.filter((body) => !isSmallBody(body));
    const majorPositions = getBodyPositions(majorBodies, currentTime);
    const interpolatedByBody = new Map(
      interpolatedSmallBodyPositions.map((position) => [position.body, position]),
    );

    const smallBodyPositions = visibleSmallBodies
      .map((body) => interpolatedByBody.get(body))
      .filter((position): position is BodyPosition => position !== undefined);

    return [...majorPositions, ...smallBodyPositions];
  }, [
    currentTime,
    interpolatedSmallBodyPositions,
    visibleBodies,
    visibleSmallBodies,
  ]);

  useEffect(() => {
    if (!focusedBody) {
      return;
    }

    const exitOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setFocusedBody(null);
      }
    };
    window.addEventListener("keydown", exitOnEscape);
    return () => window.removeEventListener("keydown", exitOnEscape);
  }, [focusedBody]);

  const handleToggleBody = (body: BodyId) => {
    if (body === focusedBody && visibleBodies.includes(body)) {
      setFocusedBody(null);
    }
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
    cancelSearch();
    eventHistory.current = createEventNavigation();
    setEventResult(null);
    setEventStatus(null);

    const normalized = normalizeEventBodies(type, eventBodyA, eventBodyB);
    setEventBodyA(normalized.bodyA);
    setEventBodyB(normalized.bodyB);
  };

  const handleEventBodyAChange = (body: BodyId) => {
    setEventBodyA(body);
    cancelSearch();
    eventHistory.current = createEventNavigation();
    setEventResult(null);
    setEventStatus(null);

    const normalized = normalizeEventBodies(eventType, body, eventBodyB);
    setEventBodyA(normalized.bodyA);
    setEventBodyB(normalized.bodyB);
  };

  const handleEventBodyBChange = (body: BodyId) => {
    setEventBodyB(body);
    cancelSearch();
    eventHistory.current = createEventNavigation();
    setEventResult(null);
    setEventStatus(null);

    const normalized = normalizeEventBodies(eventType, eventBodyA, body);
    setEventBodyA(normalized.bodyA);
    setEventBodyB(normalized.bodyB);
  };

  const showEvent = ({ result, status }: EventSnapshot) => {
    setCurrentTime(result.time);
    ensureBodiesVisible(result.bodyA, result.bodyB);
    setEventResult(result);
    setFocusedBody(null); // the camera frames the event instead
    setEventStatus(status);
  };

  const rememberEvent = (result: DisplayEvent, status: string, direction: EventDirection) => {
    const snapshot = { result, status };
    eventHistory.current = recordEventNavigation(eventHistory.current, snapshot, direction);
    showEvent(snapshot);
  };

  const restoreEvent = (direction: EventDirection) => {
    const restored = stepEventNavigation(eventHistory.current, direction);
    if (!restored?.current) return false;
    cancelSearch();
    eventHistory.current = restored;
    showEvent(restored.current);
    pausePlayback();
    return true;
  };

  const handleSearchEvent = async (anchor: Date, direction: EventDirection = "next") => {
    if (!eventPairIsValid) {
      setEventStatus("Choose a valid body pair for this event type.");
      return;
    }

    searchController.current?.abort();
    const controller = new AbortController();
    searchController.current = controller;
    setIsSearching(true);
    setEventStatus(`Searching for ${direction === "prev" ? "previous" : "next"} event...`);

    const currentResult = eventHistory.current.current?.result ?? eventResult;
    const excludeId = currentResult && "id" in currentResult ? currentResult.id : undefined;
    try {
      const result =
        direction === "prev"
          ? await findPreviousEvent(eventType, eventBodyA, eventBodyB, anchor, controller.signal, excludeId)
          : await findNextEvent(eventType, eventBodyA, eventBodyB, anchor, controller.signal, excludeId);

      if (controller.signal.aborted) return;
      if (!result) {
        setEventStatus(
          `No matching events found in the ${direction === "prev" ? "previous" : "next"} ${SEARCH_HORIZON_YEARS} years.`,
        );
        return;
      }

      rememberEvent(result, result.validationStatus === "validated" ? "Loaded event." : "Loaded provisional event.", direction);
    } catch (error) {
      if (controller.signal.aborted) return;
      if (eventType !== "closestApproach" || !canUseBrowserFallback(error) || isSmallBody(eventBodyA) || isSmallBody(eventBodyB)) {
        setEventStatus(
          error instanceof Error ? error.message : "Event search failed.",
        );
        return;
      }

      const start = direction === "prev" ? addYears(anchor, -5) : anchor;
      const end = direction === "prev" ? anchor : addYears(anchor, 5);
      const result = findClosestApproach(eventBodyA, eventBodyB, start, end, direction);
      if (!result) { setEventStatus("Backend unavailable; no local minimum found in the five-year browser window."); return; }

      rememberEvent({
        ...result, angleDeg: null, magnitude: null, type: "closestApproach",
      }, `Backend unavailable; used browser calculation. ${
        error instanceof Error ? error.message : "Unknown backend error"
      }`, direction);
    } finally {
      if (!controller.signal.aborted) {
        setIsSearching(false);
        pausePlayback();
      }
    }
  };

  const handleNextEvent = () => {
    if (restoreEvent("next")) return;
    const currentResult = eventHistory.current.current?.result ?? eventResult;
    const searchAfter = currentResult ? addDays(currentResult.time, 1) : timeRef.current;
    void handleSearchEvent(searchAfter, "next");
  };

  const handlePreviousEvent = () => {
    if (restoreEvent("prev")) return;
    const currentResult = eventHistory.current.current?.result ?? eventResult;
    const searchBefore = currentResult ? addDays(currentResult.time, -1) : timeRef.current;
    void handleSearchEvent(searchBefore, "prev");
  };

  return (
    <main className="app-shell">
      <section className="hero-panel" aria-label="Solar system simulation">
          <SceneBoundary
            timeRef={timeRef}
            currentTime={currentTime}
            focusedBody={focusedBody}
            highlightedBodies={highlightedBodies}
            positions={positions}
            smallBodyTrajectories={smallBodyTrajectories}
            visibleBodies={visibleBodies}
            onFocusBody={setFocusedBody}
          />
        {focusedBody ? (
          <div className="focus-chip" role="status">
            <span>
              Following <strong>{BODY_BY_ID[focusedBody].name}</strong>
            </span>
            <button aria-label="Back to overview (Esc)" type="button" onClick={() => setFocusedBody(null)}>
              ✕
            </button>
          </div>
        ) : null}
      </section>

      <ControlPanel
        currentTime={currentTime}
        eventPairIsValid={eventPairIsValid}
        eventResult={eventResult}
        eventStatus={eventStatus}
        trajectoryStatus={[trajectoryError, trajectoryCoverageNotice(visibleBodies, smallBodyTrajectories, currentTime)].filter(Boolean).join(" ") || null}
        eventBodyA={eventBodyA}
        eventBodyB={eventBodyB}
        eventType={eventType}
        isPlaying={isPlaying}
        isSearching={isSearching}
        locksEarthAsBodyA={locksEarthAsBodyA(eventType)}
        speedDaysPerSecond={speedDaysPerSecond}
        visibleBodies={visibleBodies}
        onDateChange={(time) => { cancelSearch(); eventHistory.current = createEventNavigation(); setEventResult(null); setEventStatus(null); pausePlayback(); setCurrentTime(time); }}
        onEventBodyAChange={handleEventBodyAChange}
        onEventBodyBChange={handleEventBodyBChange}
        onEventTypeChange={handleEventTypeChange}
        onPlayToggle={() => { if (isPlaying) pausePlayback(); else setIsPlaying(true); }}
        onNextEvent={handleNextEvent}
        onPrevEvent={handlePreviousEvent}
        onSpeedChange={setSpeedDaysPerSecond}
        onToggleBody={handleToggleBody}
      />
    </main>
  );
}
