// My notes: the owner's own words on this conversation. Saves by itself after a pause.
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";

import { api } from "../../api/client";
import { keys, must } from "../../api/queries";
import type { ConversationDetail } from "../../api/types";
import { useOnline } from "../../lib/hooks";

export function MyNotes({ detail }: { detail: ConversationDetail }) {
  const id = detail.conversation.id;
  const queryClient = useQueryClient();
  const online = useOnline();
  const [draft, setDraft] = useState(detail.note ?? "");
  const [saved, setSaved] = useState(detail.note ?? "");

  const save = useMutation({
    mutationFn: (text: string) =>
      must(
        api.PUT("/conversations/{conversation_id}/note", {
          params: { path: { conversation_id: id } },
          body: { text },
        }),
        "Couldn’t save your note.",
      ),
    onSuccess: (_, text) => {
      setSaved(text);
      queryClient.setQueryData<ConversationDetail>(keys.conversation(id), (old) =>
        old ? { ...old, note: text.trim() ? text : null } : old,
      );
    },
  });

  useEffect(() => {
    if (draft === saved || !online) return;
    const timer = setTimeout(() => save.mutate(draft), 800);
    return () => clearTimeout(timer);
    // Only a changed draft (or coming back online) schedules a save.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draft, online]);

  const status = save.isPending
    ? "Saving…"
    : save.isError
      ? save.error.message
      : !online && draft !== saved
        ? "Offline · your note saves when you’re back online"
        : save.isSuccess
          ? "Saved"
          : "Saves by itself";

  return (
    <div className="my-notes">
      <label htmlFor="my-note" className="panel-label">
        My notes
      </label>
      <textarea
        id="my-note"
        className="my-notes__field"
        value={draft}
        placeholder="Add a note. Only you see it, and Search finds it."
        onChange={(event) => setDraft(event.currentTarget.value)}
        onBlur={() => {
          if (draft !== saved && online) save.mutate(draft);
        }}
      />
      <p className={`my-notes__status${save.isError ? " inline-error" : ""}`} role="status">
        Only you see this · {status}
      </p>
    </div>
  );
}
