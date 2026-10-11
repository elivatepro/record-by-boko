// Screens and deep links: a day (/day/2026-10-10), its digest, a conversation, and a
// conversation at a time (/c/<id>?t=582, seconds into the recording).

import {
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
  redirect,
} from "@tanstack/react-router";

import { hasSession } from "./auth/session";
import { Shell } from "./components/Shell";
import { isIsoDay } from "./lib/time";
import { Backups } from "./screens/Backups";
import { Digest } from "./screens/Digest";
import { NotFound } from "./screens/NotFound";
import { Review } from "./screens/Review";
import { Search } from "./screens/Search";
import { Settings } from "./screens/Settings";
import { SignIn } from "./screens/SignIn";
import { Today } from "./screens/Today";
import { Workspace } from "./screens/workspace/Workspace";

function oneOf<T extends string>(value: unknown, allowed: readonly T[]): T | undefined {
  return allowed.includes(value as T) ? (value as T) : undefined;
}

export const PANEL_TABS = ["notes", "ask", "mine"] as const;
export type PanelTab = (typeof PANEL_TABS)[number];
export const SEARCH_WHEN = ["any", "week", "today"] as const;
export const SEARCH_IN = ["all", "transcript", "summary", "note"] as const;
export const REVIEW_TABS = ["waiting", "accepted", "done"] as const;
export const SETTINGS_SECTIONS = ["look", "writing", "recorder", "usage", "account"] as const;

const rootRoute = createRootRoute({ component: Outlet, notFoundComponent: NotFound });

const signInRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/sign-in",
  component: SignIn,
});

const appRoute = createRoute({
  getParentRoute: () => rootRoute,
  id: "app",
  beforeLoad: () => {
    if (!hasSession()) throw redirect({ to: "/sign-in" });
  },
  component: Shell,
});

const todayRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/",
  component: () => <Today />,
});

const dayRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/day/$day",
  beforeLoad: ({ params }) => {
    if (!isIsoDay(params.day)) throw redirect({ to: "/" });
  },
  component: function DayScreen() {
    const { day } = dayRoute.useParams();
    return <Today day={day} />;
  },
});

const digestRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/day/$day/digest",
  beforeLoad: ({ params }) => {
    if (!isIsoDay(params.day)) throw redirect({ to: "/" });
  },
  component: function DigestScreen() {
    const { day } = digestRoute.useParams();
    return <Digest day={day} />;
  },
});

export type WorkspaceSearch = { t?: number; play?: boolean; tab?: PanelTab };

export const workspaceRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/c/$conversationId",
  validateSearch: (search: Record<string, unknown>): WorkspaceSearch => {
    const t = Number(search.t);
    return {
      t: Number.isFinite(t) && t >= 0 ? Math.floor(t) : undefined,
      play: search.play === true || search.play === "true" ? true : undefined,
      tab: oneOf(search.tab, PANEL_TABS),
    };
  },
  component: Workspace,
});

export type SearchSearch = {
  q?: string;
  when?: (typeof SEARCH_WHEN)[number];
  in?: (typeof SEARCH_IN)[number];
};

export const searchRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/search",
  validateSearch: (search: Record<string, unknown>): SearchSearch => ({
    q: typeof search.q === "string" && search.q ? search.q : undefined,
    when: oneOf(search.when, SEARCH_WHEN),
    in: oneOf(search.in, SEARCH_IN),
  }),
  component: Search,
});

export const reviewRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/review",
  validateSearch: (search: Record<string, unknown>): { state?: (typeof REVIEW_TABS)[number] } => ({
    state: oneOf(search.state, REVIEW_TABS),
  }),
  component: Review,
});

export const settingsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/settings",
  validateSearch: (
    search: Record<string, unknown>,
  ): { section?: (typeof SETTINGS_SECTIONS)[number] } => ({
    section: oneOf(search.section, SETTINGS_SECTIONS),
  }),
  component: Settings,
});

const backupsRoute = createRoute({
  getParentRoute: () => appRoute,
  path: "/settings/backups",
  component: Backups,
});

const routeTree = rootRoute.addChildren([
  signInRoute,
  appRoute.addChildren([
    todayRoute,
    dayRoute,
    digestRoute,
    workspaceRoute,
    searchRoute,
    reviewRoute,
    settingsRoute,
    backupsRoute,
  ]),
]);

export const router = createRouter({ routeTree, defaultPreload: "intent" });

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}
