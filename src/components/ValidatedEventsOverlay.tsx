import { useEffect, useState } from "react";
import { BODIES, type BodyId } from "../domain/solarSystem";
import {
  EVENT_TYPES,
  type CatalogEventType,
} from "../domain/eventTypes";
import { getValidatedEvents, type CatalogEventResult, type ValidatedEventQuery } from "../lib/eventCatalogApi";
import { formatDate, formatDistance, formatEventTitle, formatSource } from "../lib/formatters";

type ValidatedEventsOverlayProps = {
  onClose: () => void;
};

type SortOrder = "asc" | "desc";

const ALL_FILTER = "all";

export default function ValidatedEventsOverlay({ onClose }: ValidatedEventsOverlayProps) {
  const [events, setEvents] = useState<CatalogEventResult[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [hasQueried, setHasQueried] = useState(false);
  const [filterType, setFilterType] = useState<CatalogEventType | typeof ALL_FILTER>(ALL_FILTER);
  const [filterBodyA, setFilterBodyA] = useState<BodyId | typeof ALL_FILTER>(ALL_FILTER);
  const [filterBodyB, setFilterBodyB] = useState<BodyId | typeof ALL_FILTER>(ALL_FILTER);
  const [sortOrder, setSortOrder] = useState<SortOrder>("asc");
  const [from, setFrom] = useState("1600-01-01");
  const [to, setTo] = useState("2999-12-31");
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [activeQuery, setActiveQuery] = useState<ValidatedEventQuery | null>(null);

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };

    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  const runQuery = (more = false) => {
    const query: ValidatedEventQuery = more && activeQuery ? { ...activeQuery, cursor: nextCursor ?? undefined } : {
      type: filterType === ALL_FILTER ? undefined : filterType,
      bodyA: filterBodyA === ALL_FILTER ? undefined : filterBodyA,
      bodyB: filterBodyB === ALL_FILTER ? undefined : filterBodyB,
      from: `${from}T00:00:00Z`,
      to: `${to}T23:59:59Z`,
      descending: sortOrder === "desc",
    };
    if (!more && (!from || !to || from > to)) { setError("Choose an increasing date range."); return; }
    setLoading(true);
    setError(null);
    if (!more) { setEvents([]); setNextCursor(null); setActiveQuery(query); }
    void getValidatedEvents(query)
      .then((results) => {
        setEvents(previous => more ? [...previous, ...results.events] : results.events);
        setNextCursor(results.nextCursor);
        setHasQueried(true);
      })
      .catch((fetchError: unknown) => {
        setError(fetchError instanceof Error ? fetchError.message : "Failed to query validated events.");
      })
      .finally(() => {
        setLoading(false);
      });
  };

  return (
    <div className="validated-events-backdrop" onClick={onClose}>
      <div
        className="validated-events-overlay"
        role="dialog"
        aria-labelledby="validated-events-title"
        aria-modal="true"
        onClick={(event) => event.stopPropagation()}
      >
        <header className="validated-events-header">
          <h3 id="validated-events-title">Validated events</h3>
          <button type="button" className="overlay-close-button" onClick={onClose}>
            Close
          </button>
        </header>

        <div className="validated-events-filters">
          <label className="field"><span>From (UTC)</span><input type="date" value={from} onChange={event => setFrom(event.target.value)} /></label>
          <label className="field"><span>To (UTC)</span><input type="date" value={to} onChange={event => setTo(event.target.value)} /></label>
          <label className="field">
            <span>Type</span>
            <select
              value={filterType}
              onChange={(event) =>
                setFilterType(event.target.value as CatalogEventType | typeof ALL_FILTER)
              }
            >
              <option value={ALL_FILTER}>All types</option>
              {EVENT_TYPES.map((type) => (
                <option key={type.id} value={type.id}>
                  {type.label}
                </option>
              ))}
            </select>
          </label>

          <label className="field">
            <span>Body A</span>
            <select
              value={filterBodyA}
              onChange={(event) => setFilterBodyA(event.target.value as BodyId | typeof ALL_FILTER)}
            >
              <option value={ALL_FILTER}>All bodies</option>
              {BODIES.map((body) => (
                <option key={body.id} value={body.id}>
                  {body.name}
                </option>
              ))}
            </select>
          </label>

          <label className="field">
            <span>Body B</span>
            <select
              value={filterBodyB}
              onChange={(event) => setFilterBodyB(event.target.value as BodyId | typeof ALL_FILTER)}
            >
              <option value={ALL_FILTER}>All bodies</option>
              {BODIES.map((body) => (
                <option key={body.id} value={body.id}>
                  {body.name}
                </option>
              ))}
            </select>
          </label>

          <label className="field">
            <span>Sort by time</span>
            <select
              value={sortOrder}
              onChange={(event) => setSortOrder(event.target.value as SortOrder)}
            >
              <option value="asc">Earliest first</option>
              <option value="desc">Latest first</option>
            </select>
          </label>

          <button
            type="button"
            className="next-event-button validated-events-query"
            disabled={loading}
            onClick={() => runQuery()}
          >
            {loading ? "Querying..." : "Query"}
          </button>
        </div>

        <div className="validated-events-scroll">
          {activeQuery ? <p className="status-text">
            Results for {activeQuery.from?.slice(0, 10)} – {activeQuery.to?.slice(0, 10)},
            {activeQuery.descending ? " latest first" : " earliest first"}. Selector changes apply when you click Query.
          </p> : null}
          {error ? <p className="status-text">{error}</p> : null}
          {!loading && !error && !hasQueried ? (
            <p className="status-text">Choose criteria and run a query to list validated events.</p>
          ) : null}
          {!loading && !error && hasQueried && events.length === 0 ? (
            <p className="status-text">No validated events match these criteria.</p>
          ) : null}

          {!error
            ? events.map((event) => (
                <article className="validated-event-row" key={event.id}>
                  <strong>{formatEventTitle(event)}</strong>
                  <span>{formatDate(event.time)} UTC</span>
                  {event.distanceKm && event.distanceAu ? (
                    <small>
                      {event.type === "perihelion" ? "Sun distance" : "Separation"}:{" "}
                      {formatDistance(event.distanceKm)} km ({event.distanceAu.toFixed(4)} AU)
                    </small>
                  ) : null}
                  {event.angleDeg !== null ? (
                    <small>
                      {event.type === "transit" ? "Sun separation" : "Angle"}: {event.angleDeg.toFixed(3)} deg
                    </small>
                  ) : null}
                  {event.magnitude !== null ? <small>Magnitude: {event.magnitude.toFixed(2)}</small> : null}
                  <small>Source: {formatSource(event.source)}</small>
                </article>
              ))
            : null}
          {nextCursor && !error ? <button type="button" disabled={loading} onClick={() => runQuery(true)}>
            {loading ? "Loading…" : "Load more"}
          </button> : null}
        </div>
      </div>
    </div>
  );
}
