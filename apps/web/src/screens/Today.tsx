// W2 Today (and any past day): the 24-hour dial, the digest card, and the timeline with separate
// Processing and Drive backup columns. W10 narrows it.
import { Link, useNavigate } from "@tanstack/react-router";
import { useRef } from "react";

import { useDay } from "../api/queries";
import type { Digest } from "../api/types";
import { ErrorState, Loading, StatusWord } from "../components/common";
import { Dial, DialLegend } from "../components/Dial";
import { Icon } from "../components/Icon";
import { daySpans, recordedMs } from "../lib/dial";
import { useNow, useOnline } from "../lib/hooks";
import {
  addDays,
  dayTitle,
  localDayFraction,
  localTime,
  localToday,
  recordedTotal,
} from "../lib/time";
import { timelineRows, type TimelineRow } from "../lib/today";

export function Today({ day: requested }: { day?: string }) {
  const now = useNow();
  const today = localToday(now);
  const day = requested ?? today;
  const isToday = day === today;
  const query = useDay(day);
  const online = useOnline();

  const eyebrow = isToday ? "Today" : day === addDays(today, -1) ? "Yesterday" : day > today ? "Not yet" : "Past day";

  return (
    <div className="page today">
      <header className="page-header">
        <div>
          <p className="eyebrow">{eyebrow}</p>
          <h1 className="page-title">{dayTitle(day)}</h1>
        </div>
        <DayControls day={day} today={today} />
      </header>

      {query.isPending ? (
        <Loading label="Loading the day…" />
      ) : query.isError ? (
        <ErrorState
          title={online ? "This day didn’t load" : "No internet"}
          error={online ? query.error : new Error("The web needs the internet to show your day. It will load when you’re back online.")}
          onRetry={() => void query.refetch()}
        />
      ) : (
        <DayBody
          day={day}
          isToday={isToday}
          nowFraction={isToday ? localDayFraction(now) : null}
          nowLabel={isToday ? `Now ${localTime(now)}` : undefined}
          data={query.data}
        />
      )}
    </div>
  );
}

function DayControls({ day, today }: { day: string; today: string }) {
  const navigate = useNavigate();
  const picker = useRef<HTMLInputElement>(null);
  const prev = addDays(day, -1);
  const next = addDays(day, 1);
  const go = (target: string) =>
    target === today ? navigate({ to: "/" }) : navigate({ to: "/day/$day", params: { day: target } });

  return (
    <div className="day-controls">
      {day !== today && (
        <Link to="/" className="button button--soft">
          Back to today
        </Link>
      )}
      <button type="button" className="icon-button" aria-label={`Previous day, ${dayTitle(prev)}`} onClick={() => void go(prev)}>
        <Icon name="chevronLeft" />
      </button>
      <button
        type="button"
        className="icon-button"
        aria-label={`Next day, ${dayTitle(next)}`}
        disabled={day >= today}
        onClick={() => void go(next)}
      >
        <Icon name="chevronRight" />
      </button>
      <label className="button button--soft date-button">
        <Icon name="calendar" />
        <span className="date-button__text">Choose date</span>
        <input
          ref={picker}
          type="date"
          className="date-button__input"
          value={day}
          max={today}
          aria-label="Choose date"
          onClick={(event) => {
            try {
              event.currentTarget.showPicker();
            } catch {
              // Older browsers open the picker on focus by themselves.
            }
          }}
          onChange={(event) => {
            const value = event.currentTarget.value;
            if (value) void go(value > today ? today : value);
          }}
        />
      </label>
    </div>
  );
}

