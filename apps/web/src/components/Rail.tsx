// The left rail (240 px; icons only below 1024 px, W10). Selected = solid navy pill.
import { Link, useRouterState } from "@tanstack/react-router";

import { useMe, useSync } from "../api/queries";
import type { Recording } from "../api/types";
import { useNow } from "../lib/hooks";
import { localToday, whenLabel } from "../lib/time";
import { Logo } from "../screens/Logo";
import { Icon, type IconName } from "./Icon";
import { useUpdatesDrawer } from "./Shell";

type Destination = "today" | "search" | "review" | "settings";

function current(pathname: string): Destination {
  if (pathname.startsWith("/search")) return "search";
  if (pathname.startsWith("/review")) return "review";
  if (pathname.startsWith("/settings")) return "settings";
  return "today";
}

export function Rail() {
  const pathname = useRouterState({ select: (s) => s.location.pathname });
  const here = current(pathname);
  const sync = useSync();
  const updates = useUpdatesDrawer();
  const waiting = sync.data?.waiting_review ?? 0;
  const unread = sync.data?.unread_updates ?? 0;

  return (
    <nav className="rail" aria-label="Main">
      <Link to="/" className="rail__logo" aria-label="Roudio, today">
        <Logo ink="var(--color-ink)" />
      </Link>
      <ul className="rail__items">
        <RailLink to="/" icon="today" label="Today" selected={here === "today" && !updates.open} />
        <RailLink to="/search" icon="search" label="Search" selected={here === "search" && !updates.open} kbd="/" />
        <RailLink
          to="/review"
          icon="review"
          label="Review"
          selected={here === "review" && !updates.open}
          count={waiting}
          countLabel={`${waiting} waiting`}
        />
        <li>
          <button
            type="button"
            className={`rail__item${updates.open ? " rail__item--soft" : ""}`}
            aria-expanded={updates.open}
            aria-label={unread > 0 ? `Updates, ${unread} unread` : "Updates"}
            onClick={() => updates.setOpen(!updates.open)}
          >
            <Icon name="bell" />
            <span className="rail__label">Updates</span>
            {unread > 0 && (
              <span className="rail__count rail__count--unread" aria-hidden="true">
                <span className="dot dot--buoy" />
                <span className="rail__count-number">{unread}</span>
              </span>
            )}
          </button>
        </li>
        <RailLink to="/settings" icon="settings" label="Settings" selected={here === "settings" && !updates.open} />
      </ul>
      <PhoneCard recordings={sync.data?.recordings} />
      <Account />
    </nav>
  );
}

function RailLink({
  to,
  icon,
  label,
  selected,
  count = 0,
  countLabel,
  kbd,
}: {
  to: "/" | "/search" | "/review" | "/settings";
  icon: IconName;
  label: string;
  selected: boolean;
  count?: number;
  countLabel?: string;
  kbd?: string;
}) {
  return (
    <li>
      <Link
        to={to}
        className={`rail__item${selected ? " rail__item--selected" : ""}`}
        aria-current={selected ? "page" : undefined}
        aria-label={count > 0 && countLabel ? `${label}, ${countLabel}` : label}
      >
        <Icon name={icon} />
        <span className="rail__label">{label}</span>
        {kbd && (
          <kbd className="rail__kbd" aria-hidden="true">
            {kbd}
          </kbd>
        )}
        {count > 0 && (
          <span className="rail__count" aria-hidden="true">
            <span className="rail__count-number">{count}</span>
          </span>
        )}
      </Link>
    </li>
  );
}

/**
 * The web can't talk to the recorder, so this only says what the phone has sent: the latest
 * recording it uploaded and what's still arriving. Always worded "seen by your phone".
 */
function PhoneCard({ recordings }: { recordings: Recording[] | undefined }) {
  const today = localToday(useNow());
  const latest = recordings?.reduce<Recording | null>(
    (a, r) => (a === null || r.started_at > a.started_at ? r : a),
    null,
  );
  const arriving = recordings?.filter((r) => r.transfer_state !== "uploaded").length ?? 0;
  return (
    <section className="phone-card" aria-label="Seen by your phone">
      <p className="phone-card__eyebrow">Seen by your phone</p>
      <div className="phone-card__row">
        <Icon name="phone" />
        <div>
          {latest ? (
            <>
              <p className="phone-card__title">
                Plaud ···{latest.recorder_serial.slice(-4)}
              </p>
              <p className="phone-card__sub">
                {arriving > 0 ? `${arriving} arriving from your phone` : "Nothing arriving"}
              </p>
            </>
          ) : (
            <>
              <p className="phone-card__title">Nothing new</p>
              <p className="phone-card__sub">No recordings from your phone in the last day</p>
            </>
          )}
        </div>
      </div>
      <p className="phone-card__foot">
        {latest
          ? `Latest recording ${whenLabel(latest.started_at, latest.zone_offset_seconds, today)}`
          : "The recorder talks only to your phone"}
      </p>
    </section>
  );
}

function Account() {
  const me = useMe();
  const initial = me.data?.email.slice(0, 1).toUpperCase() ?? "·";
  return (
    <Link
      to="/settings"
      search={{ section: "account" }}
      className="rail__account"
      aria-label={me.data ? `Account, ${me.data.email}` : "Account"}
    >
      <span className="avatar" aria-hidden="true">
        {initial}
      </span>
      <span className="rail__account-text">
        <span className="rail__account-name">You</span>
        <span className="rail__account-sub">
          {me.data ? (me.data.drive_connected ? "Google · Drive connected" : "Google · Drive not connected") : "Google"}
        </span>
      </span>
    </Link>
  );
}
