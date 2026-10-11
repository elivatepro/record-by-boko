// W3 / W3b Recording workspace: the transcript and the Notes / Ask / My notes panel side by side,
// with a glass player docked over the transcript. Deep link: /c/<id>?t=<seconds>&play=true.
import { getRouteApi, Link, useNavigate } from "@tanstack/react-router";
import { useRef, useState } from "react";

import { ApiError, reprocess, useConversation } from "../../api/queries";
import type { ConversationDetail } from "../../api/types";
import { ErrorState, Loading, StatusWord } from "../../components/common";
import { Icon, Spinner } from "../../components/Icon";
import { isTyping, useKeydown, useNow, useOnline } from "../../lib/hooks";
import { backupStatus, isWritingUp, processingStatus } from "../../lib/status";
import { speakerName } from "../../lib/text";
import { clock, dayLabel, dayTitle, duration, languageName, localToday, wallDay, whenLabel } from "../../lib/time";
import { activeIndex, findMatches } from "../../lib/transcript";
import type { PanelTab } from "../../router";
import { AskPanel } from "./AskPanel";
import { MyNotes } from "./MyNotes";
import { NotesPanel } from "./NotesPanel";
import { PlayerDock, PlayerProvider, usePlayer } from "./player";
import { Transcript, type TranscriptHandle } from "./Transcript";

const route = getRouteApi("/app/c/$conversationId");

export function Workspace() {
  const { conversationId } = route.useParams();
  const search = route.useSearch();
  const query = useConversation(conversationId);
  const online = useOnline();

  if (query.isPending) return <div className="page"><Loading label="Opening the recording…" /></div>;
  if (query.isError) {
    return (
      <div className="page">
        <ErrorState
          title={query.error instanceof ApiError && query.error.status === 404 ? "That recording isn’t here" : online ? "The recording didn’t open" : "No internet"}
          error={query.error}
          onRetry={() => void query.refetch()}
        />
      </div>
    );
  }
  return (
    <WorkspaceBody
      // A new ?t= (a search hit in the same conversation) starts the player there afresh.
      key={`${conversationId}:${search.t ?? ""}`}
      detail={query.data}
      startAt={search.t === undefined ? null : search.t * 1000}
      autoplay={search.play === true}
      tab={search.tab ?? "notes"}
    />
  );
}

