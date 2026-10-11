import { describe, expect, it } from "vitest";

import { bytes, costLine, usd } from "./costs";
import { arcPath, daySpans, point, recordedMs } from "./dial";
import { conversation, recording, segment } from "./fixtures";
import { backupStatus, processingStatus, recorderStatus } from "./status";
import { highlightParts, snippetParts, speakerName } from "./text";
import {
  addDays,
  clock,
  dayLabel,
  dayTitle,
  duration,
  isIsoDay,
  recordedTotal,
  wallDay,
  wallTime,
} from "./time";
import { timelineRows } from "./today";
import { activeIndex, around, findMatches } from "./transcript";

describe("time", () => {
  it("formats positions in a recording", () => {
    expect(clock(0)).toBe("0:00");
    expect(clock(582_000)).toBe("9:42");
    expect(clock(3_845_000)).toBe("1:04:05");
  });

  it("formats lengths", () => {
    expect(duration(7_000)).toBe("7 s");
    expect(duration(44 * 60_000)).toBe("44 min");
    expect(duration(70 * 60_000)).toBe("1h 10m");
    expect(recordedTotal((8 * 60 + 27) * 60_000)).toBe("8h 27m");
    expect(recordedTotal(52 * 60_000)).toBe("52m");
  });

  it("uses the recording's own offset, not the browser's", () => {
    expect(wallTime("2026-10-10T08:30:00Z", 3600)).toBe("09:30");
    expect(wallDay("2026-10-10T23:30:00Z", 3600)).toBe("2026-10-11");
  });

  it("does day maths without zone drift", () => {
    expect(addDays("2026-10-01", -1)).toBe("2026-09-30");
    expect(addDays("2026-10-10", 1)).toBe("2026-10-11");
    expect(dayTitle("2026-10-10")).toBe("Saturday 10 Oct");
    expect(dayLabel("2026-10-10", "2026-10-10")).toBe("Today");
    expect(dayLabel("2026-10-09", "2026-10-10")).toBe("Yesterday");
    expect(dayLabel("2026-10-05", "2026-10-10")).toBe("Mon 5 Oct");
    expect(isIsoDay("2026-10-10")).toBe(true);
    expect(isIsoDay("10/10/2026")).toBe(false);
  });
});

describe("dial", () => {
  it("puts midnight at the top and runs clockwise", () => {
    const top = point(100, 100, 50, 0);
    const six = point(100, 100, 50, 0.25);
    const noon = point(100, 100, 50, 0.5);
    expect(top.x).toBeCloseTo(100);
    expect(top.y).toBeCloseTo(50);
    expect(six.x).toBeCloseTo(150);
    expect(noon.y).toBeCloseTo(150);
  });

  it("draws arcs with the right sweep", () => {
    expect(arcPath(100, 100, 50, 0, 0.25)).toBe("M 100 50 A 50 50 0 0 1 150 100");
    expect(arcPath(100, 100, 50, 0, 0.75)).toContain(" 0 1 1 ");
    expect(arcPath(100, 100, 50, 0, 1).match(/A /g)).toHaveLength(2);
  });

  it("widens very short spans", () => {
    const path = arcPath(100, 100, 50, 0.25, 0.25, 0.01);
    expect(path).not.toBe("M 150 100 A 50 50 0 0 1 150 100");
  });

  it("splits a written-up recording into quiet and conversation", () => {
    const r = recording();
    const spans = daySpans("2026-10-10", [r], [conversation({ ended_at: "2026-10-10T08:45:00Z" })]);
    expect(spans.map((s) => s.kind)).toEqual(["quiet", "conversation"]);
    expect(spans[0]!.start).toBeCloseTo(9.5 / 24);
    expect(spans[1]!.end).toBeCloseTo(9.75 / 24);
  });

  it("marks recordings that aren't written up as pending, and ignores their conversations", () => {
    const spans = daySpans(
      "2026-10-10",
      [recording({ processing_state: "transcribing" })],
      [conversation()],
    );
    expect(spans.map((s) => s.kind)).toEqual(["pending"]);
  });

  it("clips a recording that crosses midnight", () => {
    const r = recording({ started_at: "2026-10-09T21:00:00Z", duration_ms: 4 * 3_600_000 });
    // Local 22:00 on the 9th to 02:00 on the 10th.
    expect(recordedMs("2026-10-10", [r])).toBe(2 * 3_600_000);
    expect(recordedMs("2026-10-09", [r])).toBe(2 * 3_600_000);
    expect(daySpans("2026-10-10", [r], [])[0]!.start).toBe(0);
  });
});

