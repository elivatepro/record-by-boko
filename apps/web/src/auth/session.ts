// Roudio session tokens. The access token lives in memory; the refresh token is kept in
// localStorage so a reload stays signed in.
// TODO(milestone 6): move the refresh token to an httpOnly cookie to shrink the XSS blast radius.

import type { components } from "../api/schema";

type SessionTokens = components["schemas"]["SessionTokens"];

const REFRESH_KEY = "roudio.refresh";
const API_URL = import.meta.env.VITE_API_URL ?? "http://localhost:8000";

let accessToken: string | null = null;
let accessExpiresAt = 0;
let refreshing: Promise<string | null> | null = null;
const listeners = new Set<() => void>();

export function onSessionChange(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function hasSession(): boolean {
  return accessToken !== null || localStorage.getItem(REFRESH_KEY) !== null;
}

export function storeSession(tokens: SessionTokens): void {
  accessToken = tokens.access_token;
  accessExpiresAt = Date.parse(tokens.access_expires_at);
  localStorage.setItem(REFRESH_KEY, tokens.refresh_token);
  listeners.forEach((l) => l());
}

export function clearSession(): void {
  accessToken = null;
  accessExpiresAt = 0;
  localStorage.removeItem(REFRESH_KEY);
  listeners.forEach((l) => l());
}

/** A valid access token, refreshing it first if it's about to expire. Null when signed out. */
export async function getAccessToken(): Promise<string | null> {
  if (accessToken && accessExpiresAt - Date.now() > 30_000) return accessToken;
  return refreshAccessToken();
}

/** Swap the refresh token for new tokens. Concurrent callers share one request. */
export function refreshAccessToken(): Promise<string | null> {
  refreshing ??= (async () => {
    const refresh = localStorage.getItem(REFRESH_KEY);
    if (!refresh) return null;
    try {
      const response = await fetch(`${API_URL}/auth/refresh`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refresh_token: refresh }),
      });
      if (response.status === 401) {
        clearSession();
        return null;
      }
      if (!response.ok) throw new Error(`Refresh failed: ${response.status}`);
      const tokens = (await response.json()) as SessionTokens;
      storeSession(tokens);
      return tokens.access_token;
    } finally {
      refreshing = null;
    }
  })();
  return refreshing;
}
