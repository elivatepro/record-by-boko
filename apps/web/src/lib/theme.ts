// Look and feel (Settings): light/dark follows the system unless overridden, and glass can be
// swapped for solid surfaces. Both live on <html> (tokens.css reads data-theme).

import { useSyncExternalStore } from "react";

export type Theme = "system" | "light" | "dark";
export type Glass = "glass" | "solid";

const THEME_KEY = "roudio.theme";
const GLASS_KEY = "roudio.glass";
const listeners = new Set<() => void>();

function read<T extends string>(key: string, allowed: readonly T[], fallback: T): T {
  const value = localStorage.getItem(key);
  return allowed.includes(value as T) ? (value as T) : fallback;
}

export function applyLook(): void {
  const theme = read<Theme>(THEME_KEY, ["system", "light", "dark"], "system");
  const glass = read<Glass>(GLASS_KEY, ["glass", "solid"], "glass");
  const root = document.documentElement;
  if (theme === "system") delete root.dataset.theme;
  else root.dataset.theme = theme;
  root.dataset.glass = glass;
}

export function setTheme(theme: Theme): void {
  localStorage.setItem(THEME_KEY, theme);
  applyLook();
  listeners.forEach((l) => l());
}

export function setGlass(glass: Glass): void {
  localStorage.setItem(GLASS_KEY, glass);
  applyLook();
  listeners.forEach((l) => l());
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useLook(): { theme: Theme; glass: Glass } {
  const theme = useSyncExternalStore(subscribe, () =>
    read<Theme>(THEME_KEY, ["system", "light", "dark"], "system"),
  );
  const glass = useSyncExternalStore(subscribe, () =>
    read<Glass>(GLASS_KEY, ["glass", "solid"], "glass"),
  );
  return { theme, glass };
}
