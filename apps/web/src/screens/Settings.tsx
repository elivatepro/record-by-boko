// W9 Settings with a section list: look and feel, writing up (word list, services), recorder
// (seen by your phone), backups (W8), and account (sign out).
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { getRouteApi, Link, useNavigate } from "@tanstack/react-router";
import { useState } from "react";

import { api } from "../api/client";
import { keys, must, useAllRecordings, useCosts, useMe, useSync, useWords } from "../api/queries";
import type { Word } from "../api/types";
import { clearSession } from "../auth/session";
import { ErrorState, Loading } from "../components/common";
import { Icon } from "../components/Icon";
import { bytes, grouped, percent, provider, purposeLine, usd } from "../lib/costs";
import { useNow } from "../lib/hooks";
import { type Glass, setGlass, setTheme, type Theme, useLook } from "../lib/theme";
import { localToday, whenLabel } from "../lib/time";

const route = getRouteApi("/app/settings");
type Section = "look" | "writing" | "recorder" | "usage" | "account";

const SECTIONS: [Section, string][] = [
  ["look", "Look and feel"],
  ["writing", "Writing up"],
  ["recorder", "Recorder"],
  ["usage", "Usage"],
  ["account", "Account"],
];

export function Settings() {
  const section: Section = route.useSearch().section ?? "writing";
  const navigate = useNavigate();
  const recordings = useAllRecordings();
  const backupFailed = (recordings.data ?? []).some((r) => r.backup_state === "failed");

  return (
    <div className="page settings">
      <h1 className="page-title page-title--large">Settings</h1>
      <div className="settings__layout">
        <nav className="settings__nav" aria-label="Settings sections">
          <ul>
            {SECTIONS.slice(0, 3).map(([id, label]) => (
              <li key={id}>
                <button
                  type="button"
                  className={`settings__link${section === id ? " settings__link--selected" : ""}`}
                  aria-current={section === id ? "page" : undefined}
                  onClick={() => void navigate({ to: "/settings", search: { section: id }, replace: true })}
                >
                  {label}
                </button>
              </li>
            ))}
            <li>
              <Link to="/settings/backups" className="settings__link">
                Backups and clean-up
                {backupFailed && (
                  <span className="dot dot--alert" role="img" aria-label="A backup failed" />
                )}
              </Link>
            </li>
            <li>
              <button
                type="button"
                className={`settings__link${section === "account" ? " settings__link--selected" : ""}`}
                aria-current={section === "account" ? "page" : undefined}
                onClick={() => void navigate({ to: "/settings", search: { section: "account" }, replace: true })}
              >
                Account
              </button>
            </li>
          </ul>
        </nav>
        <div className="settings__content">
          {section === "look" && <LookAndFeel />}
          {section === "writing" && <WritingUp />}
          {section === "recorder" && <Recorder />}
          {section === "usage" && <Usage />}
          {section === "account" && <Account />}
        </div>
      </div>
    </div>
  );
}

function LookAndFeel() {
  const { theme, glass } = useLook();
  return (
    <section aria-labelledby="look-title">
      <h2 id="look-title" className="section-title">
        Look and feel
      </h2>
      <div className="setting-row">
        <div>
          <p className="setting-row__title" id="theme-label">
            Theme
          </p>
          <p className="setting-row__sub">Follows your computer’s light or dark setting unless you choose.</p>
        </div>
        <div className="segmented" role="group" aria-labelledby="theme-label">
          {(
            [
              ["system", "System"],
              ["light", "Light"],
              ["dark", "Dark"],
            ] as [Theme, string][]
          ).map(([value, label]) => (
            <button key={value} type="button" aria-pressed={theme === value} onClick={() => setTheme(value)}>
              {label}
            </button>
          ))}
        </div>
      </div>
      <div className="setting-row">
        <div>
          <p className="setting-row__title" id="glass-label">
            Glass
          </p>
          <p className="setting-row__sub">
            The player and Ask box float as frosted glass. Solid is calmer and easier to read over text.
          </p>
        </div>
        <div className="segmented" role="group" aria-labelledby="glass-label">
          {(
            [
              ["glass", "Glass"],
              ["solid", "Solid"],
            ] as [Glass, string][]
          ).map(([value, label]) => (
            <button key={value} type="button" aria-pressed={glass === value} onClick={() => setGlass(value)}>
              {label}
            </button>
          ))}
        </div>
      </div>
    </section>
  );
}

const COLLAPSED = 12;

