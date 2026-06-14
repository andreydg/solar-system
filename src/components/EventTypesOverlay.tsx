import { useEffect } from "react";
import {
  EVENT_TYPES,
  JPL_VALIDATED_EVENT_TYPES,
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
          Choose an event type in the Events panel, then search forward up to 1,000 years for the
          next occurrence.
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
              {JPL_VALIDATED_EVENT_TYPES.has(type.id) ? (
                <small className="event-type-badge">JPL validated</small>
              ) : null}
            </article>
          ))}
        </div>
      </div>
    </div>
  );
}
