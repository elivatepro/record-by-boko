// W4 Search with preview: results grouped by conversation, labelled Transcript / Summary / My note,
// matches highlighted, ↑ ↓ to move, Enter to open, and a preview of the transcript around a hit.
import { getRouteApi, useNavigate } from "@tanstack/react-router";
import { type ReactNode, useEffect, useRef, useState } from "react";

import { useConversation, useSearch, useSync } from "../api/queries";
import type { SearchHit, SearchResult } from "../api/types";
import { EmptyState, ErrorState, Highlighted, Loading, Snippet } from "../components/common";
import { Icon } from "../components/Icon";
import { FOCUS_SEARCH_EVENT } from "../components/Shell";
import { isTyping, useKeydown, useNow, useOnline } from "../lib/hooks";
import { isWritingUp } from "../lib/status";
import { highlightParts, speakerName } from "../lib/text";
import { addDays, clock, dayLabel, duration, localToday } from "../lib/time";
import { around } from "../lib/transcript";
import type { SearchSearch } from "../router";

const route = getRouteApi("/app/search");
const VISIBLE_HITS = 3;
const WHERE: Record<SearchHit["where"], string> = {
  transcript: "Transcript",
  summary: "Summary",
  note: "My note",
};

type Flat = { result: SearchResult; hit: SearchHit; key: string };

