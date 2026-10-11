// Times and days. A recording's wall-clock times use the phone's UTC offset when it was recorded
// (spec §4, "Times"), not the browser's zone, so a day reads the same on the phone and the web.
// Days are ISO dates ("2026-10-10"); date maths runs in UTC so no zone can shift them.

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
export const DAY_MS = 24 * HOUR;

/** A position inside a recording: "9:42", or "1:04:05" past an hour. */
export function clock(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const ss = String(s).padStart(2, "0");
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${ss}` : `${m}:${ss}`;
}

/** A length in words: "7 s", "44 min", "1h 10m". */
export function duration(ms: number): string {
  if (ms < MINUTE) return `${Math.max(0, Math.round(ms / 1000))} s`;
  if (ms < HOUR) return `${Math.round(ms / MINUTE)} min`;
  const h = Math.floor(ms / HOUR);
  const m = Math.round((ms % HOUR) / MINUTE);
  return m === 60 ? `${h + 1}h 0m` : `${h}h ${m}m`;
}

/** The dial's centre figure: "8h 27m", "52m", "0m". */
export function recordedTotal(ms: number): string {
  const minutes = Math.round(ms / MINUTE);
  const h = Math.floor(minutes / 60);
  return h > 0 ? `${h}h ${minutes % 60}m` : `${minutes}m`;
}

/** Milliseconds since the Unix epoch of the wall clock where it was recorded. */
export function wallMs(iso: string, offsetSeconds: number): number {
  return Date.parse(iso) + offsetSeconds * 1000;
}

/** "09:30" in the recording's own zone. */
export function wallTime(iso: string, offsetSeconds: number): string {
  return hhmm(new Date(wallMs(iso, offsetSeconds)));
}

/** "09:30" from a Date whose UTC fields hold a wall clock. */
function hhmm(d: Date): string {
  return `${String(d.getUTCHours()).padStart(2, "0")}:${String(d.getUTCMinutes()).padStart(2, "0")}`;
}

/** The ISO day of a wall clock. */
export function wallDay(iso: string, offsetSeconds: number): string {
  return new Date(wallMs(iso, offsetSeconds)).toISOString().slice(0, 10);
}

/** Midnight at the start of an ISO day, on the wall-clock axis. */
export function dayStartMs(day: string): number {
  return Date.parse(`${day}T00:00:00Z`);
}

/** Today's ISO day in the browser's zone. */
export function localToday(now: number = Date.now()): string {
  const d = new Date(now);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

/** The browser-local ISO day of an instant. */
export function localDayOf(iso: string): string {
  return localToday(Date.parse(iso));
}

/** Fraction of the browser's local day that has passed (0–1). */
export function localDayFraction(now: number = Date.now()): number {
  const d = new Date(now);
  return (d.getHours() * 3600 + d.getMinutes() * 60 + d.getSeconds()) / 86_400;
}

/** "14:32" in the browser's zone. */
export function localTime(now: number | string): string {
  const d = new Date(now);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

export function addDays(day: string, n: number): string {
  return new Date(dayStartMs(day) + n * DAY_MS).toISOString().slice(0, 10);
}

export function isIsoDay(value: unknown): value is string {
  return typeof value === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(dayStartMs(value));
}

const longDay = new Intl.DateTimeFormat("en-GB", {
  weekday: "long",
  day: "numeric",
  month: "short",
  timeZone: "UTC",
});
const shortDay = new Intl.DateTimeFormat("en-GB", {
  weekday: "short",
  day: "numeric",
  month: "short",
  timeZone: "UTC",
});

/** "Saturday 10 Oct". */
export function dayTitle(day: string): string {
  return longDay.format(new Date(dayStartMs(day))).replace(",", "");
}

/** "Today", "Yesterday" or "Mon 5 Oct". */
export function dayLabel(day: string, today: string): string {
  if (day === today) return "Today";
  if (day === addDays(today, -1)) return "Yesterday";
  return shortDay.format(new Date(dayStartMs(day))).replace(",", "");
}

/** "Today 09:30" / "Mon 5 Oct 10:00" for a recording-zone instant. */
export function whenLabel(iso: string, offsetSeconds: number, today: string): string {
  return `${dayLabel(wallDay(iso, offsetSeconds), today)} ${wallTime(iso, offsetSeconds)}`;
}

/** "English" from "en"; the code itself if the browser doesn't know it. */
export function languageName(code: string | null): string | null {
  if (!code) return null;
  try {
    return new Intl.DisplayNames(["en"], { type: "language" }).of(code) ?? code;
  } catch {
    return code;
  }
}
