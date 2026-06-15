import { useState } from "react";
import ValidatedEventsOverlay from "./ValidatedEventsOverlay";
import EventTypesOverlay from "./EventTypesOverlay";
import {
  BODIES,
  BODY_BY_ID,
  type BodyId,
} from "../domain/solarSystem";
import {
  EVENT_TYPES,
  getEventTargetOptions,
  type CatalogEventType,
} from "../domain/eventTypes";
import type { CatalogEventType as ApiCatalogEventType } from "../lib/eventCatalogApi";
import { isJplSource } from "../lib/eventCatalogApi";
import { formatDate, formatDistance, formatEventTitle, formatSource } from "../lib/formatters";
import type { CSSProperties } from "react";

type ControlPanelProps = {
  currentTime: Date;
  eventBodyA: BodyId;
  eventBodyB: BodyId;
  eventPairIsValid: boolean;
  eventResult: EventResultView | null;
  eventStatus: string | null;
  eventType: ApiCatalogEventType;
  isPlaying: boolean;
  isSearching: boolean;
  locksEarthAsBodyA: boolean;
  speedDaysPerSecond: number;
  visibleBodies: BodyId[];
  onDateChange: (time: Date) => void;
  onEventBodyAChange: (body: BodyId) => void;
  onEventBodyBChange: (body: BodyId) => void;
  onEventTypeChange: (type: ApiCatalogEventType) => void;
  onNextEvent: () => void;
  onPrevEvent: () => void;
  onPlayToggle: () => void;
  onSpeedChange: (daysPerSecond: number) => void;
  onToggleBody: (body: BodyId) => void;
};

type CatalogMetadata = {
  id: string;
  source: string;
  computedSource?: string;
  jplCheckedAtUtc: Date | null;
  jplDeltaKm: number | null;
  jplRawSummary: string | null;
  validationStatus: "pending" | "validated" | "failed";
};

type EventResultView = Partial<CatalogMetadata> & {
  angleDeg: number | null;
  bodyA: BodyId;
  bodyB: BodyId;
  distanceAu: number | null;
  distanceKm: number | null;
  magnitude: number | null;
  time: Date;
  type?: CatalogEventType;
};

