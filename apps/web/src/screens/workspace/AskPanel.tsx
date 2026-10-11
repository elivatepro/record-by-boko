// The Ask tab (W3b): answers only from this recording, each cited with tappable times, and a
// visibly different "Not enough in this recording" reply. Offline questions wait (S7).
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";

import { api } from "../../api/client";
import { ApiError, keys, must } from "../../api/queries";
import type { AskTurn, ConversationDetail } from "../../api/types";
import { TimeChip } from "../../components/common";
import { Icon, Spinner } from "../../components/Icon";
import { useOnline } from "../../lib/hooks";
import { speakerName } from "../../lib/text";
import { byIdx, sourceSegments } from "../../lib/transcript";
import { usePlayerContext } from "./player";

const SUGGESTIONS = ["What did we decide?", "What do I need to do?", "What were the open questions?"];

export function AskPanel({ detail, writtenUp }: { detail: ConversationDetail; writtenUp: boolean }) {
  const queryClient = useQueryClient();
  const online = useOnline();
  const id = detail.conversation.id;
  const [draft, setDraft] = useState("");
  const [waiting, setWaiting] = useState<string | null>(null);
  const log = useRef<HTMLDivElement>(null);

  const ask = useMutation({
    mutationFn: (question: string) =>
      must(
        api.POST("/conversations/{conversation_id}/ask", {
          params: { path: { conversation_id: id } },
          body: { question },
        }),
        "Couldn’t answer just now. Try again.",
      ),
    onSuccess: (turn) => {
      queryClient.setQueryData<ConversationDetail>(keys.conversation(id), (old) =>
        old ? { ...old, asks: [...old.asks, turn] } : old,
      );
      setWaiting(null);
    },
  });

  const clear = useMutation({
    mutationFn: () =>
      must(
        api.DELETE("/conversations/{conversation_id}/ask", { params: { path: { conversation_id: id } } }),
        "Couldn’t clear the questions.",
      ),
    onSuccess: () => {
      queryClient.setQueryData<ConversationDetail>(keys.conversation(id), (old) =>
        old ? { ...old, asks: [] } : old,
      );
      ask.reset();
      setWaiting(null);
    },
  });

  // A new question goes out as soon as there's a connection; one asked offline waits for it.
  useEffect(() => {
    if (online && waiting !== null && ask.isIdle) ask.mutate(waiting);
  }, [online, waiting, ask]);

  // Keep the newest answer in view.
  useEffect(() => {
    log.current?.scrollTo({ top: log.current.scrollHeight });
  }, [detail.asks.length, waiting, ask.isPending]);

  function submit(text: string) {
    const question = text.trim();
    if (!question || ask.isPending) return;
    setDraft("");
    ask.reset();
    setWaiting(question);
  }

  const offlineError = ask.error instanceof ApiError && ask.error.code === "offline";
  const index = byIdx(detail.segments);

  return (
    <div className="ask">
      <div className="ask__log" ref={log} aria-live="polite">
        <div className="scope-note">
          <Icon name="info" size={18} />
          <p>Answers come only from this recording, with the moments they’re from.</p>
        </div>

        {detail.asks.map((turn) => (
          <AskExchange key={turn.id} turn={turn} index={index} />
        ))}

        {waiting !== null && (
          <>
            <p className="bubble">{waiting}</p>
            {ask.isPending ? (
              <p className="ask__pending">
                <Spinner /> Looking through this recording…
              </p>
            ) : !online || offlineError ? (
              <div className="answer-card answer-card--offline">
                <p className="answer-card__title">
                  <Icon name="offline" size={18} /> Couldn’t answer yet
                </p>
                <p>You’re offline, and answers are worked out online. Your question is saved and goes out when you’re back.</p>
                <button
                  type="button"
                  className="button button--primary button--small"
                  disabled={!online}
                  onClick={() => {
                    ask.reset();
                    ask.mutate(waiting);
                  }}
                >
                  Ask when online
                </button>
              </div>
            ) : ask.isError ? (
              <div className="answer-card answer-card--error" role="alert">
                <p className="answer-card__title">
                  <Icon name="alert" size={18} /> No answer
                </p>
                <p>{ask.error.message}</p>
                <button
                  type="button"
                  className="button button--soft button--small"
                  onClick={() => {
                    ask.reset();
                    ask.mutate(waiting);
                  }}
                >
                  Try again
                </button>
              </div>
            ) : null}
          </>
        )}

        {detail.asks.length === 0 && waiting === null && writtenUp && (
          <div className="chips chips--suggestions" aria-label="Suggestions">
            {SUGGESTIONS.map((s) => (
              <button key={s} type="button" className="chip-button" onClick={() => submit(s)}>
                {s}
              </button>
            ))}
          </div>
        )}
      </div>

      <form
        className="composer glass"
        onSubmit={(event) => {
          event.preventDefault();
          submit(draft);
        }}
      >
        <label htmlFor="ask-input" className="sr-only">
          Ask about this recording
        </label>
        <input
          id="ask-input"
          value={draft}
          onChange={(event) => setDraft(event.currentTarget.value)}
          placeholder={writtenUp ? "Ask about this recording…" : "Ask works once it’s written up"}
          disabled={!writtenUp}
          autoComplete="off"
        />
        <button type="submit" className="composer__send" aria-label="Ask" disabled={!draft.trim() || ask.isPending || !writtenUp}>
          <Icon name="arrowUp" />
        </button>
      </form>
      {detail.asks.length > 0 && (
        <button type="button" className="link-button ask__clear" onClick={() => clear.mutate()} disabled={clear.isPending}>
          <Icon name="restart" size={14} /> Clear questions
        </button>
      )}
    </div>
  );
}

function AskExchange({ turn, index }: { turn: AskTurn; index: ReturnType<typeof byIdx> }) {
  const player = usePlayerContext();
  const sources = sourceSegments(turn.sources, index);
  const chips = (
    <div className="chips">
      {sources.map((s) => (
        <TimeChip
          key={s.idx}
          ms={s.start_ms}
          label={speakerName(s.speaker, s.speaker_name)}
          onPlay={(ms) => player.seek(ms, true)}
        />
      ))}
    </div>
  );
  return (
    <>
      <p className="bubble">{turn.question}</p>
      {turn.enough ? (
        <div className="answer">
          <p className="answer__from">
            From this recording · {sources.length} {sources.length === 1 ? "source" : "sources"}
          </p>
          <p className="answer__text">{turn.answer}</p>
          {chips}
        </div>
      ) : (
        <div className="answer-card answer-card--not-enough">
          <p className="answer-card__title answer-card__title--signal">Not enough in this recording</p>
          <p>{turn.answer}</p>
          {sources.length > 0 && chips}
        </div>
      )}
    </>
  );
}
