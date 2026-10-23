export type EventDirection = "next" | "prev";
export const EVENT_HISTORY_LIMIT = 100;

export type EventNavigation<T> = {
  previous: T[];
  current: T | null;
  next: T[];
};

export function createEventNavigation<T>(current: T | null = null): EventNavigation<T> {
  return { previous: [], current, next: [] };
}

// Record only successful searches. Failed/cancelled searches do not consume history.
export function recordEventNavigation<T>(history: EventNavigation<T>, current: T, direction: EventDirection): EventNavigation<T> {
  if (history.current === null) return createEventNavigation(current);
  return direction === "next"
    ? { previous: [...history.previous, history.current].slice(-(EVENT_HISTORY_LIMIT - 1)), current, next: [] }
    : { previous: [], current, next: [...history.next, history.current].slice(-(EVENT_HISTORY_LIMIT - 1)) };
}

// Replay the exact displayed snapshot, not a fresh search with a potentially shifted timestamp.
export function stepEventNavigation<T>(history: EventNavigation<T>, direction: EventDirection): EventNavigation<T> | null {
  const stack = direction === "next" ? history.next : history.previous;
  if (history.current === null || stack.length === 0) return null;
  const current = stack[stack.length - 1];
  return direction === "next"
    ? { previous: [...history.previous, history.current], current, next: history.next.slice(0, -1) }
    : { previous: history.previous.slice(0, -1), current, next: [...history.next, history.current] };
}
