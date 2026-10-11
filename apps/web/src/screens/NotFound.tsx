import { Link } from "@tanstack/react-router";

export function NotFound() {
  return (
    <main className="page not-found">
      <h1 className="page-title">Nothing here</h1>
      <p className="page-sub">That link doesn’t lead anywhere in Roudio.</p>
      <Link to="/" className="button button--primary">
        Go to today
      </Link>
    </main>
  );
}
