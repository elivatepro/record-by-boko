// TanStack Query hooks over the typed client. Every call goes through `must`, which turns the
// API's `detail` (or a network failure) into an ApiError with a plain message.

import { useQuery } from "@tanstack/react-query";

import { api, errorDetail } from "./client";
import type { ReviewState } from "./types";

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message);
  }
}

export const OFFLINE_MESSAGE = "Couldn’t reach Roudio. Check your connection.";

type Result<T> = { data?: T; error?: unknown; response: Response };

/** The data, or an ApiError. Network failures (fetch throws) become an offline ApiError. */
export async function must<T>(call: Promise<Result<T>>, fallback: string): Promise<T> {
  let result: Result<T>;
  try {
    result = await call;
  } catch {
    throw new ApiError(OFFLINE_MESSAGE, 0, "offline");
  }
  if (result.error !== undefined || !result.response.ok) {
    const detail = errorDetail(result.error);
    throw new ApiError(detail?.message ?? fallback, result.response.status, detail?.code);
  }
  return result.data as T;
}

export const keys = {
  me: ["me"] as const,
  sync: ["sync"] as const,
  allRecordings: ["recordings"] as const,
  day: (day: string) => ["day", day] as const,
  conversation: (id: string) => ["conversation", id] as const,
  recordingConversations: (id: string) => ["recording-conversations", id] as const,
  audio: (recordingId: string) => ["audio", recordingId] as const,
  review: ["review"] as const,
  words: ["words"] as const,
  updates: ["updates"] as const,
  search: (q: string) => ["search", q] as const,
};

export function useMe() {
  return useQuery({
    queryKey: keys.me,
    queryFn: () => must(api.GET("/auth/me"), "Couldn’t load your account."),
    staleTime: 5 * 60_000,
  });
}

/** Counts for the rail and the last day of recordings ("seen by your phone"). Polled. */
export function useSync() {
  return useQuery({
    queryKey: keys.sync,
    queryFn: () =>
      must(
        api.GET("/sync", {
          params: { query: { since: new Date(Date.now() - 24 * 3_600_000).toISOString() } },
        }),
        "Couldn’t check for changes.",
      ),
    refetchInterval: 30_000,
  });
}

/** Every recording (Backups). */
export function useAllRecordings() {
  return useQuery({
    queryKey: keys.allRecordings,
    queryFn: async () =>
      (await must(api.GET("/sync"), "Couldn’t load your recordings.")).recordings,
    refetchInterval: 60_000,
  });
}

export function useDay(day: string) {
  return useQuery({
    queryKey: keys.day(day),
    queryFn: () =>
      must(api.GET("/days/{day}", { params: { path: { day } } }), "Couldn’t load this day."),
    refetchInterval: 30_000,
  });
}

export function useConversation(id: string | null) {
  return useQuery({
    queryKey: keys.conversation(id ?? ""),
    queryFn: () =>
      must(
        api.GET("/conversations/{conversation_id}", {
          params: { path: { conversation_id: id ?? "" } },
        }),
        "Couldn’t load this recording.",
      ),
    enabled: id !== null,
  });
}

export function useRecordingConversations(id: string | null) {
  return useQuery({
    queryKey: keys.recordingConversations(id ?? ""),
    queryFn: () =>
      must(
        api.GET("/recordings/{recording_id}/conversations", {
          params: { path: { recording_id: id ?? "" } },
        }),
        "Couldn’t load that recording.",
      ),
    enabled: id !== null,
  });
}

/** A short-lived link to the original audio, refreshed a minute before it expires. */
export function useAudioLink(recordingId: string) {
  return useQuery({
    queryKey: keys.audio(recordingId),
    queryFn: () =>
      must(
        api.GET("/recordings/{recording_id}/audio", {
          params: { path: { recording_id: recordingId } },
        }),
        "The audio isn’t available right now.",
      ),
    staleTime: (query) => {
      const expires = query.state.data ? Date.parse(query.state.data.expires_at) : 0;
      return Math.max(0, expires - Date.now() - 60_000);
    },
    refetchOnWindowFocus: false,
    retry: 1,
  });
}

/** All Review items; the tabs filter them locally so every count is right. */
export function useReview() {
  return useQuery({
    queryKey: keys.review,
    queryFn: () => must(api.GET("/review"), "Couldn’t load Review."),
  });
}

export function useWords() {
  return useQuery({
    queryKey: keys.words,
    queryFn: () => must(api.GET("/words"), "Couldn’t load your word list."),
  });
}

export function useUpdates(enabled = true) {
  return useQuery({
    queryKey: keys.updates,
    queryFn: () => must(api.GET("/updates"), "Couldn’t load updates."),
    enabled,
  });
}

export function useSearch(q: string) {
  return useQuery({
    queryKey: keys.search(q),
    queryFn: () => must(api.GET("/search", { params: { query: { q } } }), "Search didn’t work."),
    enabled: q.trim().length > 0,
    staleTime: 30_000,
  });
}

export function reviewChange(id: number, body: { state?: ReviewState; text?: string }) {
  return must(
    api.PATCH("/review/{review_id}", { params: { path: { review_id: id } }, body }),
    "Couldn’t save that change.",
  );
}
