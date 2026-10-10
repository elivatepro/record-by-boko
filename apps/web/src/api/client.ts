import createClient, { type Middleware } from "openapi-fetch";

import { getAccessToken, refreshAccessToken } from "../auth/session";
import type { paths } from "./schema";

export const API_URL = import.meta.env.VITE_API_URL ?? "http://localhost:8000";

const auth: Middleware = {
  async onRequest({ request, schemaPath }) {
    if (schemaPath === "/auth/google" || schemaPath === "/auth/refresh") return request;
    const token = await getAccessToken();
    if (token) request.headers.set("Authorization", `Bearer ${token}`);
    return request;
  },
  async onResponse({ request, response, schemaPath }) {
    // The access token may have been revoked early; refresh once and retry.
    if (response.status !== 401 || schemaPath.startsWith("/auth/")) return response;
    const token = await refreshAccessToken();
    if (!token) return response;
    const retry = new Request(request);
    retry.headers.set("Authorization", `Bearer ${token}`);
    return fetch(retry);
  },
};

export const api = createClient<paths>({ baseUrl: API_URL });
api.use(auth);

/** The `detail` of a Roudio API error, if the response carried one. */
export type ApiErrorDetail = { code: string; message: string; email?: string };

export function errorDetail(error: unknown): ApiErrorDetail | null {
  const detail = (error as { detail?: unknown } | undefined)?.detail;
  if (detail && typeof detail === "object" && "code" in detail) return detail as ApiErrorDetail;
  return null;
}
