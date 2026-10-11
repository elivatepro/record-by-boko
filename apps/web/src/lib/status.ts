// Status words. Processing and backup are always separate words (design rule, spec §3):
// "Written up" never implies "Backed up".

import type { Recording } from "../api/types";

/** `progress` shows a spinner, `ok` a check/cloud, `alert` the failure colour, `signal` buoy text. */
export type Tone = "muted" | "progress" | "ok" | "alert" | "signal";
export type Status = { text: string; tone: Tone; detail?: string };

const STEP: Record<string, string> = {
  finding_speech: "finding speech",
  transcribing: "transcribing",
  tidying: "tidying",
  understanding: "writing notes",
};

export function processingStatus(r: Recording): Status {
  if (r.transfer_state !== "uploaded") return { text: "Arriving from your phone", tone: "signal" };
  switch (r.processing_state) {
    case "waiting":
      return { text: "Waiting to write up", tone: "muted" };
    case "done":
      return { text: "Written up", tone: "ok" };
    case "failed":
      return { text: "Writing up failed", tone: "alert", detail: r.processing_error ?? undefined };
    default: {
      const step = STEP[r.processing_state];
      return { text: step ? `Writing up · ${step}` : "Writing up", tone: "progress" };
    }
  }
}

export function backupStatus(r: Recording): Status {
  if (r.transfer_state !== "uploaded") return { text: "After transfer", tone: "muted" };
  switch (r.backup_state) {
    case "uploading":
      return { text: "Backing up", tone: "progress" };
    case "checking":
      return { text: "Checking backup", tone: "progress" };
    case "backed_up":
      return { text: "Backed up", tone: "ok" };
    case "failed":
      return { text: "Backup failed", tone: "alert", detail: r.backup_error ?? undefined };
    default:
      return { text: "Not backed up yet", tone: "muted" };
  }
}

/** The Drive column of Backups (W8). */
export function driveStatus(r: Recording): Status {
  if (r.transfer_state !== "uploaded") return { text: "After transfer", tone: "muted" };
  switch (r.backup_state) {
    case "uploading":
      return { text: "Uploading", tone: "progress" };
    case "checking":
      return { text: "Checking", tone: "progress" };
    case "backed_up":
      return { text: "Checked", tone: "ok" };
    case "failed":
      return { text: "Failed", tone: "alert", detail: r.backup_error ?? undefined };
    default:
      return r.processing_state === "done"
        ? { text: "Waiting", tone: "muted" }
        : { text: "After write-up", tone: "muted" };
  }
}

/** The Server column of Backups: the upload counts only once the checksum matches. */
export function serverStatus(r: Recording): Status {
  return r.transfer_state === "uploaded"
    ? { text: "Matches", tone: "ok" }
    : { text: "Arriving", tone: "progress" };
}

/** The Recorder column of Backups: clean-up is gated on the Drive check (spec §8). */
export function recorderStatus(r: Recording): Status {
  switch (r.cleanup_state) {
    case "cleared":
      return { text: "Cleared", tone: "muted" };
    case "deleting":
      return { text: "Clearing", tone: "progress" };
    case "can_clean_up":
      return { text: "Waiting", tone: "signal" };
    default:
      return r.backup_state === "backed_up"
        ? { text: "Waiting", tone: "signal" }
        : { text: "Stays until Drive checks", tone: "signal" };
  }
}

export function isWritingUp(r: Recording): boolean {
  return r.transfer_state === "uploaded" && r.processing_state !== "done" && r.processing_state !== "failed";
}
