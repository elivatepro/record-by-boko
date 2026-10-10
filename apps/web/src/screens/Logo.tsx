export function Logo({ ink = "var(--color-on-hero-card)" }: { ink?: string }) {
  return (
    <div className="logo">
      <svg width="26" height="26" viewBox="0 0 22 22" aria-hidden="true">
        <circle cx="11" cy="11" r="8" fill="none" stroke={ink} strokeWidth="3" />
        <circle cx="17.2" cy="5.6" r="2.6" fill="var(--color-buoy)" stroke="var(--color-hero-card)" strokeWidth="1.5" />
      </svg>
      <span style={{ color: ink }}>roudio</span>
    </div>
  );
}
