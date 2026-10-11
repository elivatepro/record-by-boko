// One stroke icon set (24 grid, 1.9 stroke, round caps) so every icon matches. Decorative:
// the control around it carries the accessible name.

const PATHS = {
  today: "M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01",
  search: "M10.5 17a6.5 6.5 0 1 0 0-13 6.5 6.5 0 0 0 0 13ZM15.5 15.5 20 20",
  review:
    "M4 13.5V18a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-4.5M4 13.5 6.2 6.3A2 2 0 0 1 8.1 5h7.8a2 2 0 0 1 1.9 1.3L20 13.5M4 13.5h4.5l1 2h5l1-2H20M9.5 9.5l1.8 1.8 3.4-3.4",
  bell: "M6 16V11a6 6 0 1 1 12 0v5l1.5 2h-15L6 16ZM10 20.5a2 2 0 0 0 4 0",
  settings:
    "M4 7h9M17 7h3M4 17h3M11 17h9M15 9.2a2.2 2.2 0 1 0 0-4.4 2.2 2.2 0 0 0 0 4.4ZM9 19.2a2.2 2.2 0 1 0 0-4.4 2.2 2.2 0 0 0 0 4.4Z",
  chevronLeft: "M14.5 6 8.5 12l6 6",
  chevronRight: "M9.5 6l6 6-6 6",
  calendar: "M5 7.5A2.5 2.5 0 0 1 7.5 5h9A2.5 2.5 0 0 1 19 7.5v10a2.5 2.5 0 0 1-2.5 2.5h-9A2.5 2.5 0 0 1 5 17.5v-10ZM5 10h14M9 3.5v3M15 3.5v3M9 14h.01M12 14h.01M15 14h.01",
  check: "M5 12.5 10 17.5 19 7",
  checkCircle: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM8 12.3l2.7 2.7L16 9.7",
  cloud: "M7.5 18.5h9a4 4 0 0 0 .6-7.95A5.5 5.5 0 0 0 6.6 10.6 4 4 0 0 0 7.5 18.5Z",
  cloudCheck: "M7.5 18.5h9a4 4 0 0 0 .6-7.95A5.5 5.5 0 0 0 6.6 10.6 4 4 0 0 0 7.5 18.5ZM9.5 14l1.8 1.8 3.4-3.4",
  alert: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM12 7.5V13M12 16.3v.1",
  play: "M8 5.5v13l10.5-6.5L8 5.5Z",
  pause: "M8.5 5.5v13M15.5 5.5v13",
  back10:
    "M4.5 12a7.5 7.5 0 1 0 2.2-5.3M4.5 4.5v3.7h3.7M10 10v5M13 10h2v5h-2z",
  forward30:
    "M19.5 12a7.5 7.5 0 1 1-2.2-5.3M19.5 4.5v3.7h-3.7M8.5 10h2l-1 2 1 0.2v2.8h-2M13 10h2v5h-2z",
  follow: "M4 7h11M4 12h8M4 17h7M14 16.5l2.2 2.2L21 14",
  pencil: "M5 19l1-4L15.5 5.5a2.1 2.1 0 0 1 3 3L9 18l-4 1ZM13.5 7.5l3 3",
  more: "M6 12h.01M12 12h.01M18 12h.01",
  info: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM12 11v5.5M12 7.7v.1",
  shield: "M12 3.5 5 6.5v5c0 4.4 3 7.9 7 9 4-1.1 7-4.6 7-9v-5l-7-3ZM9 12l2.2 2.2L15.5 10",
  diamond: "M12 4.5 19.5 12 12 19.5 4.5 12 12 4.5Z",
  phone: "M8 3.5h8a1.5 1.5 0 0 1 1.5 1.5v14a1.5 1.5 0 0 1-1.5 1.5H8A1.5 1.5 0 0 1 6.5 19V5A1.5 1.5 0 0 1 8 3.5ZM11 17.5h2",
  trash: "M5 7h14M10 7V5h4v2M7 7l1 12.5h8L17 7M10.5 10.5v6M13.5 10.5v6",
  close: "M6.5 6.5l11 11M17.5 6.5l-11 11",
  arrowUp: "M12 19V5M6 11l6-6 6 6",
  arrowRight: "M5 12h14M13 6l6 6-6 6",
  sun: "M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7ZM4 19h16M12 3.5v1.5M5.6 6.1l1 1M18.4 6.1l-1 1M3.5 12.5H5M19 12.5h1.5",
  offline: "M4 4l16 16M8.5 16.5a5 5 0 0 1 7 0M5 12.5a10 10 0 0 1 4.3-2.4M19 12.5a10 10 0 0 0-3.5-2.1M2 9a14 14 0 0 1 4.6-2.7M22 9a14 14 0 0 0-10-3.9M12 20h.01",
  restart: "M5 12a7 7 0 1 0 2.1-5M5 4v4h4",
  sparkle: "M12 4l1.6 4.6L18 10l-4.4 1.6L12 16l-1.6-4.4L6 10l4.4-1.4L12 4Z",
  external: "M14 5h5v5M19 5l-8 8M17 14v4a1.5 1.5 0 0 1-1.5 1.5h-9A1.5 1.5 0 0 1 5 18V8.5A1.5 1.5 0 0 1 6.5 7H10",
} as const;

export type IconName = keyof typeof PATHS;

export function Icon({
  name,
  size = 20,
  className,
}: {
  name: IconName;
  size?: number;
  className?: string;
}) {
  const filled = name === "play";
  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      aria-hidden="true"
      focusable="false"
    >
      <path
        d={PATHS[name]}
        fill={filled ? "currentColor" : "none"}
        stroke="currentColor"
        strokeWidth={filled ? 1.2 : 1.9}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

/** A small spinner for "in progress" words; still when the user prefers less motion. */
export function Spinner({ size = 16 }: { size?: number }) {
  return (
    <svg className="spinner" width={size} height={size} viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="12" cy="12" r="8.5" fill="none" stroke="var(--color-hairline)" strokeWidth="3" />
      <path
        d="M12 3.5a8.5 8.5 0 0 1 8.5 8.5"
        fill="none"
        stroke="currentColor"
        strokeWidth="3"
        strokeLinecap="round"
      />
    </svg>
  );
}
