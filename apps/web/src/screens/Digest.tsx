// W6 Daily digest: overview, key moments with their sources, counts, decisions and follow-ups,
// what isn't in it yet, and the quiet-day and failed states (09c, 09d).
import { Link } from "@tanstack/react-router";

import { useDay } from "../api/queries";
import type { DayView, DigestMoment } from "../api/types";
import { EmptyState, ErrorState, Loading, TimeLink } from "../components/common";
import { Icon } from "../components/Icon";
import { useNow } from "../lib/hooks";
import { processingStatus } from "../lib/status";
import { dayLabel, dayTitle, localToday, wallTime } from "../lib/time";

export function Digest({ day }: { day: string }) {
  const today = localToday(useNow());
  const query = useDay(day);
  const back = day === today ? "Today" : dayLabel(day, today);

  return (
    <div className="page digest">
      <Link to={day === today ? "/" : "/day/$day"} params={{ day }} className="breadcrumb">
        <Icon name="chevronLeft" size={16} />
        {back}
      </Link>
      {query.isPending ? (
        <Loading label="Loading the digest…" />
      ) : query.isError ? (
        <ErrorState title="The digest didn’t load" error={query.error} onRetry={() => void query.refetch()} />
      ) : (
        <DigestBody day={day} data={query.data} />
      )}
    </div>
  );
}

function DigestBody({ day, data }: { day: string; data: DayView }) {
  const digest = data.digest;
  const conversations = new Map(
    data.conversations.map((c) => {
      const r = data.recordings.find((x) => x.id === c.recording_id);
      return [c.id, { title: c.title.split(" — ")[0] ?? c.title, offset: r?.zone_offset_seconds ?? 0 }];
    }),
  );
  const notYet = data.recordings.filter((r) => r.processing_state !== "done");
  const heading = (
    <div className="digest__header">
      <h1 className="page-title page-title--large">
        {digest?.state === "failed" ? "Daily digest · couldn’t finish" : `Daily digest · ${dayTitle(day)}`}
      </h1>
      {digest && <StateChip state={digest.state} done={digest.recordings_done} total={digest.recordings_total} />}
    </div>
  );

  if (data.recordings.length === 0) {
    return (
      <>
        {heading}
        <EmptyState icon="calendar" title="Nothing was recorded on this day">
          <p>Digests are made from the day’s recordings once they’re written up.</p>
        </EmptyState>
      </>
    );
  }
  if (!digest) {
    return (
      <>
        {heading}
        <EmptyState icon="sun" title="Not started yet">
          <p>The digest starts once the first recording of the day is written up.</p>
        </EmptyState>
      </>
    );
  }
  if (digest.state === "failed") {
    return (
      <>
        {heading}
        <div className="notice notice--failed digest__failed">
          <span className="tile tile--alert tile--large" aria-hidden="true">
            <Icon name="alert" size={28} />
          </span>
          <h2>This day’s digest didn’t come together</h2>
          <p>
            The summary step failed. The day’s recordings, transcripts and notes are all fine; only this overview is
            missing. Roudio tries again by itself.
          </p>
          <Link to="/day/$day" params={{ day }} className="button button--soft">
            Open the day’s recordings
          </Link>
        </div>
      </>
    );
  }
  if (digest.state === "quiet") {
    return (
      <>
        {heading}
        <EmptyState icon="sun" title="A quiet day">
          <p>No conversations were found in this day’s recordings, so there’s nothing to sum up.</p>
          <Link to="/day/$day" params={{ day }} className="button button--soft">
            Open the day’s recordings
          </Link>
        </EmptyState>
      </>
    );
  }

  const moments = digest.moments.filter((m) => m.kind === "key_moment");
  const decisions = digest.moments.filter((m) => m.kind === "decision");
  const tasks = digest.moments.filter((m) => m.kind === "task");

  return (
    <>
      {heading}
      <div className="digest__grid">
        <div className="digest__main">
          <p className="digest__overview">{digest.overview ?? "The overview is still being written."}</p>
          <section aria-labelledby="moments-heading">
            <h2 id="moments-heading" className="section-title">
              Key moments
            </h2>
            {moments.length === 0 ? (
              <p className="muted">No key moments yet.</p>
            ) : (
              <ul className="moment-list">
                {moments.map((m) => (
                  <MomentRow key={`${m.conversation_id}-${m.item_id}`} moment={m} source={conversations.get(m.conversation_id)} />
                ))}
              </ul>
            )}
          </section>
          {notYet.length > 0 && (
            <section className="digest__not-yet" aria-labelledby="not-yet-heading">
              <h2 id="not-yet-heading" className="panel-label">
                Not in this digest yet
              </h2>
              <p>
                {notYet
                  .map((r) => `${wallTime(r.started_at, r.zone_offset_seconds)} (${processingStatus(r).text.toLowerCase()})`)
                  .join(" · ")}
              </p>
            </section>
          )}
        </div>
        <aside className="digest__side">
          <div className="count-tiles">
            <div className="count-tile">
              <span className="count-tile__n tabular">{digest.key_moments}</span>
              <span>key moments</span>
            </div>
            <div className="count-tile">
              <span className="count-tile__n tabular">{digest.decisions}</span>
              <span>decisions</span>
            </div>
            <div className="count-tile">
              <span className="count-tile__n tabular">{tasks.length}</span>
              <span>follow-ups</span>
            </div>
          </div>
          {decisions.length > 0 && (
            <section aria-labelledby="decisions-heading">
              <h2 id="decisions-heading" className="side-title">
                Decisions
              </h2>
              <ul className="plain-list">
                {decisions.map((m) => (
                  <li key={`${m.conversation_id}-${m.item_id}`}>
                    <Link to="/c/$conversationId" params={{ conversationId: m.conversation_id }} search={{ t: Math.floor(m.at_ms / 1000) }}>
                      {m.text}
                    </Link>
                  </li>
                ))}
              </ul>
            </section>
          )}
          {tasks.length > 0 && (
            <section aria-labelledby="followups-heading">
              <div className="panel-label-row">
                <h2 id="followups-heading" className="side-title">
                  Follow-ups
                </h2>
                <Link to="/review" className="text-link">
                  Open Review
                </Link>
              </div>
              <ul className="followup-pills">
                {tasks.map((m) => (
                  <li key={`${m.conversation_id}-${m.item_id}`} className="followup-pill">
                    {m.text}
                  </li>
                ))}
              </ul>
            </section>
          )}
        </aside>
      </div>
    </>
  );
}

function StateChip({ state, done, total }: { state: string; done: number; total: number }) {
  if (state === "updating") {
    return (
      <span className="state-chip state-chip--signal">
        <span className="dot dot--buoy" aria-hidden="true" />
        Updating · {done} of {total} recordings
      </span>
    );
  }
  if (state === "finished") {
    return (
      <span className="state-chip">
        <Icon name="checkCircle" size={16} /> Finished
      </span>
    );
  }
  return null;
}

function MomentRow({ moment, source }: { moment: DigestMoment; source?: { title: string; offset: number } }) {
  return (
    <li className="moment-row">
      <Icon name="diamond" size={18} className="moment-row__diamond" />
      <span className="moment-row__time tabular">{wallTime(moment.at, source?.offset ?? 0)}</span>
      <span className="moment-row__text">{moment.text}</span>
      <span className="moment-row__source">
        {source?.title ?? "Recording"} ·
        <TimeLink conversationId={moment.conversation_id} ms={moment.at_ms} label={source?.title} />
      </span>
    </li>
  );
}
