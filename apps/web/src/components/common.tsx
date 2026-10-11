import { Link } from "@tanstack/react-router";
import type { ReactNode } from "react";

import type { Status } from "../lib/status";
import { snippetParts, type TextPart } from "../lib/text";
import { clock } from "../lib/time";
import { Icon, type IconName, Spinner } from "./Icon";

const TONE_ICON: Partial<Record<Status["tone"], IconName>> = {
  ok: "checkCircle",
  alert: "alert",
};

/** A status word with its icon; never colour alone. `cloud` swaps in the backup icons. */
export function StatusWord({ status, cloud = false }: { status: Status; cloud?: boolean }) {
  let icon: ReactNode = null;
  if (status.tone === "progress") icon = <Spinner />;
  else if (status.tone === "signal") icon = <span className="dot dot--buoy" aria-hidden="true" />;
  else if (cloud && status.tone === "ok") icon = <Icon name="cloudCheck" size={18} />;
  else if (cloud && status.tone === "alert") icon = <Icon name="cloud" size={18} />;
  else {
    const name = TONE_ICON[status.tone];
    if (name) icon = <Icon name={name} size={18} />;
  }
  return (
    <span className={`status status--${status.tone}`} title={status.detail}>
      {icon}
      {status.text}
    </span>
  );
}

export function Highlighted({ parts }: { parts: TextPart[] }) {
  return (
    <>
      {parts.map((p, i) => (p.match ? <mark key={i}>{p.text}</mark> : <span key={i}>{p.text}</span>))}
    </>
  );
}

export function Snippet({ text }: { text: string }) {
  return <Highlighted parts={snippetParts(text)} />;
}

/** A tappable time: seeks and plays. `label` adds a speaker after the time. */
export function TimeChip({
  ms,
  label,
  onPlay,
  dark = false,
}: {
  ms: number;
  label?: string;
  onPlay: (ms: number) => void;
  dark?: boolean;
}) {
  return (
    <button
      type="button"
      className={`time-chip${dark ? " time-chip--dark" : ""}`}
      onClick={() => onPlay(ms)}
      aria-label={`Play from ${clock(ms)}${label ? `, ${label}` : ""}`}
    >
      <Icon name="play" size={12} />
      <span className="tabular">{clock(ms)}</span>
      {label && <span className="time-chip__label">{label}</span>}
    </button>
  );
}

/** A time that opens the recording workspace there and plays. */
export function TimeLink({
  conversationId,
  ms,
  label,
  dark = false,
}: {
  conversationId: string;
  ms: number;
  label?: string;
  dark?: boolean;
}) {
  return (
    <Link
      to="/c/$conversationId"
      params={{ conversationId }}
      search={{ t: Math.floor(ms / 1000), play: true }}
      className={`time-chip${dark ? " time-chip--dark" : ""}`}
      aria-label={`Play from ${clock(ms)}${label ? ` in ${label}` : ""}`}
    >
      <Icon name="play" size={12} />
      <span className="tabular">{clock(ms)}</span>
    </Link>
  );
}

export function Loading({ label = "Loading…" }: { label?: string }) {
  return (
    <div className="state state--loading" role="status" aria-live="polite">
      <Spinner size={20} />
      <span>{label}</span>
    </div>
  );
}

/** An error with what to do about it. */
export function ErrorState({
  title = "Something didn’t load",
  error,
  onRetry,
}: {
  title?: string;
  error: Error | null;
  onRetry?: () => void;
}) {
  return (
    <div className="state state--error" role="alert">
      <span className="state__icon state__icon--alert">
        <Icon name="alert" size={26} />
      </span>
      <h2>{title}</h2>
      {error && <p>{error.message}</p>}
      {onRetry && (
        <button type="button" className="button button--primary" onClick={onRetry}>
          Try again
        </button>
      )}
    </div>
  );
}

export function EmptyState({
  icon,
  title,
  children,
}: {
  icon: IconName;
  title: string;
  children?: ReactNode;
}) {
  return (
    <div className="state">
      <span className="state__icon">
        <Icon name={icon} size={30} />
      </span>
      <h2>{title}</h2>
      {children}
    </div>
  );
}

/** Text only screen readers hear. */
export function VisuallyHidden({ children }: { children: ReactNode }) {
  return <span className="sr-only">{children}</span>;
}