export function Search() {
  const params = route.useSearch();
  const navigate = useNavigate();
  const q = params.q ?? "";
  const when = params.when ?? "any";
  const where = params.in ?? "all";
  const [text, setText] = useState(q);
  const [selected, setSelected] = useState(0);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const input = useRef<HTMLInputElement>(null);
  const online = useOnline();
  const today = localToday(useNow());
  const results = useSearch(q);
  const sync = useSync();

  const setParams = (next: Partial<SearchSearch>) =>
    navigate({ to: "/search", search: (prev) => ({ ...prev, ...next }), replace: true });

  // Typing updates the URL (and so the query) after a short pause.
  useEffect(() => {
    if (text.trim() === q) return;
    const timer = setTimeout(() => {
      setSelected(0);
      void navigate({ to: "/search", search: (prev) => ({ ...prev, q: text.trim() || undefined }), replace: true });
    }, 300);
    return () => clearTimeout(timer);
  }, [text, q, navigate]);

  useEffect(() => {
    const focus = () => input.current?.focus();
    window.addEventListener(FOCUS_SEARCH_EVENT, focus);
    return () => window.removeEventListener(FOCUS_SEARCH_EVENT, focus);
  }, []);

  const filtered: SearchResult[] = (results.data ?? [])
    .filter((r) => when === "any" || (when === "today" ? r.conversation.day === today : r.conversation.day >= addDays(today, -6)))
    .map((r) => ({ ...r, hits: r.hits.filter((h) => where === "all" || h.where === where) }))
    .filter((r) => r.hits.length > 0);

  const flat: Flat[] = filtered.flatMap((result) => {
    const id = result.conversation.id;
    const hits = expanded.has(id) ? result.hits : result.hits.slice(0, VISIBLE_HITS);
    return hits.map((hit, i) => ({ result, hit, key: `${id}:${i}` }));
  });
  const matchCount = filtered.reduce((n, r) => n + r.hits.length, 0);
  const current = flat[Math.min(selected, flat.length - 1)] ?? null;
  const writingUp = (sync.data?.recordings ?? []).filter(isWritingUp).length;

  function open(item: Flat, play: boolean) {
    void navigate({
      to: "/c/$conversationId",
      params: { conversationId: item.result.conversation.id },
      search: item.hit.where === "note" ? { tab: "mine" } : { t: Math.floor(item.hit.at_ms / 1000), play: play || undefined },
    });
  }

  useKeydown((event) => {
    if (flat.length === 0) return;
    const inSearchBox = event.target === input.current;
    if (!inSearchBox && isTyping(event)) return;
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      const next = Math.min(flat.length - 1, Math.max(0, selected + (event.key === "ArrowDown" ? 1 : -1)));
      setSelected(next);
      document.getElementById(`hit-${flat[next]!.key}`)?.scrollIntoView({ block: "nearest" });
    } else if (event.key === "Enter" && current) {
      event.preventDefault();
      open(current, false);
    }
  });

  return (
    <div className="page search">
      <form
        className="search-box"
        role="search"
        onSubmit={(event) => {
          event.preventDefault();
          void setParams({ q: text.trim() || undefined });
        }}
      >
        <Icon name="search" size={24} />
        <label htmlFor="search-input" className="sr-only">
          Search transcripts, summaries and notes
        </label>
        <input
          id="search-input"
          ref={input}
          value={text}
          autoFocus
          autoComplete="off"
          placeholder="Search transcripts, summaries and notes"
          onChange={(event) => setText(event.currentTarget.value)}
          aria-controls="search-results"
        />
        {q && results.data && (
          <span className="search-box__count" aria-live="polite">
            {matchCount} {matchCount === 1 ? "match" : "matches"} in {filtered.length}{" "}
            {filtered.length === 1 ? "recording" : "recordings"}
          </span>
        )}
        {text && (
          <button
            type="button"
            className="icon-button"
            aria-label="Clear search"
            onClick={() => {
              setText("");
              void setParams({ q: undefined });
              input.current?.focus();
            }}
          >
            <Icon name="close" />
          </button>
        )}
      </form>

      <div className="filters">
        <span className="filters__label" id="when-label">
          When
        </span>
        <div className="pill-group" role="group" aria-labelledby="when-label">
          {(
            [
              ["any", "Any time"],
              ["week", "Last 7 days"],
              ["today", "Today"],
            ] as const
          ).map(([value, label]) => (
            <button key={value} type="button" className="pill" aria-pressed={when === value} onClick={() => void setParams({ when: value === "any" ? undefined : value })}>
              {label}
            </button>
          ))}
        </div>
        <span className="filters__divider" aria-hidden="true" />
        <span className="filters__label" id="in-label">
          In
        </span>
        <div className="pill-group" role="group" aria-labelledby="in-label">
          {(
            [
              ["all", "Everything"],
              ["transcript", "Transcripts"],
              ["summary", "Summaries"],
              ["note", "My notes"],
            ] as const
          ).map(([value, label]) => (
            <button key={value} type="button" className="pill" aria-pressed={where === value} onClick={() => void setParams({ in: value === "all" ? undefined : value })}>
              {label}
            </button>
          ))}
        </div>
      </div>

      {!q ? (
        <EmptyState icon="search" title="Search your whole record">
          <p>Find words in transcripts, summaries and your own notes. Press / from anywhere to come here.</p>
        </EmptyState>
      ) : !online && !results.data ? (
        <div className="callout callout--plain" role="status">
          <Icon name="offline" />
          <div>
            <strong>Offline</strong>
            <p>Search on the web needs the internet. Your phone can still search what it has saved.</p>
          </div>
        </div>
      ) : results.isPending ? (
        <Loading label="Searching…" />
      ) : results.isError ? (
        <ErrorState title="Search didn’t work" error={results.error} onRetry={() => void results.refetch()} />
      ) : filtered.length === 0 ? (
        <NoResults q={q} filtered={(results.data?.length ?? 0) > 0} writingUp={writingUp} onSearch={(word) => setText(word)} />
      ) : (
        <div className="search__layout">
          <div id="search-results" className="results" role="listbox" aria-label="Results" aria-activedescendant={current ? `hit-${current.key}` : undefined}>
            {filtered.map((result) => {
              const c = result.conversation;
              const hidden = result.hits.length - VISIBLE_HITS;
              const isOpen = expanded.has(c.id);
              return (
                <section key={c.id} className="result-group" aria-label={c.title}>
                  <header className="result-group__head">
                    <h2>{c.title}</h2>
                    <span className="result-group__when">
                      {dayLabel(c.day, today)} · {duration(c.end_ms - c.start_ms)}
                    </span>
                    <span className="result-group__count">
                      {result.hits.length} {result.hits.length === 1 ? "match" : "matches"}
                    </span>
                  </header>
                  {(isOpen ? result.hits : result.hits.slice(0, VISIBLE_HITS)).map((hit, i) => {
                    const key = `${c.id}:${i}`;
                    const index = flat.findIndex((f) => f.key === key);
                    const isSelected = current?.key === key;
                    return (
                      <div
                        key={key}
                        id={`hit-${key}`}
                        role="option"
                        aria-selected={isSelected}
                        className={`hit${isSelected ? " hit--selected" : ""}`}
                        onClick={() => setSelected(index)}
                        onDoubleClick={() => open({ result, hit, key }, false)}
                      >
                        <span className="hit__where">{WHERE[hit.where]}</span>
                        <p className="hit__snippet">
                          <Snippet text={hit.snippet} />
                        </p>
                        {hit.where === "note" ? (
                          <button type="button" className="time-chip" onClick={() => open({ result, hit, key }, false)}>
                            Open
                          </button>
                        ) : (
                          <button
                            type="button"
                            className={`time-chip${isSelected ? " time-chip--dark" : ""}`}
                            onClick={(event) => {
                              event.stopPropagation();
                              open({ result, hit, key }, true);
                            }}
                            aria-label={`Play ${c.title} from ${clock(hit.at_ms)}`}
                          >
                            <Icon name="play" size={12} />
                            <span className="tabular">{clock(hit.at_ms)}</span>
                          </button>
                        )}
                      </div>
                    );
                  })}
                  {hidden > 0 && (
                    <button
                      type="button"
                      className="link-button result-group__more"
                      onClick={() => {
                        const next = new Set(expanded);
                        if (isOpen) next.delete(c.id);
                        else next.add(c.id);
                        setExpanded(next);
                      }}
                    >
                      {isOpen ? "Show fewer" : `Show ${hidden} more ${hidden === 1 ? "match" : "matches"}`}
                    </button>
                  )}
                </section>
              );
            })}
          </div>
          {current && <Preview item={current} q={q} onOpen={open} />}
        </div>
      )}
    </div>
  );
}

