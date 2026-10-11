// W2 Today's timeline: one row per conversation of a written-up recording, one row per recording
// that isn't written up yet, and a quiet row for a written-up recording with nothing said.
// Newest first. Processing and backup stay separate fields.

import type { Conversation, Recording } from "../api/types";
import { backupStatus, processingStatus, type Status } from "./status";
import { duration, wallMs, wallTime } from "./time";

export type TimelineRow =
  | {
      kind: "conversation";
      key: string;
      sortMs: number;
      time: string;
      title: string;
      meta: string;
      conversationId: string;
      processing: Status;
      backup: Status;
    }
  | {
      kind: "recording";
      key: string;
      sortMs: number;
      time: string;
      title: string;
      meta: string;
      recordingId: string;
      processing: Status;
      backup: Status;
    }
  | { kind: "quiet"; key: string; sortMs: number; time: string; title: string; meta: string };

const KIND: Record<string, string> = {
  conversation: "Conversation",
  event: "Event",
  voice_note: "Voice note",
};

export function conversationKind(kind: string): string {
  return KIND[kind] ?? "Conversation";
}

export function timelineRows(recordings: Recording[], conversations: Conversation[]): TimelineRow[] {
  const rows: TimelineRow[] = [];
  const byRecording = new Map<string, Conversation[]>();
  for (const c of conversations) {
    const list = byRecording.get(c.recording_id) ?? [];
    list.push(c);
    byRecording.set(c.recording_id, list);
  }
  for (const r of recordings) {
    const offset = r.zone_offset_seconds;
    const start = wallMs(r.started_at, offset);
    const length = r.duration_ms ?? 0;
    const own = r.processing_state === "done" ? (byRecording.get(r.id) ?? []) : [];
    const processing = processingStatus(r);
    const backup = backupStatus(r);
    if (r.processing_state === "done" && own.length === 0) {
      const end = new Date(start + length).toISOString();
      rows.push({
        kind: "quiet",
        key: `quiet-${r.id}`,
        sortMs: start,
        time: wallTime(r.started_at, offset),
        title: `Quiet · until ${wallTime(end, 0)}`,
        meta: `${duration(length)} recorded · no speech found, nothing sent for transcription`,
      });
      continue;
    }
    if (own.length === 0) {
      rows.push({
        kind: "recording",
        key: `recording-${r.id}`,
        sortMs: start,
        time: wallTime(r.started_at, offset),
        title: r.duration_ms === null ? "Recording" : `Recording · ${duration(length)}`,
        meta:
          r.processing_state === "failed"
            ? "The audio is safe on the server. You can still play it."
            : "Notes and transcript appear here once it's written up",
        recordingId: r.id,
        processing,
        backup,
      });
      continue;
    }
    for (const c of own) {
      rows.push({
        kind: "conversation",
        key: c.id,
        sortMs: wallMs(c.started_at, offset),
        time: wallTime(c.started_at, offset),
        title: c.title,
        meta: `${duration(c.end_ms - c.start_ms)} · ${conversationKind(c.kind).toLowerCase()}`,
        conversationId: c.id,
        processing,
        backup,
      });
    }
  }
  return rows.sort((a, b) => b.sortMs - a.sortMs);
}
