// Test fixtures (sample data only).
import type { Conversation, Recording, Segment } from "../api/types";

export function recording(overrides: Partial<Recording> = {}): Recording {
  return {
    id: "r1",
    recorder_serial: "8810B0000001",
    session_id: 1,
    started_at: "2026-10-10T08:30:00Z",
    zone_offset_seconds: 3600,
    duration_ms: 44 * 60_000,
    transfer_state: "uploaded",
    processing_state: "done",
    processing_error: null,
    backup_state: "backed_up",
    backup_error: null,
    cleanup_state: "blocked",
    results_version: 1,
    ...overrides,
  };
}

export function conversation(overrides: Partial<Conversation> = {}): Conversation {
  return {
    id: "c1",
    recording_id: "r1",
    day: "2026-10-10",
    kind: "conversation",
    title: "Studio call",
    summary: "",
    started_at: "2026-10-10T08:30:00Z",
    ended_at: "2026-10-10T09:14:00Z",
    start_ms: 0,
    end_ms: 44 * 60_000,
    ...overrides,
  };
}

export function segment(idx: number, start: number, end: number, text = "words"): Segment {
  return {
    id: idx + 100,
    idx,
    speaker: "speaker_0",
    speaker_name: null,
    start_ms: start,
    end_ms: end,
    text,
    original_text: text,
    corrections: [],
    edited: false,
  };
}
