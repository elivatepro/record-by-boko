// Transcript helpers: which segment is playing, where a source points, and find-in-transcript.

import type { Segment } from "../api/types";

/**
 * Index of the segment playing at `ms`: the last one that has started. Segments are sorted by
 * start. Returns -1 before the first, and -1 in a gap longer than `gapMs` after a segment ends.
 */
export function activeIndex(segments: readonly Segment[], ms: number, gapMs = 1500): number {
  let lo = 0;
  let hi = segments.length - 1;
  let found = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (segments[mid]!.start_ms <= ms) {
      found = mid;
      lo = mid + 1;
    } else {
      hi = mid - 1;
    }
  }
  if (found === -1) return -1;
  return ms <= segments[found]!.end_ms + gapMs ? found : -1;
}

/** Segments by their `idx` (what sources point at). */
export function byIdx(segments: readonly Segment[]): Map<number, Segment> {
  return new Map(segments.map((s) => [s.idx, s]));
}

/** The source segments that exist, in transcript order. */
export function sourceSegments(sources: readonly number[], index: Map<number, Segment>): Segment[] {
  return sources
    .map((idx) => index.get(idx))
    .filter((s): s is Segment => s !== undefined)
    .sort((a, b) => a.start_ms - b.start_ms);
}

/** Indexes (into `segments`) of every segment containing `query`, case-insensitive. */
export function findMatches(segments: readonly Segment[], query: string): number[] {
  const q = query.trim().toLocaleLowerCase();
  if (!q) return [];
  const out: number[] = [];
  segments.forEach((s, i) => {
    if (s.text.toLocaleLowerCase().includes(q)) out.push(i);
  });
  return out;
}

/** The segment around a time, with its neighbours (Search preview). */
export function around(segments: readonly Segment[], ms: number): Segment[] {
  if (segments.length === 0) return [];
  let i = activeIndex(segments, ms, Number.POSITIVE_INFINITY);
  if (i === -1) i = 0;
  return segments.slice(Math.max(0, i - 1), i + 2);
}