function WritingUp() {
  const words = useWords();
  const queryClient = useQueryClient();
  const [term, setTerm] = useState("");
  const [showAll, setShowAll] = useState(false);
  const refresh = () => queryClient.invalidateQueries({ queryKey: keys.words });

  const add = useMutation({
    mutationFn: (value: string) => must(api.POST("/words", { body: { term: value } }), "Couldn’t add that word."),
    onSuccess: async () => {
      setTerm("");
      await refresh();
    },
  });
  const confirm = useMutation({
    mutationFn: (w: Word) =>
      must(
        api.PATCH("/words/{word_id}", { params: { path: { word_id: w.id } }, body: { confirmed: true } }),
        "Couldn’t keep that word.",
      ),
    onSuccess: refresh,
  });
  const remove = useMutation({
    mutationFn: (w: Word) =>
      must(api.DELETE("/words/{word_id}", { params: { path: { word_id: w.id } } }), "Couldn’t remove that word."),
    onSuccess: refresh,
  });

  const list = words.data ?? [];
  const toConfirm = list.filter((w) => !w.confirmed);
  const confirmed = list.filter((w) => w.confirmed).sort((a, b) => a.term.localeCompare(b.term));
  const shown = showAll ? confirmed : confirmed.slice(0, COLLAPSED);
  const error = add.error ?? confirm.error ?? remove.error;

  return (
    <>
      <section aria-labelledby="languages-title">
        <h2 id="languages-title" className="section-title">
          Languages
        </h2>
        <div className="setting-row">
          <div>
            <p className="setting-row__title">Detected for each recording</p>
            <p className="setting-row__sub">
              The speech service works out the language as it goes, including mixed English and Pidgin. Each recording
              shows what it heard.
            </p>
          </div>
        </div>
      </section>

      <section aria-labelledby="words-title">
        <div className="section-head">
          <h2 id="words-title" className="section-title">
            Your word list
          </h2>
          <span className="muted">
            {confirmed.length} {confirmed.length === 1 ? "word" : "words"}
            {toConfirm.length > 0 ? ` · ${toConfirm.length} to confirm` : ""}
          </span>
        </div>
        <p className="setting-row__sub">
          Names and words the speech service should listen for: people, places and terms it doesn’t know.
        </p>
        <form
          className="add-word"
          onSubmit={(event) => {
            event.preventDefault();
            if (term.trim()) add.mutate(term.trim());
          }}
        >
          <label htmlFor="add-word" className="sr-only">
            Add a name or word
          </label>
          <input
            id="add-word"
            value={term}
            maxLength={49}
            placeholder="Add a name or word"
            onChange={(event) => setTerm(event.currentTarget.value)}
          />
          <button type="submit" className="button button--primary button--small" disabled={!term.trim() || add.isPending}>
            Add
          </button>
        </form>
        {error && (
          <p className="inline-error" role="alert">
            {error.message}
          </p>
        )}
        {words.isPending ? (
          <Loading />
        ) : words.isError ? (
          <ErrorState error={words.error} onRetry={() => void words.refetch()} />
        ) : (
          <>
            {toConfirm.length > 0 && (
              <ul className="learned" aria-label="Learned from your edits">
                {toConfirm.map((w) => (
                  <li key={w.id} className="learned__item">
                    <div>
                      <p className="learned__term">{w.term}</p>
                      <p className="setting-row__sub">Learned from your edits · keep it?</p>
                    </div>
                    <button type="button" className="button button--primary button--small" onClick={() => confirm.mutate(w)} disabled={confirm.isPending}>
                      Keep
                    </button>
                    <button type="button" className="icon-button" aria-label={`Don’t keep ${w.term}`} onClick={() => remove.mutate(w)} disabled={remove.isPending}>
                      <Icon name="close" size={18} />
                    </button>
                  </li>
                ))}
              </ul>
            )}
            <ul className="word-chips" aria-label="Your words">
              {shown.map((w) => (
                <li key={w.id} className="word-chip">
                  {w.term}
                  <button type="button" className="word-chip__remove" aria-label={`Remove ${w.term}`} onClick={() => remove.mutate(w)} disabled={remove.isPending}>
                    <Icon name="close" size={14} />
                  </button>
                </li>
              ))}
              {confirmed.length > COLLAPSED && (
                <li>
                  <button type="button" className="word-chip word-chip--more" onClick={() => setShowAll(!showAll)} aria-expanded={showAll}>
                    {showAll ? "Show fewer" : `+${confirmed.length - COLLAPSED} more`}
                  </button>
                </li>
              )}
            </ul>
            {list.length === 0 && <p className="muted">No words yet. Add names you say often.</p>}
            <p className="setting-row__sub">The list goes with each recording to the speech service. It costs a little extra per hour.</p>
          </>
        )}
      </section>

      <section aria-labelledby="services-title">
        <h2 id="services-title" className="section-title">
          Services
        </h2>
        <div className="setting-row">
          <div>
            <p className="setting-row__title">Speech to text</p>
            <p className="setting-row__sub">ElevenLabs Scribe v2 · silence skipped before sending</p>
          </div>
        </div>
        <div className="setting-row">
          <div>
            <p className="setting-row__title">Notes, digest and Ask</p>
            <p className="setting-row__sub">OpenAI GPT-6.1 Sol · transcript text only, never audio</p>
          </div>
        </div>
        <div className="setting-row">
          <div>
            <p className="setting-row__title">Tidy obvious mishearings</p>
            <p className="setting-row__sub">Always on. Changes are marked in the transcript and the original is kept.</p>
          </div>
        </div>
      </section>
    </>
  );
}

