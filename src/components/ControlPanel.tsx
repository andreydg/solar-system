import {
  AU_KM,
  BODIES,
  BODY_BY_ID,
  type BodyId,
  type ClosestApproachResult,
} from "../domain/solarSystem";
import type { CSSProperties } from "react";

type ControlPanelProps = {
  currentTime: Date;
  eventResult: ClosestApproachResult | null;
  isPlaying: boolean;
  isSearching: boolean;
  speedDaysPerSecond: number;
  visibleBodies: BodyId[];
  onDateChange: (time: Date) => void;
  onPlayToggle: () => void;
  onSearchClosestApproach: () => void;
  onSpeedChange: (daysPerSecond: number) => void;
  onToggleBody: (body: BodyId) => void;
};

export default function ControlPanel({
  currentTime,
  eventResult,
  isPlaying,
  isSearching,
  speedDaysPerSecond,
  visibleBodies,
  onDateChange,
  onPlayToggle,
  onSearchClosestApproach,
  onSpeedChange,
  onToggleBody,
}: ControlPanelProps) {
  return (
    <aside className="control-panel" aria-label="Simulation controls">
      <div>
        <p className="eyebrow">Frontend-only ephemeris MVP</p>
        <h1>Solar System Explorer</h1>
        <p className="lede">
          Major planets are placed from analytical ephemeris calculations in the browser.
          Distances are physical; planet sizes and rendered spacing are intentionally scaled
          for readability.
        </p>
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
        <p>
          Search the next three years for the nearest Earth-Mars distance, then jump
          the clock to that instant.
        </p>
        <button type="button" disabled={isSearching} onClick={onSearchClosestApproach}>
          {isSearching ? "Searching..." : "Find next Earth-Mars closest approach"}
        </button>

        {eventResult ? (
          <div className="event-card">
            <strong>
              {BODY_BY_ID[eventResult.bodyA].name} to {BODY_BY_ID[eventResult.bodyB].name}
            </strong>
            <span>{formatDate(eventResult.time)} UTC</span>
            <span>{formatDistance(eventResult.distanceKm)} km</span>
            <small>
              {eventResult.distanceAu.toFixed(4)} AU, refined to about{" "}
              {(eventResult.refinementStepDays * 24).toFixed(0)} hour steps.
            </small>
          </div>
        ) : null}
      </section>

      <section className="control-section note">
        <h2>Next backend candidates</h2>
        <p>
          Add Spring Boot later for saved scenarios, precomputed event catalogs, or
          JPL Horizons validation. The current browser API is shaped so it can be
          replaced by REST calls without changing the renderer.
        </p>
      </section>
    </aside>
  );
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

function toDateTimeLocalValue(time: Date) {
  return time.toISOString().slice(0, 16);
}
