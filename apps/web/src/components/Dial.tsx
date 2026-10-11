// The 24-hour dial: midnight at the top, clockwise. Spans come from lib/dial (tested); the
// timeline next to it is the accessible, clickable version of the same day.
import { arcPath, point, type DialSpan } from "../lib/dial";

const SIZE = 440;
const C = SIZE / 2;
const R = 168;
const STROKE = 30;
const MIN_SPAN = 0.004; // about 6 minutes, so a short voice note still shows

export function Dial({
  spans,
  now,
  centre,
  caption,
  nowLabel,
  label,
  small = false,
}: {
  spans: DialSpan[];
  /** Where "now" is (0–1), only on today. */
  now: number | null;
  centre: string;
  caption: string;
  nowLabel?: string;
  label: string;
  small?: boolean;
}) {
  const ticks = Array.from({ length: 48 }, (_, i) => i);
  const nowPoint = now === null ? null : point(C, C, R + STROKE / 2 + 2, now);
  return (
    <figure className={`dial${small ? " dial--small" : ""}`}>
      <svg viewBox={`0 0 ${SIZE} ${SIZE}`} role="img" aria-label={label}>
        <circle cx={C} cy={C} r={R} className="dial__track" strokeWidth={STROKE} fill="none" />
        {spans.map((s, i) => (
          <path
            key={i}
            d={arcPath(C, C, R, s.start, s.end, MIN_SPAN)}
            className={`dial__span dial__span--${s.kind}`}
            strokeWidth={STROKE}
            fill="none"
          />
        ))}
        {ticks.map((i) => {
          const f = i / 48;
          const hour = i % 2 === 0;
          const major = i % 12 === 0;
          const outer = R + STROKE / 2 + (major ? 26 : 20);
          const inner = outer - (major ? 10 : hour ? 5 : 3);
          if (!hour && small) return null;
          const a = point(C, C, inner, f);
          const b = point(C, C, outer, f);
          return (
            <line
              key={i}
              x1={a.x}
              y1={a.y}
              x2={b.x}
              y2={b.y}
              className={major ? "dial__tick dial__tick--major" : "dial__tick"}
            />
          );
        })}
        {!small &&
          ["00", "06", "12", "18"].map((text, i) => {
            const p = point(C, C, R - STROKE / 2 - 22, i / 4);
            return (
              <text key={text} x={p.x} y={p.y} className="dial__hour" textAnchor="middle" dominantBaseline="central">
                {text}
              </text>
            );
          })}
        {nowPoint && (
          <circle cx={nowPoint.x} cy={nowPoint.y} r={7} className="dial__now" />
        )}
      </svg>
      <figcaption className="dial__centre">
        <span className="dial__total tabular">{centre}</span>
        <span className="dial__caption">{caption}</span>
        {nowLabel && (
          <span className="dial__now-label">
            <span className="dot dot--buoy" aria-hidden="true" />
            {nowLabel}
          </span>
        )}
      </figcaption>
    </figure>
  );
}

export function DialLegend({ pending }: { pending: boolean }) {
  return (
    <ul className="legend" aria-label="Key">
      <li>
        <span className="legend__swatch legend__swatch--conversation" aria-hidden="true" />
        Conversation
      </li>
      <li>
        <span className="legend__swatch legend__swatch--quiet" aria-hidden="true" />
        Quiet · asleep
      </li>
      {pending && (
        <li>
          <span className="legend__swatch legend__swatch--pending" aria-hidden="true" />
          Not written up yet
        </li>
      )}
    </ul>
  );
}
