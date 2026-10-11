// The transcript column: virtualised (long recordings have thousands of lines), follows playback,
// highlights find matches, and edits lines and speaker names in edit mode.
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useVirtualizer } from "@tanstack/react-virtual";
import { forwardRef, type ReactNode, useEffect, useImperativeHandle, useRef, useState } from "react";

import { api } from "../../api/client";
import { keys, must } from "../../api/queries";
import type { ConversationDetail, Segment, Speaker } from "../../api/types";
import { Highlighted } from "../../components/common";
import { Icon } from "../../components/Icon";
import { highlightParts, speakerName, type TextPart } from "../../lib/text";
import { clock } from "../../lib/time";

export type TranscriptHandle = { scrollTo: (index: number) => void };

type Props = {
  conversationId: string;
  segments: Segment[];
  speakers: Speaker[];
  active: number;
  playing: boolean;
  follow: boolean;
  onUserScroll: () => void;
  onPlay: (ms: number) => void;
  find: string;
  currentMatch: number | null;
  editing: boolean;
  footer?: ReactNode;
};

export const Transcript = forwardRef<TranscriptHandle, Props>(function Transcript(
  {
    conversationId,
    segments,
    speakers,
    active,
    playing,
    follow,
    onUserScroll,
    onPlay,
    find,
    currentMatch,
    editing,
    footer,
  },
  ref,
) {
  const scroller = useRef<HTMLDivElement>(null);
  // The app doesn't use the React Compiler, so the virtualiser's unmemoisable API is fine here.
  // eslint-disable-next-line react-hooks/incompatible-library
  const virtualizer = useVirtualizer({
    count: segments.length,
    getScrollElement: () => scroller.current,
    estimateSize: () => 120,
    overscan: 6,
    getItemKey: (index) => segments[index]?.id ?? index,
  });

  useImperativeHandle(
    ref,
    () => ({ scrollTo: (index: number) => virtualizer.scrollToIndex(index, { align: "center" }) }),
    [virtualizer],
  );

  // Follow along: keep the playing line in view while Follow is on.
  useEffect(() => {
    if (follow && active >= 0) virtualizer.scrollToIndex(active, { align: "center" });
  }, [active, follow, virtualizer]);

  const speakerOrder = new Map(speakers.map((s, i) => [s.label, i]));
  const byLabel = new Map(speakers.map((s) => [s.label, s]));

  return (
    <div
      ref={scroller}
      className="transcript"
      tabIndex={0}
      aria-label="Transcript"
      onWheel={onUserScroll}
      onTouchMove={onUserScroll}
      onKeyDown={(event) => {
        if (["PageUp", "PageDown", "ArrowUp", "ArrowDown", "Home", "End"].includes(event.key)) onUserScroll();
      }}
    >
      <div className="transcript__inner" style={{ height: virtualizer.getTotalSize() }}>
        {virtualizer.getVirtualItems().map((item) => {
          const segment = segments[item.index]!;
          return (
            <div
              key={item.key}
              data-index={item.index}
              ref={virtualizer.measureElement}
              className="transcript__item"
              style={{ transform: `translateY(${item.start}px)` }}
            >
              <SegmentRow
                conversationId={conversationId}
                segment={segment}
                speaker={byLabel.get(segment.speaker)}
                speakerIndex={speakerOrder.get(segment.speaker) ?? 0}
                isActive={item.index === active}
                playing={playing}
                onPlay={onPlay}
                find={find}
                isCurrentMatch={item.index === currentMatch}
                editing={editing}
              />
            </div>
          );
        })}
      </div>
      {footer}
    </div>
  );
});

function textParts(segment: Segment, find: string): TextPart[] {
  if (find.trim()) return highlightParts(segment.text, find);
  return [{ text: segment.text, match: false }];
}

