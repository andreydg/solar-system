import { useEffect } from "react";
import {
  EVENT_TYPES,
  formatEventPairingNote,
  type CatalogEventType,
} from "../domain/eventTypes";

type EventTypesOverlayProps = {
  selectedType?: CatalogEventType;
  onClose: () => void;
};

export default function EventTypesOverlay({ selectedType, onClose }: EventTypesOverlayProps) {
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };

    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  return (
    <div className="validated-events-backdrop" onClick={onClose}>
      <div
        className="validated-events-overlay event-types-overlay"
        role="dialog"
        aria-labelledby="event-types-title"
        aria-modal="true"
        onClick={(event) => event.stopPropagation()}
      >
        <header className="validated-events-header">
          <h3 id="event-types-title">Event types</h3>
          <button type="button" className="overlay-close-button" onClick={onClose}>
            Close
          </button>
        </header>

        <p className="event-types-intro">
          Choose an event type, then step through occurrences within 1600–2999 (up to 1,000 years per search).
          Results start as provisional estimates. Only a “JPL geometry checked” result has passed
          event-specific geometry and timing checks against NASA JPL Horizons. Refinement uses a
          5-minute grid; brightness remains modeled, and these are not local visibility predictions.
        </p>

        <div className="validated-events-scroll">
          {EVENT_TYPES.map((type) => (
            <article
              className={`validated-event-row${selectedType === type.id ? " event-type-row-selected" : ""}`}
              key={type.id}
            >
              <strong>{type.label}</strong>
              <small>{formatEventPairingNote(type.id)}</small>
              <span>{type.description}</span>
            </article>
          ))}
        </div>
      </div>
    </div>
  );
}