function Recorder() {
  const sync = useSync();
  const today = localToday(useNow());
  const latest = (sync.data?.recordings ?? []).reduce<{ started_at: string; zone_offset_seconds: number; recorder_serial: string } | null>(
    (a, r) => (a === null || r.started_at > a.started_at ? r : a),
    null,
  );
  return (
    <section aria-labelledby="recorder-title">
      <h2 id="recorder-title" className="section-title">
        Recorder
      </h2>
      <div className="callout callout--plain">
        <Icon name="phone" />
        <div>
          <strong>Seen by your phone</strong>
          <p>
            The web can’t talk to the recorder. Your phone links it, brings recordings across, and clears it once every
            backup is checked. Open Roudio on your phone for battery, storage and transfers.
          </p>
        </div>
      </div>
      <div className="setting-row">
        <div>
          <p className="setting-row__title">Latest recording from your phone</p>
          <p className="setting-row__sub">
            {latest
              ? `Plaud ···${latest.recorder_serial.slice(-4)} · ${whenLabel(latest.started_at, latest.zone_offset_seconds, today)}`
              : "Nothing in the last day"}
          </p>
        </div>
      </div>
    </section>
  );
}

function Usage() {
  const costs = useCosts();
  if (costs.isPending) return <Loading />;
  if (costs.isError) return <ErrorState error={costs.error} onRetry={() => void costs.refetch()} />;
  const c = costs.data;
  const el = c.elevenlabs;
  const openai = c.month.filter((l) => l.service === "openai");
  const tokens = openai.filter((l) => l.unit === "tokens").reduce((n, l) => n + l.units, 0);
  const searches = openai.filter((l) => l.unit === "searches").reduce((n, l) => n + l.units, 0);
  const openaiUsd = openai.reduce((n, l) => n + l.usd, 0);
  const scribe = c.month.filter((l) => l.service === "scribe");
  const scribeHours = scribe.reduce((n, l) => n + (l.unit === "seconds" ? l.units / 3600 : l.units), 0);
  const scribeUsd = scribe.reduce((n, l) => n + l.usd, 0);
  const used = el ? percent(el.credits_used, el.credits_limit) : 0;
  return (
    <section aria-labelledby="usage-title">
      <h2 id="usage-title" className="section-title">
        Usage
      </h2>
      <p className="usage__lead">
        What Roudio uses: ElevenLabs turns speech into text, and OpenAI writes the notes, digests and Ask answers.
        Amounts are Roudio’s own estimates from what each call reported.
      </p>

      <div className="usage__stats">
        <div className="usage__stat">
          <span className="usage__stat-value tabular">{usd(c.today_usd)}</span>
          <span className="usage__stat-label">Today</span>
        </div>
        <div className="usage__stat">
          <span className="usage__stat-value tabular">{usd(c.month_usd)}</span>
          <span className="usage__stat-label">This month</span>
        </div>
        <div className="usage__stat">
          <span className="usage__stat-value tabular">{usd(c.total_usd)}</span>
          <span className="usage__stat-label">Since you started</span>
        </div>
      </div>

      <div className="usage__cards">
        <article className="usage__card" aria-labelledby="usage-el">
          <h3 id="usage-el" className="usage__card-title">ElevenLabs · speech to text</h3>
          {el ? (
            <>
              <p className="usage__card-sub">
                {el.tier.charAt(0).toUpperCase() + el.tier.slice(1)} plan · {el.status}
                {el.resets_at ? ` · resets ${new Date(el.resets_at).toLocaleDateString("en-GB", { day: "numeric", month: "short" })}` : ""}
              </p>
              <div className="usage__meter" role="meter" aria-valuemin={0} aria-valuemax={el.credits_limit} aria-valuenow={el.credits_used} aria-label="ElevenLabs credits used">
                <span style={{ width: `${used}%` }} className={used >= 90 ? "usage__meter-fill usage__meter-fill--high" : "usage__meter-fill"} />
              </div>
              <p className="usage__card-sub tabular">
                {grouped(el.credits_used)} of {grouped(el.credits_limit)} credits used ({used}%)
              </p>
            </>
          ) : (
            <p className="usage__card-sub">The plan and credits couldn’t be read from ElevenLabs just now.</p>
          )}
          <p className="usage__card-line tabular">
            This month: {scribeHours.toFixed(1)} h of speech · {usd(scribeUsd)}
          </p>
        </article>

        <article className="usage__card" aria-labelledby="usage-oa">
          <h3 id="usage-oa" className="usage__card-title">OpenAI · notes, digests and Ask</h3>
          <p className="usage__card-sub">GPT-6.1 Sol. OpenAI doesn’t share the account balance with an app key.</p>
          <p className="usage__card-line tabular">
            This month: {grouped(tokens)} tokens{searches > 0 ? ` · ${grouped(searches)} web searches` : ""} · {usd(openaiUsd)}
          </p>
          <a className="usage__link" href="https://platform.openai.com/usage" target="_blank" rel="noreferrer">
            Balance and billing on OpenAI <Icon name="external" />
          </a>
        </article>
      </div>

      <h3 className="usage__heading">What it was used for this month</h3>
      {c.purposes.length === 0 ? (
        <p className="setting-row__sub">Nothing yet this month.</p>
      ) : (
        c.purposes.map((line) => {
          const words = purposeLine(line);
          return (
            <div className="setting-row" key={`${line.purpose}-${line.service}-${line.unit}`}>
              <div>
                <p className="setting-row__title">{words.name}</p>
                <p className="setting-row__sub">
                  {provider(line.service)} · {words.amount}
                </p>
              </div>
              <span className="setting-row__value">{usd(line.usd)}</span>
            </div>
          );
        })
      )}

      <h3 className="usage__heading">Your recordings</h3>
      <div className="setting-row">
        <div>
          <p className="setting-row__title">Recorded</p>
          <p className="setting-row__sub">
            {c.recorded_hours.toFixed(1)} h in total · {c.speech_hours.toFixed(1)} h of it was speech, the only part sent to ElevenLabs
          </p>
        </div>
        <span className="setting-row__value">{c.recordings_written_up} written up</span>
      </div>
      <div className="setting-row">
        <div>
          <p className="setting-row__title">Audio kept on the server</p>
          <p className="setting-row__sub">Storage is billed by Railway, not counted above</p>
        </div>
        <span className="setting-row__value">{bytes(c.stored_audio_bytes)}</span>
      </div>
    </section>
  );
}