function SegmentRow({
  conversationId,
  segment,
  speaker,
  speakerIndex,
  isActive,
  playing,
  onPlay,
  find,
  isCurrentMatch,
  editing,
}: {
  conversationId: string;
  segment: Segment;
  speaker: Speaker | undefined;
  speakerIndex: number;
  isActive: boolean;
  playing: boolean;
  onPlay: (ms: number) => void;
  find: string;
  isCurrentMatch: boolean;
  editing: boolean;
}) {
  const [showOriginal, setShowOriginal] = useState(false);
  const [draft, setDraft] = useState<string | null>(null);
  const [renaming, setRenaming] = useState<string | null>(null);
  const queryClient = useQueryClient();
  const name = speakerName(segment.speaker, segment.speaker_name);
  const tidied = !segment.edited && segment.corrections.length > 0;
  const changed = segment.original_text !== segment.text;

  const save = useMutation({
    mutationFn: (text: string) =>
      must(
        api.PATCH("/segments/{segment_id}", {
          params: { path: { segment_id: segment.id } },
          body: { text, edited_at: new Date().toISOString() },
        }),
        "Couldn’t save that line.",
      ),
    onSuccess: (updated) => {
      queryClient.setQueryData<ConversationDetail>(keys.conversation(conversationId), (old) =>
        old ? { ...old, segments: old.segments.map((s) => (s.id === updated.id ? updated : s)) } : old,
      );
      void queryClient.invalidateQueries({ queryKey: keys.words });
      setDraft(null);
    },
  });

  const rename = useMutation({
    mutationFn: (newName: string) => {
      if (!speaker) throw new Error("This speaker can’t be renamed.");
      return must(
        api.PATCH("/speakers/{speaker_id}", {
          params: { path: { speaker_id: speaker.id } },
          body: { name: newName },
        }),
        "Couldn’t rename the speaker.",
      );
    },
    onSuccess: async () => {
      setRenaming(null);
      await queryClient.invalidateQueries({ queryKey: keys.conversation(conversationId) });
    },
  });

  const corrections = segment.corrections
    .map((c) => c.from)
    .filter((from): from is string => typeof from === "string");

  return (
    <article
      className={`segment${isActive ? " segment--playing" : ""}${isCurrentMatch ? " segment--match" : ""}`}
      aria-current={isActive && playing ? "true" : undefined}
    >
      <div className="segment__head">
        <span className={`speaker-dot speaker-dot--${speakerIndex % 3}`} aria-hidden="true" />
        {editing && speaker && renaming === null ? (
          <button type="button" className="segment__speaker segment__speaker--edit" onClick={() => setRenaming(name)}>
            {name}
            <Icon name="pencil" size={14} />
            <span className="sr-only">, rename this speaker</span>
          </button>
        ) : renaming !== null ? (
          <form
            className="rename"
            onSubmit={(event) => {
              event.preventDefault();
              if (renaming.trim()) rename.mutate(renaming.trim());
            }}
          >
            <label className="sr-only" htmlFor={`rename-${segment.id}`}>
              Name for {name}
            </label>
            <input
              id={`rename-${segment.id}`}
              value={renaming}
              autoFocus
              onChange={(event) => setRenaming(event.currentTarget.value)}
              onKeyDown={(event) => {
                if (event.key === "Escape") setRenaming(null);
              }}
            />
            <button type="submit" className="button button--primary button--small" disabled={rename.isPending}>
              Rename every line
            </button>
            <button type="button" className="button button--soft button--small" onClick={() => setRenaming(null)}>
              Cancel
            </button>
            {rename.isError && <span className="inline-error">{rename.error.message}</span>}
          </form>
        ) : (
          <span className="segment__speaker">{name}</span>
        )}
        <button
          type="button"
          className="segment__time tabular"
          onClick={() => onPlay(segment.start_ms)}
          aria-label={`Play from ${clock(segment.start_ms)}, ${name}`}
        >
          {clock(segment.start_ms)}
        </button>
        {isActive && playing && <span className="segment__playing">Playing</span>}
      </div>

      {draft !== null ? (
        <form
          className="segment__edit"
          onSubmit={(event) => {
            event.preventDefault();
            if (draft.trim()) save.mutate(draft.trim());
          }}
        >
          <label className="sr-only" htmlFor={`edit-${segment.id}`}>
            Edit line at {clock(segment.start_ms)}
          </label>
          <textarea
            id={`edit-${segment.id}`}
            value={draft}
            autoFocus
            rows={Math.max(2, Math.ceil(draft.length / 70))}
            onChange={(event) => setDraft(event.currentTarget.value)}
            onKeyDown={(event) => {
              if (event.key === "Escape") setDraft(null);
              if (event.key === "Enter" && (event.metaKey || event.ctrlKey)) {
                event.preventDefault();
                if (draft.trim()) save.mutate(draft.trim());
              }
            }}
          />
          <div className="segment__edit-actions">
            <button type="submit" className="button button--primary button--small" disabled={save.isPending}>
              {save.isPending ? "Saving…" : "Save"}
            </button>
            <button type="button" className="button button--soft button--small" onClick={() => setDraft(null)}>
              Cancel
            </button>
            <span className="segment__hint">The original is kept. Ctrl Enter saves.</span>
          </div>
          {save.isError && (
            <p className="inline-error" role="alert">
              {save.error.message}
            </p>
          )}
        </form>
      ) : editing ? (
        <button type="button" className="segment__text segment__text--editable" onClick={() => setDraft(segment.text)}>
          <Highlighted parts={textParts(segment, find)} />
          <span className="sr-only">, edit this line</span>
        </button>
      ) : (
        <p className="segment__text">
          {showOriginal ? segment.original_text : <Highlighted parts={textParts(segment, find)} />}
        </p>
      )}

      {draft === null && changed && (
        <p className="segment__note">
          <Icon name="sparkle" size={14} />
          {segment.edited
            ? "Edited by you · original kept."
            : tidied
              ? `Tidied: heard as ${corrections.map((c) => `“${c}”`).join(", ")}.`
              : "Changed from what was heard."}{" "}
          <button type="button" className="link-button" onClick={() => setShowOriginal(!showOriginal)}>
            {showOriginal ? "Show current" : "Show original"}
          </button>
        </p>
      )}
    </article>
  );
}