export default function ControlPanel({
  currentTime,
  eventBodyA,
  eventBodyB,
  eventPairIsValid,
  eventResult,
  eventStatus,
  eventType,
  isPlaying,
  isSearching,
  locksEarthAsBodyA,
  speedDaysPerSecond,
  visibleBodies,
  onDateChange,
  onEventBodyAChange,
  onEventBodyBChange,
  onEventTypeChange,
  onNextEvent,
  onPrevEvent,
  onPlayToggle,
  onSpeedChange,
  onToggleBody,
}: ControlPanelProps) {
  const [showEventHelp, setShowEventHelp] = useState(false);
  const [showValidatedEvents, setShowValidatedEvents] = useState(false);
  const searchDisabled = isSearching || !eventPairIsValid;
  const bodyBOptions = locksEarthAsBodyA ? getEventTargetOptions(eventType) : BODIES.map((body) => body.id);

  return (
    <aside className="control-panel" aria-label="Simulation controls">
      <div>
        <h1 style={{ textAlign: "center" }}>[Solar System Explorer]</h1>
      </div>

      <section className="control-section">
        <h2>Time</h2>
        <div className="time-readout">
          <span>{formatDate(currentTime)}</span>
          <small>UTC</small>
        </div>

        <label className="field">
          <span>Jump to date</span>
          <input
            type="datetime-local"
            value={toDateTimeLocalValue(currentTime)}
            onChange={(event) => onDateChange(new Date(event.target.value))}
          />
        </label>

        <div className="button-row">
          <button type="button" onClick={onPlayToggle}>
            {isPlaying ? "Pause" : "Play"}
          </button>
          <button type="button" onClick={() => onDateChange(new Date())}>
            Today
          </button>
        </div>

        <label className="field">
          <span>Speed: {speedDaysPerSecond.toLocaleString()} days/sec</span>
          <input
            min="0.1"
            max="365"
            step="0.1"
            type="range"
            value={speedDaysPerSecond}
            onChange={(event) => onSpeedChange(Number(event.target.value))}
          />
        </label>
      </section>

      <section className="control-section">
        <h2>Bodies</h2>
        <div className="body-grid">
          {BODIES.map((body) => (
            <label className="body-toggle" key={body.id}>
              <input
                checked={visibleBodies.includes(body.id)}
                type="checkbox"
                onChange={() => onToggleBody(body.id)}
              />
              <span style={{ "--body-color": body.color } as CSSProperties} />
              {body.name}
            </label>
          ))}
        </div>
      </section>

      <section className="control-section">
        <h2>Events</h2>
        <label className="field">
          <span className="field-label-row">
            Event type
            <button
              type="button"
              className="info-button"
              aria-expanded={showEventHelp}
              aria-label="Show event type help"
              onClick={() => setShowEventHelp((visible) => !visible)}
            >
              i
            </button>
          </span>
          <select
            value={eventType}
            onChange={(event) => onEventTypeChange(event.target.value as ApiCatalogEventType)}
          >
            {EVENT_TYPES.map((type) => (
              <option key={type.id} value={type.id}>
                {type.label}
              </option>
            ))}
          </select>
        </label>

        <div className="event-select-grid">
          <label className="field">
            <span>{locksEarthAsBodyA ? "Observer" : "Body A"}</span>
            {locksEarthAsBodyA ? (
              <div className="locked-body">{BODY_BY_ID.earth.name}</div>
            ) : (
              <select
                value={eventBodyA}
                onChange={(event) => onEventBodyAChange(event.target.value as BodyId)}
              >
                {BODIES.map((body) => (
                  <option key={body.id} value={body.id}>
                    {body.name}
                  </option>
                ))}
              </select>
            )}
          </label>
          <label className="field">
            <span>{locksEarthAsBodyA ? "Target" : "Body B"}</span>
            <select
              value={eventBodyB}
              onChange={(event) => onEventBodyBChange(event.target.value as BodyId)}
            >
              {bodyBOptions.map((bodyId) => (
                <option key={bodyId} value={bodyId}>
                  {BODY_BY_ID[bodyId].name}
                </option>
              ))}
            </select>
          </label>
        </div>

        {!eventPairIsValid ? (
          <p className="status-text">Choose a valid body pair for this event type.</p>
        ) : null}

        {eventResult ? (
          <div className="event-nav-buttons">
            <button type="button" className="next-event-button" disabled={searchDisabled} onClick={onPrevEvent}>
              ‹ Prev
            </button>
            <button type="button" className="next-event-button" disabled={searchDisabled} onClick={onNextEvent}>
              Next ›
            </button>
          </div>
        ) : (
          <button type="button" className="next-event-button" disabled={searchDisabled} onClick={onNextEvent}>
            {isSearching ? "Searching..." : "Find"}
          </button>
        )}
        {eventStatus ? <p className="status-text">{eventStatus}</p> : null}

        {eventResult ? (
          <div className="event-card">
            <strong>{formatEventTitle(eventResult, eventType)}</strong>
            <span>{formatDate(eventResult.time)} UTC</span>
            {eventResult.distanceKm && eventResult.distanceAu ? (
              <>
                <span>{formatDistance(eventResult.distanceKm)} km</span>
                <small>
                  {eventResult.type === "perihelion" || eventType === "perihelion"
                    ? "Sun distance"
                    : "Separation"}
                  : {eventResult.distanceAu.toFixed(4)} AU
                </small>
              </>
            ) : null}
            {eventResult.angleDeg !== null && eventResult.angleDeg !== undefined ? (
              <small>
                {eventResult.type === "transit" || eventType === "transit"
                  ? "Sun separation"
                  : "Angle"}
                : {eventResult.angleDeg.toFixed(3)} deg
              </small>
            ) : null}
            {eventResult.magnitude !== null && eventResult.magnitude !== undefined ? (
              <small>Magnitude: {eventResult.magnitude.toFixed(2)}</small>
            ) : null}
            {eventResult.source ? <small>Source: {formatSource(eventResult.source)}</small> : null}
            {eventResult.validationStatus === "validated" ? <small>Validated</small> : null}
            {!isJplSource(eventResult.source) &&
            !isJplSource(eventResult.computedSource) &&
            (eventResult.validationStatus === "pending" || eventResult.validationStatus === "failed") ? (
              <small>Validation pending</small>
            ) : null}
          </div>
        ) : null}

        <div className="events-section-footer">
          <button
            type="button"
            className="text-link-button"
            onClick={() => setShowValidatedEvents(true)}
          >
            All validated events
          </button>
        </div>
      </section>

      {showEventHelp ? (
        <EventTypesOverlay selectedType={eventType} onClose={() => setShowEventHelp(false)} />
      ) : null}

      {showValidatedEvents ? (
        <ValidatedEventsOverlay onClose={() => setShowValidatedEvents(false)} />
      ) : null}
    </aside>
  );
}
function toDateTimeLocalValue(time: Date) {
  return time.toISOString().slice(0, 16);
}
