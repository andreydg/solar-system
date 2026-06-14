import { useEffect, useMemo, useRef, useState } from "react";
import ControlPanel from "./components/ControlPanel";
import SolarSystemScene from "./components/SolarSystemScene";
import { BODIES, isSmallBody, type BodyId, type BodyPosition, type ClosestApproachResult } from "./domain/solarSystem";
import {
  EVENT_TYPE_BY_ID,
  getEventTargetOptions,
  isValidEventPair,
  locksEarthAsBodyA,
  type CatalogEventType,
} from "./domain/eventTypes";
import { findClosestApproach, getBodyPositions } from "./lib/ephemeris";
import { getBackendBodyPositions } from "./lib/ephemerisApi";
import { interpolateTrajectory, loadSmallBodyTrajectoriesWithRetry, type SmallBodyTrajectory } from "./lib/smallBodyTrajectory";
import { findNextEvent, isJplSource, SEARCH_HORIZON_YEARS, type CatalogEventResult } from "./lib/eventCatalogApi";
import { addDays, addYears } from "./lib/timeUtils";

const INITIAL_DATE = new Date();
const DEFAULT_VISIBLE_BODIES = BODIES.filter((body) => !isSmallBody(body.id)).map((body) => body.id);

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
  const [smallBodyTrajectories, setSmallBodyTrajectories] = useState<
    Partial<Record<BodyId, SmallBodyTrajectory>>
  >({});
  const [liveSmallBodyPositions, setLiveSmallBodyPositions] = useState<BodyPosition[]>([]);
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

  useEffect(() => {
    let cancelled = false;

    void loadSmallBodyTrajectoriesWithRetry().then((trajectories) => {
      if (!cancelled) {
        setSmallBodyTrajectories(trajectories);
      }
    });

    return () => {
      cancelled = true;
    };
  }, []);

  const visibleSmallBodies = useMemo(
    () => visibleBodies.filter((body) => isSmallBody(body)),
    [visibleBodies],
  );
  const simulatedDayKey = Math.floor(currentTime.getTime() / 86_400_000);

  const smallBodiesNeedingLiveFetch = useMemo(
    () =>
      visibleSmallBodies.filter((body) => {
        const trajectory = smallBodyTrajectories[body];
        return !trajectory?.length;
      }),
    [smallBodyTrajectories, visibleSmallBodies],
  );

  useEffect(() => {
    if (smallBodiesNeedingLiveFetch.length === 0) {
      setLiveSmallBodyPositions([]);
      return;
    }

    let cancelled = false;

    void getBackendBodyPositions(smallBodiesNeedingLiveFetch, currentTime)
      .then((positions) => {
        if (!cancelled) {
          setLiveSmallBodyPositions(positions);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setLiveSmallBodyPositions([]);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [simulatedDayKey, smallBodiesNeedingLiveFetch]);

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
    const liveByBody = new Map(liveSmallBodyPositions.map((position) => [position.body, position]));
    const interpolatedByBody = new Map(
      interpolatedSmallBodyPositions.map((position) => [position.body, position]),
    );

    const smallBodyPositions = visibleSmallBodies
      .map((body) => interpolatedByBody.get(body) ?? liveByBody.get(body))
      .filter((position): position is BodyPosition => position !== undefined);

    return [...majorPositions, ...smallBodyPositions];
  }, [
    currentTime,
    interpolatedSmallBodyPositions,
    liveSmallBodyPositions,
    visibleBodies,
    visibleSmallBodies,
  ]);

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
        result.validationStatus === "validated" || isJplSource(result.source) || isJplSource(result.computedSource)
          ? "Loaded event."
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
          smallBodyTrajectories={smallBodyTrajectories}
          visibleBodies={visibleBodies}
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