function NoResults({
  q,
  filtered,
  writingUp,
  onSearch,
}: {
  q: string;
  filtered: boolean;
  writingUp: number;
  onSearch: (word: string) => void;
}) {
  const first = q.split(/\s+/)[0] ?? "";
  return (
    <EmptyState icon="search" title={`Nothing for “${q}”`}>
      <p>
        {filtered
          ? "There are matches, but not with these filters. Try Any time or Everything."
          : "Searched all your transcripts, notes and summaries. Try fewer or different words."}
      </p>
      {!filtered && first && first !== q && (
        <button type="button" className="button button--primary" onClick={() => onSearch(first)}>
          Search “{first}” instead
        </button>
      )}
      {writingUp > 0 && (
        <p className="state__note">
          {writingUp === 1 ? "One recording is" : `${writingUp} recordings are`} still being written up. Try
          again when {writingUp === 1 ? "it’s" : "they’re"} done.
        </p>
      )}
    </EmptyState>
  );
}

function Preview({ item, q, onOpen }: { item: Flat; q: string; onOpen: (item: Flat, play: boolean) => void }) {
  const detail = useConversation(item.result.conversation.id);
  const { hit, result } = item;
  const title = result.conversation.title.split(" — ")[0];
  let body: ReactNode;
  if (detail.isPending) body = <Loading />;
  else if (detail.isError) body = <p className="preview__note">{detail.error.message}</p>;
  else if (hit.where === "transcript") {
    const lines = around(detail.data.segments, hit.at_ms);
    body = lines.map((s) => {
      const main = s.id === hit.segment_id || (s.start_ms <= hit.at_ms && s.end_ms >= hit.at_ms);
      return (
        <div key={s.id} className={`preview__line${main ? " preview__line--main" : ""}`}>
          <p className="preview__who">
            {speakerName(s.speaker, s.speaker_name)} · {clock(s.start_ms)}
          </p>
          <p className="preview__text">{main ? <Highlighted parts={highlightParts(s.text, q)} /> : s.text}</p>
        </div>
      );
    });
  } else if (hit.where === "summary") {
    body = <p className="preview__text">{detail.data.conversation.summary}</p>;
  } else {
    body = <p className="preview__text preview__text--note">{detail.data.note}</p>;
  }
  return (
    <aside className="preview" aria-label="Preview">
      <p className="preview__eyebrow">
        {title} · {hit.where === "transcript" ? `transcript around ${clock(hit.at_ms)}` : WHERE[hit.where].toLowerCase()}
      </p>
      <div className="preview__body">{body}</div>
      <div className="preview__actions">
        {hit.where !== "note" && (
          <button type="button" className="button button--primary button--grow" onClick={() => onOpen(item, true)}>
            <Icon name="play" size={14} className="buoy-icon" />
            Play from {clock(hit.at_ms)}
          </button>
        )}
        <button type="button" className="button button--ghost" onClick={() => onOpen(item, false)}>
          Open
        </button>
      </div>
      <p className="preview__hint">↑ ↓ to move between matches · Enter to open</p>
    </aside>
  );
}
