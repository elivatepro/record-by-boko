// W7 Updates: a drawer beside the rail. Everything / Needs you, unread vs read, mark all read,
// and a way straight to the recording.
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";
import { useEffect, useRef, useState } from "react";

import { api } from "../api/client";
import { keys, must, reprocess, retryBackup, useUpdates } from "../api/queries";
import type { Update } from "../api/types";
import { useNow } from "../lib/hooks";
import { dayLabel, localDayOf, localTime, localToday, wallDay } from "../lib/time";
import { EmptyState, ErrorState, Loading } from "./common";
import { Icon, type IconName } from "./Icon";

function needsYou(u: Update): boolean {
  return u.action !== null || u.kind.includes("failed");
}

function iconFor(u: Update): { name: IconName; tone: "alert" | "signal" | "plain" } {
  if (u.kind.includes("backup") && u.kind.includes("failed")) return { name: "cloud", tone: "alert" };
  if (u.kind.includes("failed")) return { name: "alert", tone: "alert" };
  if (u.kind.includes("clean")) return { name: "trash", tone: "plain" };
  if (u.kind.includes("recorder") || u.kind.includes("plaud")) return { name: "phone", tone: "signal" };
  if (u.kind.includes("written") || u.kind.includes("processed") || u.kind.includes("backed")) {
    return { name: "checkCircle", tone: "plain" };
  }
  return { name: "bell", tone: "plain" };
}

export function UpdatesDrawer({ onClose }: { onClose: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const updates = useUpdates();
  const queryClient = useQueryClient();
  const [filter, setFilter] = useState<"all" | "needs">("all");
  const today = localToday(useNow());

  useEffect(() => {
    const el = dialog.current;
    if (el && !el.open) el.showModal();
    return () => el?.close();
  }, []);

  const markRead = useMutation({
    mutationFn: () => must(api.POST("/updates/read"), "Couldn’t mark them read."),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: keys.updates }),
        queryClient.invalidateQueries({ queryKey: keys.sync }),
      ]);
    },
  });

  const all = updates.data ?? [];
  const unread = all.filter((u) => !u.read).length;
  const needing = all.filter(needsYou);
  const shown = filter === "all" ? all : needing;

  return (
    <dialog
      ref={dialog}
      className="drawer"
      aria-labelledby="updates-title"
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
      onClick={(event) => {
        // A click on the scrim (the dialog box itself, outside its panel) closes it.
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div className="drawer__panel">
        <header className="drawer__header">
          <div>
            <p className="eyebrow">{unread > 0 ? `${unread} unread` : "All read"}</p>
            <h2 id="updates-title" className="drawer__title">
              Updates
            </h2>
          </div>
          <div className="drawer__actions">
            {unread > 0 && (
              <button
                type="button"
                className="button button--soft"
                onClick={() => markRead.mutate()}
                disabled={markRead.isPending}
              >
                Mark all read
              </button>
            )}
            <button type="button" className="icon-button" aria-label="Close updates" onClick={onClose}>
              <Icon name="close" />
            </button>
          </div>
        </header>

        <div className="segmented segmented--wide" role="group" aria-label="Show">
          <button type="button" aria-pressed={filter === "all"} onClick={() => setFilter("all")}>
            Everything
          </button>
          <button type="button" aria-pressed={filter === "needs"} onClick={() => setFilter("needs")}>
            Needs you · {needing.length}
          </button>
        </div>

        {markRead.isError && (
          <p className="inline-error" role="alert">
            {markRead.error.message}
          </p>
        )}

        {updates.isPending ? (
          <Loading />
        ) : updates.isError ? (
          <ErrorState error={updates.error} onRetry={() => void updates.refetch()} />
        ) : shown.length === 0 ? (
          <EmptyState icon="checkCircle" title={filter === "needs" ? "Nothing needs you" : "No updates yet"}>
            <p>
              {filter === "needs"
                ? "Backups and writing up are going fine. Anything that needs a hand shows up here."
                : "Writing up, backups and clean-ups report here as they happen."}
            </p>
          </EmptyState>
        ) : (
          <ul className="updates">
            {shown.map((u) => (
              <UpdateRow key={u.id} update={u} today={today} onDone={onClose} />
            ))}
          </ul>
        )}
      </div>
    </dialog>
  );
}

function UpdateRow({ update, today, onDone }: { update: Update; today: string; onDone: () => void }) {
  const navigate = useNavigate();
  const icon = iconFor(update);
  const [opening, setOpening] = useState(false);
  const [retrying, setRetrying] = useState<"idle" | "busy" | "done">("idle");
  const [failed, setFailed] = useState<string | null>(null);
  const day = localDayOf(update.created_at);
  const when = day === today ? localTime(update.created_at) : dayLabel(day, today);

  async function open() {
    if (update.conversation_id) {
      onDone();
      await navigate({ to: "/c/$conversationId", params: { conversationId: update.conversation_id } });
      return;
    }
    if (!update.recording_id) return;
    setOpening(true);
    setFailed(null);
    try {
      const found = await must(
        api.GET("/recordings/{recording_id}/conversations", {
          params: { path: { recording_id: update.recording_id } },
        }),
        "Couldn’t open that recording.",
      );
      onDone();
      const first = found.conversations[0];
      if (first) await navigate({ to: "/c/$conversationId", params: { conversationId: first.id } });
      else await navigate({ to: "/day/$day", params: { day: wallDay(found.recording.started_at, found.recording.zone_offset_seconds) } });
    } catch (error) {
      setFailed(error instanceof Error ? error.message : "Couldn’t open that recording.");
    } finally {
      setOpening(false);
    }
  }

  return (
    <li className={`update${update.read ? "" : " update--unread"}`}>
      <span className={`tile tile--${icon.tone}`} aria-hidden="true">
        <Icon name={icon.name} />
      </span>
      <div className="update__body">
        <div className="update__head">
          <h3>
            {!update.read && <span className="sr-only">Unread: </span>}
            {update.title}
          </h3>
          <time dateTime={update.created_at} className="tabular">
            {when}
          </time>
        </div>
        <p>{update.body}</p>
        {(update.action === "retry" || update.action === "retry_backup") && update.recording_id && (
          <div className="update__actions">
            <button
              type="button"
              className="button button--soft button--small"
              disabled={retrying !== "idle"}
              onClick={() => {
                setRetrying("busy");
                const call = update.action === "retry_backup" ? retryBackup : reprocess;
                call(update.recording_id!).then(
                  () => setRetrying("done"),
                  () => setRetrying("idle"),
                );
              }}
            >
              {retrying === "done" ? "Trying again…" : "Try again now"}
            </button>
          </div>
        )}
        {(update.conversation_id || update.recording_id) && (
          <div className="update__actions">
            <button type="button" className="button button--soft button--small" onClick={() => void open()} disabled={opening}>
              {opening ? "Opening…" : "Open recording"}
            </button>
          </div>
        )}
        {failed && (
          <p className="inline-error" role="alert">
            {failed}
          </p>
        )}
      </div>
    </li>
  );
}
