// The 24-hour dial (Tide Clock): midnight at the top, clockwise. Pure, so the rules are tested;
// the Dial component only turns spans into SVG.

import type { Conversation, Recording } from "../api/types";
import { DAY_MS, dayStartMs, wallMs } from "./time";

/**
 * `conversation`: someone talking (from processing). `quiet`: recorded, processed, no speech.
 * `pending`: recorded but not written up yet, so it can't be split yet ("Not written up yet").
 */
export type SpanKind = "conversation" | "quiet" | "pending";

/** A stretch of the day as fractions (0 = midnight at the top, 1 = the next midnight). */
export type DialSpan = { start: number; end: number; kind: SpanKind; conversationId?: string };

/** Clip [from, to) (wall-clock ms) to the day; null when nothing is left. */
function clip(day0: number, from: number, to: number): { start: number; end: number } | null {
  const a = Math.max(from, day0);
  const b = Math.min(to, day0 + DAY_MS);
  if (b <= a) return null;
  return { start: (a - day0) / DAY_MS, end: (b - day0) / DAY_MS };
}

function recordingRange(r: Recording): [number, number] {
  const from = wallMs(r.started_at, r.zone_offset_seconds);
  return [from, from + (r.duration_ms ?? 0)];
}

/**
 * The spans for one day, drawn in order (quiet and pending first, conversations on top).
 * A processed recording is quiet except where its conversations are.
 */
export function daySpans(day: string, recordings: Recording[], conversations: Conversation[]): DialSpan[] {
  const day0 = dayStartMs(day);
  const base: DialSpan[] = [];
  const top: DialSpan[] = [];
  const byRecording = new Map(recordings.map((r) => [r.id, r]));
  for (const r of recordings) {
    const [from, to] = recordingRange(r);
    const span = clip(day0, from, to);
    if (!span) continue;
    base.push({ ...span, kind: r.processing_state === "done" ? "quiet" : "pending" });
  }
  for (const c of conversations) {
    const r = byRecording.get(c.recording_id);
    if (!r || r.processing_state !== "done") continue;
    const span = clip(
      day0,
      wallMs(c.started_at, r.zone_offset_seconds),
      wallMs(c.ended_at, r.zone_offset_seconds),
    );
    if (span) top.push({ ...span, kind: "conversation", conversationId: c.id });
  }
  const byStart = (a: DialSpan, b: DialSpan) => a.start - b.start;
  return [...base.sort(byStart), ...top.sort(byStart)];
}

/** How much of the day was recorded, in ms (recordings don't overlap). */
export function recordedMs(day: string, recordings: Recording[]): number {
  const day0 = dayStartMs(day);
  return recordings.reduce((sum, r) => {
    const [from, to] = recordingRange(r);
    return sum + Math.max(0, Math.min(to, day0 + DAY_MS) - Math.max(from, day0));
  }, 0);
}

/** A point on a circle at a fraction of the day (clockwise from the top). */
export function point(cx: number, cy: number, r: number, fraction: number): { x: number; y: number } {
  const angle = fraction * 2 * Math.PI;
  return { x: cx + r * Math.sin(angle), y: cy - r * Math.cos(angle) };
}

const round = (n: number) => Math.round(n * 100) / 100;

/**
 * An SVG arc path from one fraction to another, clockwise. Very short spans are widened to
 * `minFraction` around their middle so a 2-minute voice note is still visible.
 */
export function arcPath(
  cx: number,
  cy: number,
  r: number,
  start: number,
  end: number,
  minFraction = 0,
): string {
  let a = start;
  let b = end;
  if (b - a < minFraction) {
    const mid = (a + b) / 2;
    a = mid - minFraction / 2;
    b = mid + minFraction / 2;
  }
  if (b - a >= 1) {
    // A full circle can't be one arc: draw two halves.
    const top = point(cx, cy, r, 0);
    const bottom = point(cx, cy, r, 0.5);
    return `M ${round(top.x)} ${round(top.y)} A ${r} ${r} 0 1 1 ${round(bottom.x)} ${round(bottom.y)} A ${r} ${r} 0 1 1 ${round(top.x)} ${round(top.y)}`;
  }
  const p0 = point(cx, cy, r, a);
  const p1 = point(cx, cy, r, b);
  const large = b - a > 0.5 ? 1 : 0;
  return `M ${round(p0.x)} ${round(p0.y)} A ${r} ${r} 0 ${large} 1 ${round(p1.x)} ${round(p1.y)}`;
}