function DayBody({
  day,
  isToday,
  nowFraction,
  nowLabel,
  data,
}: {
  day: string;
  isToday: boolean;
  nowFraction: number | null;
  nowLabel?: string;
  data: NonNullable<ReturnType<typeof useDay>["data"]>;
}) {
  const spans = daySpans(day, data.recordings, data.conversations);
  const total = recordedMs(day, data.recordings);
  const rows = timelineRows(data.recordings, data.conversations);
  const pending = spans.some((s) => s.kind === "pending");
  const conversations = data.conversations.length;

  return (
    <div className="today__grid">
      <section className="today__picture" aria-label="The day at a glance">
        <Dial
          spans={spans}
          now={nowFraction}
          centre={recordedTotal(total)}
          caption={isToday ? "recorded today" : "recorded"}
          nowLabel={nowLabel}
          label={`24-hour dial: ${recordedTotal(total)} recorded, ${conversations} ${conversations === 1 ? "conversation" : "conversations"}. The list beside it has every recording.`}
        />
        <DialLegend pending={pending} />
        {data.recordings.length > 0 && <DigestCard day={day} digest={data.digest} />}
      </section>

      <section className="timeline" aria-labelledby="timeline-title">
        <div className="timeline__head">
          <h2 id="timeline-title">
            Conversations and events{" "}
            <span className="timeline__count">
              {data.recordings.length} {data.recordings.length === 1 ? "recording" : "recordings"}
            </span>
          </h2>
          <span className="timeline__col" aria-hidden="true">
            Processing
          </span>
          <span className="timeline__col" aria-hidden="true">
            Drive backup
          </span>
        </div>
        {rows.length === 0 ? (
          <p className="timeline__empty">
            {isToday
              ? "Nothing has arrived from your phone yet today. Recordings show up here once your phone has uploaded them."
              : "Nothing was recorded on this day."}
          </p>
        ) : (
          <ol className="timeline__rows">
            {rows.map((row) => (
              <TimelineItem key={row.key} row={row} />
            ))}
          </ol>
        )}
      </section>
    </div>
  );
}

function TimelineItem({ row }: { row: TimelineRow }) {
  if (row.kind === "quiet") {
    return (
      <li className="timeline-row timeline-row--quiet">
        <span className="timeline-row__bar" aria-hidden="true" />
        <span className="timeline-row__time tabular">{row.time}</span>
        <div className="timeline-row__main">
          <p className="timeline-row__title">{row.title}</p>
          <p className="timeline-row__meta">{row.meta}</p>
        </div>
      </li>
    );
  }
  const failed = row.processing.tone === "alert" || row.backup.tone === "alert";
  const pending = row.kind === "recording";
  return (
    <li className={`timeline-row${failed ? " timeline-row--attention" : ""}${pending ? " timeline-row--pending" : ""}`}>
      <span className="timeline-row__bar" aria-hidden="true" />
      <span className="timeline-row__time tabular">{row.time}</span>
      <div className="timeline-row__main">
        {row.kind === "conversation" ? (
          <Link
            to="/c/$conversationId"
            params={{ conversationId: row.conversationId }}
            className="timeline-row__title timeline-row__link"
          >
            {row.title}
          </Link>
        ) : (
          <p className="timeline-row__title">{row.title}</p>
        )}
        <p className="timeline-row__meta">
          {row.processing.tone === "alert" && row.processing.detail ? row.processing.detail : row.meta}
        </p>
      </div>
      <span className="timeline-row__status">
        <span className="sr-only">Processing: </span>
        <StatusWord status={row.processing} />
      </span>
      <span className="timeline-row__status">
        <span className="sr-only">Drive backup: </span>
        <StatusWord status={row.backup} cloud />
      </span>
    </li>
  );
}

function digestState(digest: Digest | null): string {
  if (!digest) return "Not started";
  switch (digest.state) {
    case "updating":
      return `Updating · ${digest.recordings_done} of ${digest.recordings_total}`;
    case "finished":
      return "Finished";
    case "quiet":
      return "Quiet day";
    case "failed":
      return "Couldn’t finish";
  }
}

function DigestCard({ day, digest }: { day: string; digest: Digest | null }) {
  const headline =
    digest?.overview ??
    (digest?.state === "quiet"
      ? "No conversations were found on this day."
      : digest?.state === "failed"
        ? "The overview didn’t come together. Your recordings and notes are fine."
        : "The digest builds as each recording is written up.");
  return (
    <section className="digest-card" aria-labelledby="digest-card-title">
      <div className="digest-card__head">
        <h2 id="digest-card-title">
          <Icon name="sun" size={18} className="digest-card__icon" />
          Daily digest
        </h2>
        <span className="digest-card__state">{digestState(digest)}</span>
      </div>
      <p className="digest-card__headline">{headline}</p>
      <Link to="/day/$day/digest" params={{ day }} className="digest-card__link">
        Open digest <Icon name="arrowRight" size={16} />
      </Link>
    </section>
  );
}

