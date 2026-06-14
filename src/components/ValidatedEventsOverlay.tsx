import { useEffect, useMemo, useState } from "react";
import { AU_KM, BODIES, BODY_BY_ID, type BodyId } from "../domain/solarSystem";
import {
  EVENT_TYPES,
  formatEventTypeLabel,
  locksEarthAsBodyA,
  type CatalogEventType,
} from "../domain/eventTypes";
import { getValidatedEvents, type CatalogEventResult } from "../lib/eventCatalogApi";

type ValidatedEventsOverlayProps = {
  onClose: () => void;
};

type SortOrder = "asc" | "desc";

const ALL_FILTER = "all";

export default function ValidatedEventsOverlay({ onClose }: ValidatedEventsOverlayProps) {
  const [events, setEvents] = useState<CatalogEventResult[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [filterType, setFilterType] = useState<CatalogEventType | typeof ALL_FILTER>(ALL_FILTER);
  const [filterBodyA, setFilterBodyA] = useState<BodyId | typeof ALL_FILTER>(ALL_FILTER);
  const [filterBodyB, setFilterBodyB] = useState<BodyId | typeof ALL_FILTER>(ALL_FILTER);
  const [sortOrder, setSortOrder] = useState<SortOrder>("asc");

  useEffect(() => {
    let cancelled = false;

    void getValidatedEvents()
      .then((results) => {
        if (!cancelled) {
          setEvents(results);
        }
      })
      .catch((fetchError: unknown) => {
        if (!cancelled) {
          setError(fetchError instanceof Error ? fetchError.message : "Failed to load validated events.");
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };

    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  const filteredEvents = useMemo(() => {
    const filtered = events.filter((event) => {
      if (filterType !== ALL_FILTER && event.type !== filterType) {
        return false;
      }

      if (filterBodyA !== ALL_FILTER && event.bodyA !== filterBodyA && event.bodyB !== filterBodyA) {
        return false;
      }

      if (filterBodyB !== ALL_FILTER && event.bodyA !== filterBodyB && event.bodyB !== filterBodyB) {
        return false;
      }

      return true;
    });

    return filtered.sort((left, right) => {
      const difference = left.time.getTime() - right.time.getTime();
      return sortOrder === "asc" ? difference : -difference;
    });
  }, [events, filterBodyA, filterBodyB, filterType, sortOrder]);

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
          <h3 id="validated-events-title">All validated events</h3>
          <button type="button" className="overlay-close-button" onClick={onClose}>
            Close
          </button>
        </header>

        <div className="validated-events-filters">
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
        </div>

        <div className="validated-events-scroll">
          {loading ? <p className="status-text">Loading validated events...</p> : null}
          {error ? <p className="status-text">{error}</p> : null}
          {!loading && !error && events.length === 0 ? (
            <p className="status-text">No JPL-validated events cached yet.</p>
          ) : null}
          {!loading && !error && events.length > 0 && filteredEvents.length === 0 ? (
            <p className="status-text">No validated events match these filters.</p>
          ) : null}

          {!loading && !error
            ? filteredEvents.map((event) => (
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
                  <small>Source: JPL</small>
                </article>
              ))
            : null}
        </div>
      </div>
    </div>
  );
}

function formatEventTitle(event: CatalogEventResult) {
  if (event.type === "perihelion") {
    const target = event.bodyA === "earth" ? event.bodyB : event.bodyA;
    return `${formatEventTypeLabel(event.type)}: ${BODY_BY_ID[target].name}`;
  }

  if (locksEarthAsBodyA(event.type)) {
    const target = event.bodyA === "earth" ? event.bodyB : event.bodyA;
    return `${formatEventTypeLabel(event.type)}: ${BODY_BY_ID[target].name} (from Earth)`;
  }

  return `${formatEventTypeLabel(event.type)}: ${BODY_BY_ID[event.bodyA].name} to ${BODY_BY_ID[event.bodyB].name}`;
}

function formatDate(time: Date) {
  return new Intl.DateTimeFormat("en", {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone: "UTC",
  }).format(time);
}

function formatDistance(distanceKm: number) {
  if (distanceKm > AU_KM) {
    return `${(distanceKm / 1_000_000).toFixed(1)} million`;
  }

  return Math.round(distanceKm).toLocaleString();
}