describe("status", () => {
  it("keeps processing and backup separate", () => {
    const r = recording({ backup_state: "failed", backup_error: "Drive said no" });
    expect(processingStatus(r)).toEqual({ text: "Written up", tone: "ok" });
    expect(backupStatus(r)).toMatchObject({ text: "Backup failed", tone: "alert", detail: "Drive said no" });
  });

  it("names the processing step", () => {
    expect(processingStatus(recording({ processing_state: "transcribing" })).text).toBe(
      "Writing up · transcribing",
    );
    expect(processingStatus(recording({ transfer_state: "uploading" })).text).toBe(
      "Arriving from your phone",
    );
  });

  it("holds the recorder copy until Drive checks", () => {
    expect(recorderStatus(recording({ backup_state: "failed" })).text).toBe("Stays until Drive checks");
    expect(recorderStatus(recording({ cleanup_state: "cleared" })).text).toBe("Cleared");
  });
});

describe("text", () => {
  it("parses snippet highlights", () => {
    expect(snippetParts("keep the [[provider]] [[shortlist]] to three")).toEqual([
      { text: "keep the ", match: false },
      { text: "provider shortlist", match: true },
      { text: " to three", match: false },
    ]);
    expect(snippetParts("no marks")).toEqual([{ text: "no marks", match: false }]);
    expect(snippetParts("broken [[mark")).toEqual([{ text: "broken [[mark", match: false }]);
  });

  it("highlights a typed phrase case-insensitively", () => {
    expect(highlightParts("Provider and provider", "provider")).toEqual([
      { text: "Provider", match: true },
      { text: " and ", match: false },
      { text: "provider", match: true },
    ]);
    expect(highlightParts("text", " ")).toEqual([{ text: "text", match: false }]);
  });

  it("names unnamed speakers", () => {
    expect(speakerName("speaker_1", null)).toBe("Speaker 2");
    expect(speakerName("speaker_1", "Priya")).toBe("Priya");
  });
});

describe("transcript", () => {
  const segments = [segment(0, 0, 4000, "Hello"), segment(1, 5000, 9000, "provider list"), segment(2, 30_000, 34_000, "Provider again")];

  it("finds the playing segment", () => {
    expect(activeIndex(segments, 0)).toBe(0);
    expect(activeIndex(segments, 6000)).toBe(1);
    expect(activeIndex(segments, 20_000)).toBe(-1);
    expect(activeIndex(segments, 31_000)).toBe(2);
    expect(activeIndex([], 10)).toBe(-1);
  });

  it("finds matches and neighbours", () => {
    expect(findMatches(segments, "provider")).toEqual([1, 2]);
    expect(around(segments, 6000).map((s) => s.idx)).toEqual([0, 1, 2]);
  });
});

describe("timeline", () => {
  it("lists conversations, pending recordings and quiet stretches, newest first", () => {
    const done = recording();
    const quiet = recording({ id: "r0", started_at: "2026-10-09T23:00:00Z", duration_ms: 7 * 3_600_000 });
    const pending = recording({ id: "r2", started_at: "2026-10-10T11:40:00Z", processing_state: "waiting", backup_state: "waiting" });
    const rows = timelineRows([quiet, done, pending], [conversation()]);
    expect(rows.map((r) => r.kind)).toEqual(["recording", "conversation", "quiet"]);
    expect(rows[0]).toMatchObject({ time: "12:40", title: "Recording · 44 min" });
    expect(rows[1]).toMatchObject({ time: "09:30", title: "Studio call", meta: "44 min · conversation" });
    expect(rows[2]).toMatchObject({ time: "00:00", title: "Quiet · until 07:00" });
  });
});

describe("costs", () => {
  it("words money, hours and tokens plainly", () => {
    expect(usd(0)).toBe("$0.00");
    expect(usd(0.004)).toBe("under $0.01");
    expect(usd(12.5)).toBe("$12.50");
    expect(costLine({ service: "scribe", unit: "seconds", units: 11_520, usd: 1.2 })).toEqual({
      name: "Speech to text (Scribe)",
      amount: "3.2 h sent",
    });
    expect(costLine({ service: "openai", unit: "tokens", units: 1_400_000, usd: 3 }).amount).toBe("1.4M tokens");
    expect(bytes(2_300_000_000)).toBe("2.3 GB");
    expect(bytes(48_000_000)).toBe("48 MB");
  });
});
