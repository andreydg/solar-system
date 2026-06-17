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
          Choose an event type in the Events panel, then step through occurrences up to 1,000 years
          out. Every event's time and geometry is refined and validated against NASA JPL Horizons.
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
