// Playback of the original Ogg Opus file through one <audio> element. Times are offsets into
// that file (`*_ms`), so every source chip, search hit and ?t= link seeks the same timeline.
// The audio link is short-lived: when it fails, a fresh one is fetched and playback resumes.
import { useQueryClient } from "@tanstack/react-query";
import { createContext, type CSSProperties, useCallback, useContext, useRef, useState } from "react";

import { keys, useAudioLink } from "../../api/queries";
import { Icon, Spinner } from "../../components/Icon";
import { clock } from "../../lib/time";

const RATES = [1, 1.25, 1.5, 2, 0.75] as const;

export type Player = {
  ms: number;
  playing: boolean;
  rate: number;
  follow: boolean;
  start: number;
  end: number;
  loading: boolean;
  error: string | null;
  setFollow: (follow: boolean) => void;
  /** Seek to `ms` (clamped to the conversation) and optionally start playing. */
  seek: (ms: number, play?: boolean) => void;
  toggle: () => void;
  skip: (deltaMs: number) => void;
  cycleRate: () => void;
};

const PlayerContext = createContext<Player | null>(null);

export function usePlayerContext(): Player {
  const player = useContext(PlayerContext);
  if (!player) throw new Error("usePlayerContext outside a workspace");
  return player;
}

export const PlayerProvider = PlayerContext.Provider;

/** The player for one conversation (a stretch of one recording). Render `audio` once. */
export function usePlayer(
  recordingId: string,
  start: number,
  end: number,
  startAt: number | null = null,
  autoplay = false,
) {
  const link = useAudioLink(recordingId);
  const queryClient = useQueryClient();
  const audio = useRef<HTMLAudioElement>(null);
  const first = startAt === null ? start : Math.min(Math.max(startAt, start), end);
  const pending = useRef<{ ms: number; play: boolean } | null>({ ms: first, play: autoplay });
  const [ms, setMs] = useState(first);
  const [playing, setPlaying] = useState(false);
  const [rate, setRate] = useState(1);
  const [follow, setFollow] = useState(true);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const retried = useRef(false);

  const clamp = useCallback((t: number) => Math.min(Math.max(t, start), end), [start, end]);

  const apply = useCallback((el: HTMLAudioElement, target: { ms: number; play: boolean }) => {
    el.currentTime = target.ms / 1000;
    if (target.play) {
      el.play().catch(() => {
        // Autoplay blocked (a deep link opened without a click): stay paused at the time.
        setPlaying(false);
      });
    }
  }, []);

  const seek = useCallback(
    (t: number, play = false) => {
      const target = { ms: clamp(t), play };
      setMs(target.ms);
      setFollow(true);
      const el = audio.current;
      if (el && el.readyState >= 1) apply(el, target);
      else pending.current = target;
    },
    [apply, clamp],
  );

  const toggle = useCallback(() => {
    const el = audio.current;
    if (!el) return;
    if (el.paused) {
      if (el.currentTime * 1000 >= end - 250) el.currentTime = start / 1000;
      el.play().catch(() => setError("The browser didn’t start playback. Press play again."));
    } else {
      el.pause();
    }
  }, [start, end]);

  const skip = useCallback(
    (delta: number) => {
      const el = audio.current;
      const from = el ? el.currentTime * 1000 : ms;
      seek(from + delta, el ? !el.paused : false);
    },
    [ms, seek],
  );

  const cycleRate = useCallback(() => {
    setRate((current) => {
      const next = RATES[(RATES.indexOf(current as (typeof RATES)[number]) + 1) % RATES.length] ?? 1;
      if (audio.current) audio.current.playbackRate = next;
      return next;
    });
  }, []);

  const player: Player = {
    ms,
    playing,
    rate,
    follow,
    start,
    end,
    loading: link.isPending || (loading && !error),
    error: link.isError ? link.error.message : error,
    setFollow,
    seek,
    toggle,
    skip,
    cycleRate,
  };

  const element = link.data ? (
    <audio
      ref={audio}
      src={link.data.url}
      preload="metadata"
      onLoadedMetadata={(event) => {
        const el = event.currentTarget;
        setLoading(false);
        setError(null);
        retried.current = false;
        el.playbackRate = rate;
        const target = pending.current ?? { ms, play: false };
        pending.current = null;
        apply(el, { ms: clamp(target.ms), play: target.play });
      }}
      onTimeUpdate={(event) => {
        const t = event.currentTarget.currentTime * 1000;
        setMs(t);
        if (t >= end && !event.currentTarget.paused) event.currentTarget.pause();
      }}
      onPlay={() => setPlaying(true)}
      onPause={() => setPlaying(false)}
      onWaiting={() => setLoading(true)}
      onCanPlay={() => setLoading(false)}
      onError={(event) => {
        const el = event.currentTarget;
        pending.current = { ms: el.currentTime * 1000 || ms, play: !el.paused || playing };
        setPlaying(false);
        if (!retried.current) {
          // Most likely the short-lived link expired: fetch a new one once.
          retried.current = true;
          void queryClient.invalidateQueries({ queryKey: keys.audio(recordingId) });
        } else {
          setError("The audio didn’t load. The recording is safe on the server; try again later.");
        }
      }}
    />
  ) : null;

  return { player, element };
}

/** The docked glass player over the transcript (W3). */
export function PlayerDock({ player }: { player: Player }) {
  const length = Math.max(1, player.end - player.start);
  const progress = Math.min(1, Math.max(0, (player.ms - player.start) / length));
  return (
    <div className="player glass" role="region" aria-label="Player">
      <button
        type="button"
        className="player__play"
        onClick={player.toggle}
        aria-label={player.playing ? "Pause" : "Play"}
        disabled={player.error !== null && !player.playing}
      >
        {player.loading && player.playing ? <Spinner size={22} /> : <Icon name={player.playing ? "pause" : "play"} size={22} />}
      </button>
      <button type="button" className="player__skip" onClick={() => player.skip(-10_000)} aria-label="Back 10 seconds">
        <Icon name="back10" size={22} />
      </button>
      <button type="button" className="player__skip" onClick={() => player.skip(30_000)} aria-label="Forward 30 seconds">
        <Icon name="forward30" size={22} />
      </button>
      <span className="player__time tabular">{clock(player.ms)}</span>
      <input
        type="range"
        className="player__slider"
        min={player.start}
        max={player.end}
        step={1000}
        value={Math.round(player.ms)}
        style={{ "--progress": `${progress * 100}%` } as CSSProperties}
        onChange={(event) => player.seek(Number(event.currentTarget.value), player.playing)}
        aria-label="Position"
        aria-valuetext={`${clock(player.ms)} of ${clock(player.end)}`}
      />
      <span className="player__time player__time--end tabular">{clock(player.end)}</span>
      <button type="button" className="player__rate" onClick={player.cycleRate} aria-label={`Speed ${player.rate} times. Change speed`}>
        {player.rate}×
      </button>
      <button
        type="button"
        className={`player__follow${player.follow ? " player__follow--on" : ""}`}
        aria-pressed={player.follow}
        onClick={() => player.setFollow(!player.follow)}
      >
        <Icon name="follow" size={18} />
        Follow
      </button>
      {player.error && (
        <p className="player__error" role="alert">
          {player.error}
        </p>
      )}
    </div>
  );
}
