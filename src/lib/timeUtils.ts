export const DAY_MS = 24 * 60 * 60 * 1000;

/** datetime-local is only an editor here: its displayed fields explicitly mean UTC. */
export function parseUtcInput(value: string): Date | null {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value)) return null;
  const time = new Date(`${value}:00Z`);
  if (!Number.isFinite(time.getTime()) || time.toISOString().slice(0, 16) !== value) return null;
  return time.getUTCFullYear() >= 1600 && time.getUTCFullYear() <= 2999 ? time : null;
}

export function addDays(time: Date, days: number): Date {
  return new Date(time.getTime() + days * DAY_MS);
}

export function addYears(time: Date, years: number): Date {
  const next = new Date(time);
  next.setUTCFullYear(next.getUTCFullYear() + years);
  return next;
}
