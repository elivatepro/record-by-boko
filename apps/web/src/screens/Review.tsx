// W5 Review: suggested tasks wait here until accepted, edited or dismissed. List + detail, with
// the designed keys: ↑ ↓ move, Enter opens the source, A accept, E edit, D dismiss (or done).
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { getRouteApi, useNavigate } from "@tanstack/react-router";
import { useState } from "react";

import { keys, reviewChange, useConversation, useReview, useSync } from "../api/queries";
import type { ReviewItem, ReviewState } from "../api/types";
import { EmptyState, ErrorState, Loading, TimeLink } from "../components/common";
import { Icon } from "../components/Icon";
import { isTyping, useKeydown } from "../lib/hooks";
import { isWritingUp } from "../lib/status";
import { speakerName } from "../lib/text";
import { clock } from "../lib/time";
import { byIdx, sourceSegments } from "../lib/transcript";

const route = getRouteApi("/app/review");
type Tab = "waiting" | "accepted" | "done";
const TAB_LABEL: Record<Tab, string> = { waiting: "Waiting", accepted: "Accepted", done: "Done" };

function shortTitle(title: string): string {
  return title.split(" — ")[0] ?? title;
}

export function Review() {
  const tab: Tab = route.useSearch().state ?? "waiting";
  const navigate = useNavigate();
  const review = useReview();
  const sync = useSync();
  const queryClient = useQueryClient();
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [undo, setUndo] = useState<{ item: ReviewItem; label: string } | null>(null);

  const all = review.data ?? [];
  const items = all.filter((i) => i.state === tab);
  const counts = {
    waiting: all.filter((i) => i.state === "waiting").length,
    accepted: all.filter((i) => i.state === "accepted").length,
    done: all.filter((i) => i.state === "done").length,
  };
  const selectedIndex = Math.max(0, items.findIndex((i) => i.id === selectedId));
  const selected = items[selectedIndex] ?? null;
  const writingUp = (sync.data?.recordings ?? []).filter(isWritingUp).length;

  const change = useMutation({
    mutationFn: ({ item, state, text }: { item: ReviewItem; state?: ReviewState; text?: string; label?: string }) =>
      reviewChange(item.id, { state, text }),
    onMutate: ({ item, state, text }) => {
      // Optimistic: the item moves tabs straight away; the next one is selected.
      const next = items[selectedIndex + 1] ?? items[selectedIndex - 1] ?? null;
      queryClient.setQueryData<ReviewItem[]>(keys.review, (old) =>
        old?.map((i) => (i.id === item.id ? { ...i, state: state ?? i.state, text: text || i.text } : i)),
      );
      if (state && state !== item.state) setSelectedId(next?.id ?? null);
      setEditing(null);
    },
    onSuccess: (updated, { item, label }) => {
      queryClient.setQueryData<ReviewItem[]>(keys.review, (old) => old?.map((i) => (i.id === updated.id ? updated : i)));
      if (label) setUndo({ item, label });
    },
    onError: () => void queryClient.invalidateQueries({ queryKey: keys.review }),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: keys.sync });
      void queryClient.invalidateQueries({ queryKey: ["conversation"] });
    },
  });

  const act = (state: ReviewState, label: string) => selected && change.mutate({ item: selected, state, label });
  const openSource = (item: ReviewItem) =>
    navigate({
      to: "/c/$conversationId",
      params: { conversationId: item.conversation.id },
      search: { t: Math.floor(item.at_ms / 1000), play: true },
    });

  useKeydown((event) => {
    if (isTyping(event) || event.metaKey || event.ctrlKey || event.altKey || editing !== null) return;
    const key = event.key.toLowerCase();
    if ((key === "arrowdown" || key === "arrowup") && items.length > 0) {
      event.preventDefault();
      const next = items[Math.min(items.length - 1, Math.max(0, selectedIndex + (key === "arrowdown" ? 1 : -1)))]!;
      setSelectedId(next.id);
      document.getElementById(`review-${next.id}`)?.focus();
      return;
    }
    if (!selected) return;
    if (key === "enter" && (event.target as HTMLElement).tagName !== "BUTTON") {
      event.preventDefault();
      void openSource(selected);
    } else if (key === "a" && tab === "waiting") act("accepted", "Accepted");
    else if (key === "e" && tab !== "done") {
      event.preventDefault();
      setEditing(selected.text);
    } else if (key === "d") {
      if (tab === "waiting") act("dismissed", "Dismissed");
      else if (tab === "accepted") act("done", "Marked done");
    } else if (key === "u" && tab !== "waiting") act(tab === "done" ? "accepted" : "waiting", "Moved back");
  });

  return (
    <div className="page review">
      <header className="page-header">
        <div>
          <h1 className="page-title page-title--large">Review</h1>
          <p className="page-sub">Suggestions wait here until you accept, edit or dismiss them.</p>
        </div>
        <div className="segmented segmented--counts" role="tablist" aria-label="Review lists">
          {(["waiting", "accepted", "done"] as const).map((t) => (
            <button
              key={t}
              type="button"
              role="tab"
              aria-selected={tab === t}
              onClick={() => {
                setSelectedId(null);
                setUndo(null);
                void navigate({ to: "/review", search: { state: t === "waiting" ? undefined : t }, replace: true });
              }}
            >
              {TAB_LABEL[t]} <span className="segmented__count">{counts[t]}</span>
            </button>
          ))}
        </div>
      </header>

      {undo && (
        <div className="toast" role="status">
          {undo.label}: “{undo.item.text}”
          <button
            type="button"
            className="link-button"
            onClick={() => {
              change.mutate({ item: undo.item, state: undo.item.state });
              setSelectedId(undo.item.id);
              setUndo(null);
            }}
          >
            Undo
          </button>
        </div>
      )}
      {change.isError && (
        <p className="inline-error" role="alert">
          {change.error.message}
        </p>
      )}

      {review.isPending ? (
        <Loading label="Loading Review…" />
      ) : review.isError ? (
        <ErrorState title="Review didn’t load" error={review.error} onRetry={() => void review.refetch()} />
      ) : items.length === 0 ? (
        <EmptyState icon="checkCircle" title={tab === "waiting" ? "All caught up" : tab === "accepted" ? "No open tasks" : "Nothing done yet"}>
          <p>
            {tab === "waiting"
              ? `Nothing is waiting. ${counts.accepted} accepted, ${counts.done} done.`
              : tab === "accepted"
                ? "Tasks you accept from suggestions show up here."
                : "Tasks you tick off show up here."}
          </p>
          {tab === "waiting" && counts.accepted > 0 && (
            <button type="button" className="button button--soft" onClick={() => void navigate({ to: "/review", search: { state: "accepted" } })}>
              See accepted tasks
            </button>
          )}
          {writingUp > 0 && <p className="state__note">More may arrive while recordings are still being written up.</p>}
        </EmptyState>
      ) : (
        <div className="review__layout">
          <div>
            <ul className="review-list" aria-label={`${TAB_LABEL[tab]} items`}>
              {items.map((item) => {
                const isSelected = item.id === selected?.id;
                return (
                  <li key={item.id}>
                    <button
                      type="button"
                      id={`review-${item.id}`}
                      className={`review-row${isSelected ? " review-row--selected" : ""}`}
                      aria-current={isSelected ? "true" : undefined}
                      onClick={() => {
                        setSelectedId(item.id);
                        setEditing(null);
                      }}
                    >
                      <span className={`tag${tab === "waiting" ? " tag--dashed" : ""}`}>
                        {tab === "waiting" ? "Suggested task" : tab === "accepted" ? "Your task" : "Done"}
                      </span>
                      <span className={`review-row__text${tab === "done" ? " review-row__text--done" : ""}`}>{item.text}</span>
                      <span className="review-row__source">
                        {shortTitle(item.conversation.title)} · <span className="tabular">{clock(item.at_ms)}</span>
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
            {writingUp > 0 && (
              <p className="review__note">
                More may arrive while {writingUp === 1 ? "a recording is" : `${writingUp} recordings are`} still being
                written up.
              </p>
            )}
          </div>
          {selected && (
            <ReviewDetail
              key={selected.id}
              item={selected}
              tab={tab}
              editing={editing}
              setEditing={setEditing}
              busy={change.isPending}
              onAct={act}
              onSave={(text) => change.mutate({ item: selected, text, state: tab === "waiting" ? "accepted" : undefined, label: tab === "waiting" ? "Accepted" : undefined })}
            />
          )}
        </div>
      )}
    </div>
  );
}

function ReviewDetail({
  item,
  tab,
  editing,
  setEditing,
  busy,
  onAct,
  onSave,
}: {
  item: ReviewItem;
  tab: Tab;
  editing: string | null;
  setEditing: (text: string | null) => void;
  busy: boolean;
  onAct: (state: ReviewState, label: string) => void;
  onSave: (text: string) => void;
}) {
  const detail = useConversation(item.conversation.id);
  const sources = detail.data ? sourceSegments(item.sources, byIdx(detail.data.segments)) : [];
  const speakers = [...new Set(sources.map((s) => speakerName(s.speaker, s.speaker_name)))];

  return (
    <section className="review-detail" aria-label="Selected item">
      <span className={`tag${tab === "waiting" ? " tag--dashed" : ""}`}>
        {tab === "waiting" ? "Suggested task · not accepted yet" : tab === "accepted" ? "Your task · accepted" : "Your task · done"}
      </span>
      {editing !== null ? (
        <form
          className="review-detail__edit"
          onSubmit={(event) => {
            event.preventDefault();
            if (editing.trim()) onSave(editing.trim());
          }}
        >
          <label htmlFor="review-edit" className="sr-only">
            Task wording
          </label>
          <textarea
            id="review-edit"
            value={editing}
            autoFocus
            rows={3}
            onChange={(event) => setEditing(event.currentTarget.value)}
            onKeyDown={(event) => {
              if (event.key === "Escape") setEditing(null);
            }}
          />
          {item.text !== item.suggested_text && (
            <p className="review-detail__suggested">
              Suggested: “{item.suggested_text}”{" "}
              <button type="button" className="link-button" onClick={() => setEditing(item.suggested_text)}>
                Use it
              </button>
            </p>
          )}
          <div className="review-detail__actions">
            <button type="submit" className="button button--primary" disabled={busy}>
              {tab === "waiting" ? "Save and accept" : "Save"}
            </button>
            <button type="button" className="button button--soft" onClick={() => setEditing(null)}>
              Cancel
            </button>
          </div>
        </form>
      ) : (
        <h2 className={`review-detail__title${tab === "done" ? " review-row__text--done" : ""}`}>{item.text}</h2>
      )}

      <div className="source-box">
        <div className="source-box__head">
          <span>
            From {shortTitle(item.conversation.title)}
            {speakers.length > 0 ? ` · ${speakers.join(", ")}` : ""}
          </span>
          <TimeLink conversationId={item.conversation.id} ms={item.at_ms} label={item.conversation.title} />
        </div>
        {detail.isPending ? (
          <p className="source-box__quote source-box__quote--muted">Loading the moment…</p>
        ) : sources.length > 0 ? (
          <p className="source-box__quote">“{sources.map((s) => s.text).join(" ")}”</p>
        ) : null}
      </div>

      {editing === null && (
        <div className="review-detail__actions">
          {tab === "waiting" && (
            <>
              <button type="button" className="button button--primary" onClick={() => onAct("accepted", "Accepted")} disabled={busy}>
                <Icon name="check" size={18} /> Accept <kbd>A</kbd>
              </button>
              <button type="button" className="button button--soft" onClick={() => setEditing(item.text)}>
                Edit <kbd>E</kbd>
              </button>
              <button type="button" className="button button--soft" onClick={() => onAct("dismissed", "Dismissed")} disabled={busy}>
                Dismiss <kbd>D</kbd>
              </button>
            </>
          )}
          {tab === "accepted" && (
            <>
              <button type="button" className="button button--primary" onClick={() => onAct("done", "Marked done")} disabled={busy}>
                <Icon name="check" size={18} /> Mark done <kbd>D</kbd>
              </button>
              <button type="button" className="button button--soft" onClick={() => setEditing(item.text)}>
                Edit <kbd>E</kbd>
              </button>
              <button type="button" className="button button--soft" onClick={() => onAct("waiting", "Moved back")} disabled={busy}>
                Back to waiting <kbd>U</kbd>
              </button>
            </>
          )}
          {tab === "done" && (
            <button type="button" className="button button--soft" onClick={() => onAct("accepted", "Moved back")} disabled={busy}>
              Not done yet <kbd>U</kbd>
            </button>
          )}
        </div>
      )}
      <p className="review-detail__foot">
        {tab === "waiting"
          ? "Accepting turns it into your task. Nothing is sent to anyone."
          : "Enter opens the moment it came from."}
      </p>
    </section>
  );
}
