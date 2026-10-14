import { useEffect, useMemo, useRef, useState } from "react";
import { defaultBodies, readSharedView, shareUrl, type SharedView } from "./lib/sharedView";
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

type DisplayEvent = (ClosestApproachResult & { angleDeg: null; magnitude: null; type: "closestApproach" }) | CatalogEventResult;
type ViewEntry = {
  solarView: 1; key: string; kind: "shared" | "unshared"; search: string;
  playing: boolean; speed: number; eventResult: DisplayEvent | null; eventStatus: string | null;
};
// History identity only, not a security token; works on plain HTTP too.
const viewEntryKey = () => `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
function readViewEntry(value: unknown, expectedSearch?: string): { entry: ViewEntry; view: SharedView } | null {
  if (!value || typeof value !== "object") return null;
  const entry = value as ViewEntry;
  if (entry.solarView !== 1 || typeof entry.key !== "string" || !["shared", "unshared"].includes(entry.kind)
      || typeof entry.search !== "string" || typeof entry.playing !== "boolean"
      || !Number.isFinite(entry.speed) || entry.speed <= 0) return null;
  if (expectedSearch !== undefined && entry.search !== expectedSearch) return null;
  const view = readSharedView(entry.search);
  return view ? { entry, view } : null;
}

export default function App() {
  const [initialView] = useState(() => readSharedView(window.location.search));
  const [initialTime] = useState(() => initialView?.time ?? new Date());
  const [isPlaying, setIsPlaying] = useState(() => !initialView && !window.matchMedia("(prefers-reduced-motion: reduce)").matches);
  const [speedDaysPerSecond, setSpeedDaysPerSecond] = useState(10);
  const [visibleBodies, setVisibleBodies] = useState<BodyId[]>(initialView?.visibleBodies ?? defaultBodies);
  const [eventType, setEventType] = useState<CatalogEventType>(initialView?.eventType ?? "closestApproach");
  const [eventBodyA, setEventBodyA] = useState<BodyId>(initialView?.bodyA ?? "earth");
  const [eventBodyB, setEventBodyB] = useState<BodyId>(initialView?.bodyB ?? "mars");
  const [eventResult, setEventResult] = useState<DisplayEvent | null>(null);
  const [isSearching, setIsSearching] = useState(false);
  const [eventStatus, setEventStatus] = useState<string | null>(null);
  const { trajectories: smallBodyTrajectories, error: trajectoryError } = useSmallBodyTrajectories(visibleBodies);
  // The body the camera is following, if any; null is the Sun-centred overview.
  const [focusedBody, setFocusedBody] = useState<BodyId | null>(initialView?.focus ?? null);
  const searchController = useRef<AbortController | null>(null);
  useEffect(() => () => searchController.current?.abort(), []);
  const cancelSearch = () => {
    searchController.current?.abort();
    setIsSearching(false);
  };
  const { currentTime, setCurrentTime, timeRef, pauseClock } = useSimulationClock(initialTime, isPlaying, speedDaysPerSecond);
  const pausePlayback = () => { pauseClock(); setIsPlaying(false); };
  const [sharedLink, setSharedLink] = useState<string | null>(null);
  const viewUrl = useMemo(() => shareUrl({
    time: currentTime, visibleBodies, eventType, bodyA: eventBodyA, bodyB: eventBodyB, focus: focusedBody,
  }, window.location.origin), [currentTime, visibleBodies, eventType, eventBodyA, eventBodyB, focusedBody]);
  const [initialEntry] = useState<ViewEntry | null>(() => readViewEntry(window.history.state, window.location.search)?.entry ?? (
    initialView ? { solarView: 1, key: viewEntryKey(), kind: "shared",
      search: new URL(shareUrl(initialView, window.location.origin)).search,
      playing: false, speed: 10, eventResult: null, eventStatus: null } : null
  ));
  const historyEntry = useRef(initialEntry);
  const historySearch = useRef(window.location.search);
  const activeShare = useRef(initialView ? shareUrl(initialView, window.location.origin) : null);
  const shareView = async () => {
    const time = pauseClock();
    setIsPlaying(false);
    const url = shareUrl({ time, visibleBodies, eventType, bodyA: eventBodyA, bodyB: eventBodyB, focus: focusedBody }, window.location.origin);
    const snapshot = (kind: ViewEntry["kind"]): ViewEntry => ({
      solarView: 1, key: viewEntryKey(), kind, search: new URL(url).search,
      playing: isPlaying, speed: speedDaysPerSecond, eventResult, eventStatus: isSearching ? null : eventStatus,
    });
    // Preserve the original shared snapshot after edits; save a bare-page draft before its first Share.
    if (historyEntry.current?.kind !== "shared") {
      historyEntry.current = snapshot("unshared");
      window.history.replaceState(historyEntry.current, "", window.location.href);
    }
    const shared = { ...snapshot("shared"), playing: false };
    window.history.pushState(shared, "", url);
    historyEntry.current = shared;
    historySearch.current = window.location.search;
    activeShare.current = url;
    setSharedLink(url);
    try { await navigator.clipboard.writeText(url); } catch { /* selectable link remains available */ }
  };
  useEffect(() => {
    if (!activeShare.current || activeShare.current === viewUrl) return;
    // One history write per shared entry, never one write per animation frame.
    window.history.replaceState(historyEntry.current, "", window.location.pathname + window.location.hash);
    historySearch.current = "";
    activeShare.current = null;
    setSharedLink(null);
  }, [viewUrl]);
  useEffect(() => {
    const restore = (event: PopStateEvent) => {
      const saved = readViewEntry(event.state);
      // Hash navigation clones the same entry. Distinct bare entries still need restoring.
      if (historySearch.current === window.location.search && (!saved || saved.entry.key === historyEntry.current?.key)) return;
      const view = saved?.view ?? readSharedView(window.location.search);
      // An edited share retains its original snapshot. Restore its copyable URL once on Back.
      if (saved?.entry.kind === "shared" && !window.location.search) {
        window.history.replaceState(saved.entry, "", window.location.pathname + saved.entry.search + window.location.hash);
      }
      if (!saved?.entry.playing) pauseClock();
      historyEntry.current = saved?.entry ?? null;
      historySearch.current = window.location.search;
      activeShare.current = view && window.location.search ? shareUrl(view, window.location.origin) : null;
      searchController.current?.abort();
      setIsSearching(false); setIsPlaying(saved?.entry.playing ?? false);
      setSpeedDaysPerSecond(saved?.entry.speed ?? 10);
      setEventResult(saved?.entry.eventResult ?? null); setEventStatus(saved?.entry.eventStatus ?? null);
      setSharedLink(null);
      setCurrentTime(view?.time ?? new Date());
      setVisibleBodies(view?.visibleBodies ?? defaultBodies);
      setEventType(view?.eventType ?? "closestApproach");
      setEventBodyA(view?.bodyA ?? "earth"); setEventBodyB(view?.bodyB ?? "mars");
      setFocusedBody(view?.focus ?? null);
    };
    window.addEventListener("popstate", restore);
    return () => window.removeEventListener("popstate", restore);
  }, [pauseClock, setCurrentTime]);

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
    setEventResult(null);
    setEventStatus(null);

    const normalized = normalizeEventBodies(type, eventBodyA, eventBodyB);
    setEventBodyA(normalized.bodyA);
    setEventBodyB(normalized.bodyB);
  };

  const handleEventBodyAChange = (body: BodyId) => {
    setEventBodyA(body);
    cancelSearch();
    setEventResult(null);
    setEventStatus(null);

    const normalized = normalizeEventBodies(eventType, body, eventBodyB);
    setEventBodyA(normalized.bodyA);
    setEventBodyB(normalized.bodyB);
  };

  const handleEventBodyBChange = (body: BodyId) => {
    setEventBodyB(body);
    cancelSearch();
    setEventResult(null);
    setEventStatus(null);

    const normalized = normalizeEventBodies(eventType, eventBodyA, body);
    setEventBodyA(normalized.bodyA);
    setEventBodyB(normalized.bodyB);
  };

  const handleSearchEvent = async (anchor: Date, direction: "next" | "prev" = "next") => {
    if (!eventPairIsValid) {
      setEventStatus("Choose a valid body pair for this event type.");
      return;
    }

    searchController.current?.abort();
    const controller = new AbortController();
    searchController.current = controller;
    setIsSearching(true);
    setEventStatus(`Searching for ${direction === "prev" ? "previous" : "next"} event...`);

    try {
      const result =
        direction === "prev"
          ? await findPreviousEvent(eventType, eventBodyA, eventBodyB, anchor, controller.signal)
          : await findNextEvent(eventType, eventBodyA, eventBodyB, anchor, controller.signal);

      if (controller.signal.aborted) return;
      if (!result) {
        setEventStatus(
          `No matching events found in the ${direction === "prev" ? "previous" : "next"} ${SEARCH_HORIZON_YEARS} years.`,
        );
        return;
      }

      setCurrentTime(result.time);
      ensureBodiesVisible(result.bodyA, result.bodyB);
      setEventResult(result);
      setFocusedBody(null); // the camera frames the event instead
      setEventStatus(
        result.validationStatus === "validated"
          ? "Loaded event."
          : "Loaded provisional event.",
      );
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

      setCurrentTime(result.time);
      ensureBodiesVisible(result.bodyA, result.bodyB);
      setEventResult({
        ...result,
        angleDeg: null,
        magnitude: null,
        type: "closestApproach",
      });
      setFocusedBody(null);
      setEventStatus(
        `Backend unavailable; used browser calculation. ${
          error instanceof Error ? error.message : "Unknown backend error"
        }`,
      );
    } finally {
      if (!controller.signal.aborted) {
        setIsSearching(false);
        pausePlayback();
      }
    }
  };

  const handleNextEvent = () => {
    const searchAfter = eventResult ? addDays(eventResult.time, 1) : currentTime;
    void handleSearchEvent(searchAfter, "next");
  };

  const handlePreviousEvent = () => {
    const searchBefore = eventResult ? addDays(eventResult.time, -1) : currentTime;
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
        onShare={() => void shareView()}
        sharedLink={sharedLink}
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
        onDateChange={(time) => { cancelSearch(); setEventResult(null); setEventStatus(null); pausePlayback(); setCurrentTime(time); }}
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
