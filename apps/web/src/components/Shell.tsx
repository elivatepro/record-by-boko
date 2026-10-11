// The signed-in frame: left rail, the screen, the Updates drawer, and app-wide keys.
import { useQueryClient } from "@tanstack/react-query";
import { Outlet, useNavigate, useRouterState } from "@tanstack/react-router";
import { createContext, useContext, useEffect, useState } from "react";

import { hasSession, onSessionChange } from "../auth/session";
import { isTyping, useKeydown, useOnline } from "../lib/hooks";
import { Icon } from "./Icon";
import { Rail } from "./Rail";
import { UpdatesDrawer } from "./UpdatesDrawer";

type UpdatesControl = { open: boolean; setOpen: (open: boolean) => void };
const UpdatesContext = createContext<UpdatesControl>({ open: false, setOpen: () => {} });

export function useUpdatesDrawer(): UpdatesControl {
  return useContext(UpdatesContext);
}

export const FOCUS_SEARCH_EVENT = "roudio:focus-search";

export function Shell() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const online = useOnline();
  const [updatesOpen, setUpdatesOpen] = useState(false);
  const pathname = useRouterState({ select: (s) => s.location.pathname });

  // A refresh that fails with 401 clears the session; go back to sign-in.
  useEffect(
    () =>
      onSessionChange(() => {
        if (!hasSession()) {
          queryClient.clear();
          void navigate({ to: "/sign-in" });
        }
      }),
    [navigate, queryClient],
  );

  useKeydown((event) => {
    if (event.key !== "/" || event.metaKey || event.ctrlKey || event.altKey || isTyping(event)) return;
    event.preventDefault();
    setUpdatesOpen(false);
    if (pathname === "/search") window.dispatchEvent(new Event(FOCUS_SEARCH_EVENT));
    else void navigate({ to: "/search" });
  });

  return (
    <UpdatesContext.Provider value={{ open: updatesOpen, setOpen: setUpdatesOpen }}>
      <div className="shell">
        <a className="skip-link" href="#main">
          Skip to content
        </a>
        <Rail />
        <main id="main" className="shell__main" tabIndex={-1}>
          {!online && (
            <div className="offline-banner" role="status">
              <Icon name="offline" size={18} />
              You’re offline. You can read what’s already loaded; changes, Search and Ask need the
              internet.
            </div>
          )}
          <Outlet />
        </main>
        {updatesOpen && <UpdatesDrawer onClose={() => setUpdatesOpen(false)} />}
      </div>
    </UpdatesContext.Provider>
  );
}
