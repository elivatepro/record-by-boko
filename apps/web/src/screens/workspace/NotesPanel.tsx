// The Notes tab: summary with its sources, decisions, follow-ups (suggested vs accepted) and key
// moments. Every statement has a time chip that seeks and plays.
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";

import { keys, reviewChange } from "../../api/queries";
import type { ConversationDetail, Item } from "../../api/types";
import { TimeChip } from "../../components/common";
import { Icon } from "../../components/Icon";
import { byIdx, sourceSegments } from "../../lib/transcript";
import { usePlayerContext } from "./player";

export function NotesPanel({ detail }: { detail: ConversationDetail }) {
  const player = usePlayerContext();
  const play = (ms: number) => player.seek(ms, true);
  const index = byIdx(detail.segments);
  const sources = sourceSegments(detail.summary_sources, index);
  const decisions = detail.items.filter((i) => i.kind === "decision");
  const tasks = detail.items.filter((i) => i.kind === "task" && i.review_state !== "dismissed");
  const moments = detail.items.filter((i) => i.kind === "key_moment");

  return (
    <div className="notes">
      <section aria-labelledby="summary-title">
        <h3 id="summary-title" className="panel-label">
          Summary
        </h3>
        <p className="notes__summary">{detail.conversation.summary || "No summary for this one."}</p>
        {sources.length > 0 && (
          <div className="chips" aria-label="Where the summary comes from">
            {sources.map((s) => (
              <TimeChip key={s.idx} ms={s.start_ms} onPlay={play} />
            ))}
          </div>
        )}
      </section>

      {decisions.length > 0 && (
        <section aria-labelledby="decided-title">
          <h3 id="decided-title" className="panel-label">
            Decided
          </h3>
          <ul className="item-list">
            {decisions.map((d) => (
              <li key={d.id} className="item-row">
                <span className="check-badge" aria-hidden="true">
                  <Icon name="check" size={14} />
                </span>
                <span className="item-row__text">{d.text}</span>
                <TimeChip ms={d.at_ms} onPlay={play} />
              </li>
            ))}
          </ul>
        </section>
      )}

      {tasks.length > 0 && (
        <section aria-labelledby="followups-title">
          <div className="panel-label-row">
            <h3 id="followups-title" className="panel-label">
              Follow-ups
            </h3>
            <Link to="/review" className="text-link">
              Open Review
            </Link>
          </div>
          <ul className="followups">
            {tasks.map((t) => (
              <FollowUp key={t.id} item={t} conversationId={detail.conversation.id} onPlay={play} />
            ))}
          </ul>
        </section>
      )}

      {moments.length > 0 && (
        <section aria-labelledby="moments-title">
          <h3 id="moments-title" className="panel-label">
            Key moments
          </h3>
          <ul className="item-list">
            {moments.map((m) => (
              <li key={m.id} className="item-row">
                <Icon name="diamond" size={16} className="item-row__diamond" />
                <span className="item-row__text">{m.text}</span>
                <TimeChip ms={m.at_ms} onPlay={play} />
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}

function FollowUp({
  item,
  conversationId,
  onPlay,
}: {
  item: Item;
  conversationId: string;
  onPlay: (ms: number) => void;
}) {
  const queryClient = useQueryClient();
  const change = useMutation({
    mutationFn: (state: "accepted" | "done" | "waiting") => {
      if (item.review_id === null || item.review_id === undefined) throw new Error("Not in Review.");
      return reviewChange(item.review_id, { state });
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: keys.conversation(conversationId) }),
        queryClient.invalidateQueries({ queryKey: keys.review }),
        queryClient.invalidateQueries({ queryKey: keys.sync }),
      ]);
    },
  });
  const suggested = item.review_state === "waiting" || !item.review_state;
  const done = item.review_state === "done";

  if (suggested) {
    return (
      <li className="followup followup--suggested">
        <div className="followup__body">
          <p className="followup__text">{item.text}</p>
          <p className="followup__meta">Suggested · waiting in Review</p>
          {change.isError && <p className="inline-error">{change.error.message}</p>}
        </div>
        <TimeChip ms={item.at_ms} onPlay={onPlay} />
        {item.review_id != null && (
          <button
            type="button"
            className="button button--primary button--small"
            onClick={() => change.mutate("accepted")}
            disabled={change.isPending}
          >
            Accept
          </button>
        )}
      </li>
    );
  }
  return (
    <li className="followup followup--accepted">
      <input
        type="checkbox"
        className="checkbox"
        id={`task-${item.id}`}
        checked={done}
        disabled={change.isPending}
        onChange={() => change.mutate(done ? "accepted" : "done")}
      />
      <label htmlFor={`task-${item.id}`} className="followup__body">
        <span className={`followup__text${done ? " followup__text--done" : ""}`}>{item.text}</span>
        <span className="followup__meta">Your task · {done ? "done" : "accepted"}</span>
      </label>
      <TimeChip ms={item.at_ms} onPlay={onPlay} />
      {change.isError && <p className="inline-error">{change.error.message}</p>}
    </li>
  );
}
