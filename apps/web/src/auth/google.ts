// Google Identity Services, popup code flow. One consent covers sign-in and the Drive grant:
// the backend swaps the code, checks the ID token that comes back, and keeps the Drive
// refresh token encrypted.

const GIS_SRC = "https://accounts.google.com/gsi/client";
const SCOPES = "openid email https://www.googleapis.com/auth/drive.file";

type CodeResponse = { code?: string; error?: string; error_description?: string };
type CodeClient = { requestCode: () => void };
type GoogleOAuth2 = {
  initCodeClient: (config: {
    client_id: string;
    scope: string;
    ux_mode: "popup";
    callback: (response: CodeResponse) => void;
    error_callback?: (error: { type: string }) => void;
  }) => CodeClient;
};

declare global {
  interface Window {
    google?: { accounts: { oauth2: GoogleOAuth2 } };
  }
}

let loading: Promise<void> | null = null;

function loadScript(): Promise<void> {
  loading ??= new Promise((resolve, reject) => {
    const script = document.createElement("script");
    script.src = GIS_SRC;
    script.async = true;
    script.onload = () => resolve();
    script.onerror = () => {
      loading = null;
      reject(new Error("Couldn't load Google sign-in. Check your connection."));
    };
    document.head.appendChild(script);
  });
  return loading;
}

export class GoogleCancelled extends Error {}

/** Open Google's popup and resolve with the one-time authorization code. */
export async function requestGoogleCode(): Promise<string> {
  const clientId = import.meta.env.VITE_GOOGLE_CLIENT_ID;
  if (!clientId) throw new Error("Google sign-in isn't configured (VITE_GOOGLE_CLIENT_ID).");
  await loadScript();
  const oauth2 = window.google?.accounts.oauth2;
  if (!oauth2) throw new Error("Couldn't load Google sign-in. Check your connection.");
  return new Promise((resolve, reject) => {
    oauth2
      .initCodeClient({
        client_id: clientId,
        scope: SCOPES,
        ux_mode: "popup",
        callback: (response) => {
          if (response.code) resolve(response.code);
          else reject(new Error(response.error_description ?? "Google sign-in didn't finish."));
        },
        error_callback: () => reject(new GoogleCancelled()),
      })
      .requestCode();
  });
}