function Account() {
  const me = useMe();
  const navigate = useNavigate();
  const signOut = useMutation({
    mutationFn: async () => {
      await api.POST("/auth/sign-out").catch(() => undefined);
    },
    onSettled: async () => {
      clearSession();
      await navigate({ to: "/sign-in" });
    },
  });
  return (
    <section aria-labelledby="account-title">
      <h2 id="account-title" className="section-title">
        Account
      </h2>
      <div className="setting-row">
        <div>
          <p className="setting-row__title">Google account</p>
          <p className="setting-row__sub">{me.data?.email ?? "Loading…"}</p>
        </div>
      </div>
      <div className="setting-row">
        <div>
          <p className="setting-row__title">Drive backup</p>
          <p className={`setting-row__sub${me.data && !me.data.drive_connected ? " text-signal" : ""}`}>
            {me.data ? (me.data.drive_connected ? "Connected" : "Not connected yet. Sign out and in again to grant it.") : "…"}
          </p>
        </div>
      </div>
      <div className="setting-row">
        <div>
          <p className="setting-row__title">Sign out of this browser</p>
          <p className="setting-row__sub">Your phone stays signed in and keeps transferring.</p>
        </div>
        <button type="button" className="button button--soft" onClick={() => signOut.mutate()} disabled={signOut.isPending}>
          {signOut.isPending ? "Signing out…" : "Sign out"}
        </button>
      </div>
    </section>
  );
}
