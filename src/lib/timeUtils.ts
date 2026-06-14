export const DAY_MS = 24 * 60 * 60 * 1000;

export function addDays(time: Date, days: number): Date {
  return new Date(time.getTime() + days * DAY_MS);
}

export function addYears(time: Date, years: number): Date {
  const next = new Date(time);
  next.setUTCFullYear(next.getUTCFullYear() + years);
  return next;
}