function WorkspaceBody({
  detail,
  startAt,
  autoplay,
  tab,
}: {
  detail: ConversationDetail;
  startAt: number | null;
  autoplay: boolean;
  tab: PanelTab;
}) {
  const navigate = useNavigate();
  const { conversation: c, recording: r, segments } = detail;
  const { player, element } = usePlayer(r.id, c.start_ms, c.end_ms, startAt, autoplay);
  const transcript = useRef<TranscriptHandle>(null);
  const findInput = useRef<HTMLInputElement>(null);
  const [findOpen, setFindOpen] = useState(false);
  const [find, setFind] = useState("");
  const [cursor, setCursor] = useState(0);
  const [editing, setEditing] = useState(false);
  const [narrowView, setNarrowView] = useState<"transcript" | "panel">("transcript");
  const [menuOpen, setMenuOpen] = useState(false);
  const [copied, setCopied] = useState(false);
  const today = localToday(useNow());

  const day = wallDay(c.started_at, r.zone_offset_seconds);
  const active = activeIndex(segments, player.ms);
  const matches = findMatches(segments, find);
  const currentMatch = matches.length > 0 ? matches[cursor % matches.length]! : null;
  const writtenUp = segments.length > 0;
  const processing = processingStatus(r);
  const backup = backupStatus(r);
  const names = detail.speakers.map((s) => speakerName(s.label, s.name));
  const language = languageName(detail.language);
  const meta = [
    whenLabel(c.started_at, r.zone_offset_seconds, today),
    duration(c.end_ms - c.start_ms),
    names.length > 0 ? names.join(", ") : null,
    language,
  ]
    .filter(Boolean)
    .join(" · ");

  function setTab(next: PanelTab) {
    setNarrowView("panel");
    void navigate({ to: ".", search: (prev) => ({ ...prev, tab: next === "notes" ? undefined : next }), replace: true });
  }

  function jumpToMatch(next: number, list = matches) {
    if (list.length === 0) return;
    const wrapped = ((next % list.length) + list.length) % list.length;
    setCursor(wrapped);
    player.setFollow(false);
    transcript.current?.scrollTo(list[wrapped]!);
  }

  function openFind() {
    setFindOpen(true);
    setNarrowView("transcript");
    requestAnimationFrame(() => findInput.current?.select());
  }

  useKeydown((event) => {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "f") {
      event.preventDefault();
      openFind();
      return;
    }
    if (isTyping(event)) return;
    const target = event.target as HTMLElement;
    if (event.key === " " && target.tagName !== "BUTTON" && target.tagName !== "A") {
      event.preventDefault();
      player.toggle();
    }
  });

  return (
    <PlayerProvider value={player}>
      <div className="workspace" data-view={narrowView}>
        {element}
        <header className="ws-header">
          <div className="ws-header__top">
            <Link to={day === today ? "/" : "/day/$day"} params={{ day }} className="breadcrumb">
              <Icon name="chevronLeft" size={16} />
              {dayLabel(day, today)} · {dayTitle(day)}
            </Link>
            <div className="ws-header__actions">
              {findOpen ? (
                <div className="find" role="search">
                  <Icon name="search" size={18} />
                  <label htmlFor="find-input" className="sr-only">
                    Find in transcript
                  </label>
                  <input
                    id="find-input"
                    ref={findInput}
                    value={find}
                    placeholder="Find in transcript"
                    autoFocus
                    onChange={(event) => {
                      const value = event.currentTarget.value;
                      setFind(value);
                      setCursor(0);
                      const list = findMatches(segments, value);
                      if (list.length > 0) jumpToMatch(0, list);
                    }}
                    onKeyDown={(event) => {
                      if (event.key === "Enter") {
                        event.preventDefault();
                        jumpToMatch(cursor + (event.shiftKey ? -1 : 1));
                      }
                      if (event.key === "Escape") {
                        setFindOpen(false);
                        setFind("");
                      }
                    }}
                  />
                  <span className="find__count tabular" aria-live="polite">
                    {find.trim() ? (matches.length > 0 ? `${(cursor % matches.length) + 1} of ${matches.length}` : "None") : ""}
                  </span>
                  <button type="button" className="icon-button icon-button--small" aria-label="Previous match" onClick={() => jumpToMatch(cursor - 1)} disabled={matches.length === 0}>
                    <Icon name="chevronLeft" size={16} />
                  </button>
                  <button type="button" className="icon-button icon-button--small" aria-label="Next match" onClick={() => jumpToMatch(cursor + 1)} disabled={matches.length === 0}>
                    <Icon name="chevronRight" size={16} />
                  </button>
                  <button
                    type="button"
                    className="icon-button icon-button--small"
                    aria-label="Close find"
                    onClick={() => {
                      setFindOpen(false);
                      setFind("");
                    }}
                  >
                    <Icon name="close" size={16} />
                  </button>
                </div>
              ) : (
                <button type="button" className="button button--soft" onClick={openFind} disabled={!writtenUp}>
                  <Icon name="search" size={18} />
                  Find
                  <kbd>Ctrl F</kbd>
                </button>
              )}
              <button
                type="button"
                className={`button ${editing ? "button--primary" : "button--soft"}`}
                aria-pressed={editing}
                onClick={() => {
                  setEditing(!editing);
                  setNarrowView("transcript");
                }}
                disabled={!writtenUp}
              >
                <Icon name={editing ? "check" : "pencil"} size={18} />
                {editing ? "Done" : "Edit"}
              </button>
              <div className="menu-wrap">
                <button
                  type="button"
                  className="icon-button"
                  aria-label="More"
                  aria-expanded={menuOpen}
                  aria-haspopup="menu"
                  onClick={() => setMenuOpen(!menuOpen)}
                >
                  <Icon name="more" />
                </button>
                {menuOpen && (
                  <div
                    className="menu"
                    role="menu"
                    onKeyDown={(event) => {
                      if (event.key === "Escape") setMenuOpen(false);
                    }}
                  >
                    <button
                      type="button"
                      role="menuitem"
                      autoFocus
                      onClick={() => {
                        const url = new URL(window.location.href);
                        url.search = `?t=${Math.floor(player.ms / 1000)}`;
                        void navigator.clipboard?.writeText(url.toString()).then(() => setCopied(true));
                        setMenuOpen(false);
                      }}
                    >
                      Copy link to {clock(player.ms)}
                    </button>
                    <Link role="menuitem" to="/day/$day/digest" params={{ day }} onClick={() => setMenuOpen(false)}>
                      Open the day’s digest
                    </Link>
                  </div>
                )}
              </div>
            </div>
          </div>
          <div className="ws-header__title">
            <div>
              <h1 className="ws-title">{c.title}</h1>
              <p className="ws-meta">{meta}</p>
              {copied && (
                <p className="ws-meta" role="status">
                  Link copied.
                </p>
              )}
            </div>
            <div className="ws-chips">
              <span className={`status-chip status-chip--${processing.tone}`} title={processing.detail}>
                <StatusWord status={processing} />
              </span>
              <span className={`status-chip status-chip--${backup.tone}`} title={backup.detail}>
                <StatusWord status={backup} cloud />
              </span>
            </div>
          </div>
          <div className="segmented ws-switch" role="group" aria-label="Show">
            <button type="button" aria-pressed={narrowView === "transcript"} onClick={() => setNarrowView("transcript")}>
              Transcript
            </button>
            <button type="button" aria-pressed={narrowView === "panel" && tab === "notes"} onClick={() => setTab("notes")}>
              Notes
            </button>
            <button type="button" aria-pressed={narrowView === "panel" && tab === "ask"} onClick={() => setTab("ask")}>
              Ask
            </button>
            <button type="button" aria-pressed={narrowView === "panel" && tab === "mine"} onClick={() => setTab("mine")}>
              My notes
            </button>
          </div>
        </header>

        <div className="ws-body">
          <section className="ws-transcript" aria-label="Transcript and player">
            <Moments detail={detail} onPlay={(ms) => player.seek(ms, true)} />
            {writtenUp ? (
              <Transcript
                ref={transcript}
                conversationId={c.id}
                segments={segments}
                speakers={detail.speakers}
                active={active}
                playing={player.playing}
                follow={player.follow}
                onUserScroll={() => {
                  if (player.follow) player.setFollow(false);
                }}
                onPlay={(ms) => player.seek(ms, true)}
                find={find}
                currentMatch={currentMatch}
                editing={editing}
                footer={isWritingUp(r) ? <StillWritingUp from={segments[segments.length - 1]?.end_ms ?? 0} /> : null}
              />
            ) : (
              <div className="transcript transcript--empty">
                <NotWrittenUp detail={detail} />
              </div>
            )}
            <PlayerDock player={player} />
          </section>

          <aside className="ws-panel" aria-label="Notes, Ask and My notes">
            <div className="segmented segmented--tabs ws-tabs" role="tablist" aria-label="Panel">
              {(["notes", "ask", "mine"] as const).map((t) => (
                <button
                  key={t}
                  type="button"
                  role="tab"
                  id={`tab-${t}`}
                  aria-selected={tab === t}
                  aria-controls={`panel-${t}`}
                  onClick={() => setTab(t)}
                >
                  {t === "notes" ? "Notes" : t === "ask" ? "Ask" : "My notes"}
                </button>
              ))}
            </div>
            <div className="ws-panel__body" role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`}>
              {tab === "notes" ? (
                writtenUp || detail.conversation.summary ? (
                  <NotesPanel detail={detail} />
                ) : (
                  <NotWrittenUp detail={detail} />
                )
              ) : tab === "ask" ? (
                <AskPanel detail={detail} writtenUp={writtenUp} />
              ) : (
                <MyNotes detail={detail} />
              )}
            </div>
          </aside>
        </div>
      </div>
    </PlayerProvider>
  );
}

/** Key moments as jump chips above the transcript (there are no chapters in the data). */
function Moments({ detail, onPlay }: { detail: ConversationDetail; onPlay: (ms: number) => void }) {
  const moments = detail.items.filter((i) => i.kind === "key_moment");
  if (moments.length === 0) return null;
  return (
    <nav className="moments" aria-label="Key moments">
      <span className="moments__label">Moments</span>
      <ul>
        {moments.map((m) => (
          <li key={m.id}>
            <button type="button" className="moment-chip" onClick={() => onPlay(m.at_ms)} title={m.text}>
              <span className="tabular moment-chip__time">{clock(m.at_ms)}</span>
              <span className="moment-chip__text">{m.text}</span>
            </button>
          </li>
        ))}
      </ul>
    </nav>
  );
}

/** S3: part of the transcript is ready; the rest is still being written up. */
function StillWritingUp({ from }: { from: number }) {
  return (
    <div className="still-writing" role="status">
      <p className="still-writing__label">
        <Spinner /> {clock(from)} onward · still writing up
      </p>
      <span className="skeleton" />
      <span className="skeleton skeleton--long" />
      <span className="skeleton skeleton--short" />
    </div>
  );
}

/** S3 / S8: nothing to read yet, or writing up failed. The audio still plays either way. */
function NotWrittenUp({ detail }: { detail: ConversationDetail }) {
  const r = detail.recording;
  const [retry, setRetry] = useState<"idle" | "busy" | "done" | string>("idle");
  if (r.processing_state === "failed") {
    return (
      <div className="notice notice--failed">
        <h2>No notes yet</h2>
        <p>{r.processing_error ?? "Writing up stopped partway."} Roudio tries again by itself.</p>
        <p>
          <button
            type="button"
            className="button button--soft button--small"
            disabled={retry === "busy" || retry === "done"}
            onClick={() => {
              setRetry("busy");
              reprocess(r.id).then(
                () => setRetry("done"),
                (error: Error) => setRetry(error.message),
              );
            }}
          >
            {retry === "done" ? "Writing it up again…" : "Try again now"}
          </button>
        </p>
        {retry !== "idle" && retry !== "busy" && retry !== "done" && (
          <p className="inline-error" role="alert">
            {retry}
          </p>
        )}
        <p className="safe-line">
          <Icon name="shield" size={18} />
          The audio is safe on the server{r.backup_state === "backed_up" ? " and Drive" : ""}. You can still play it.
        </p>
      </div>
    );
  }
  const status = processingStatus(r);
  return (
    <div className="notice">
      <p className="notice__title">
        <Spinner /> {status.text}
      </p>
      <p>The transcript and notes appear here as they’re done. You can listen in the meantime.</p>
    </div>
  );
}
