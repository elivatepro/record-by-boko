// W1 Sign in (Paper page "07 Web").
import { useMutation } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";

import { api, errorDetail, type ApiErrorDetail } from "../api/client";
import { GoogleCancelled, requestGoogleCode } from "../auth/google";
import { storeSession } from "../auth/session";
import { Logo } from "./Logo";

export function SignIn() {
  const navigate = useNavigate();
  const signIn = useMutation({
    mutationFn: async () => {
      const code = await requestGoogleCode();
      const { data, error } = await api.POST("/auth/google", {
        body: { client: "web", drive_auth_code: code },
      });
      if (error) throw errorDetail(error) ?? new Error("Sign-in failed. Try again.");
      return data;
    },
    onSuccess: async (tokens) => {
      storeSession(tokens);
      await navigate({ to: "/" });
    },
  });

  const failure = signIn.error instanceof GoogleCancelled ? null : signIn.error;

  return (
    <main className="sign-in">
      <section className="sign-in__hero" aria-hidden="false">
        <Logo />
        <div className="sign-in__pitch">
          <h1>Your whole day, written down and easy to find.</h1>
          <p>
            Read, search and review your recordings on a bigger screen. Your phone still does the
            transfers from the recorder.
          </p>
        </div>
        <HeroRing />
      </section>

      <section className="sign-in__panel">
        <div className="sign-in__form">
          <div className="sign-in__heading">
            <h2>Sign in</h2>
            <p>Use the same Google account as on your phone.</p>
          </div>

          <button
            type="button"
            className="google-button"
            onClick={() => signIn.mutate()}
            disabled={signIn.isPending}
          >
            <GoogleMark />
            {signIn.isPending ? "Signing in…" : "Sign in with Google"}
          </button>

          {failure && <SignInError error={failure} />}

          <p className="sign-in__note">
            <ShieldIcon />
            Only the owner’s account can sign in. No passwords are stored.
          </p>
        </div>
      </section>
    </main>
  );
}

function SignInError({ error }: { error: Error | ApiErrorDetail }) {
  const detail = "code" in error ? error : null;
  const notOwner = detail?.code === "not_owner";
  return (
    <div className="callout callout--alert" role="alert">
      <AlertIcon />
      <div>
        <strong>{notOwner ? "That account can’t open this Roudio" : "Sign-in didn’t work"}</strong>
        <p>
          {notOwner
            ? `${detail?.email ?? "That account"} isn’t the owner. Sign out of Google and pick the account you use on your phone.`
            : error.message}
        </p>
      </div>
    </div>
  );
}

function HeroRing() {
  return (
    <svg className="sign-in__ring" width="560" height="560" viewBox="0 0 560 560" aria-hidden="true">
      <circle cx="280" cy="280" r="230" fill="none" stroke="var(--color-hero-tile)" strokeWidth="44" />
      <path d="M280 50 A230 230 0 0 1 505 232" fill="none" stroke="#3A4E70" strokeWidth="44" />
      <path d="M462 420 A230 230 0 0 1 400 476" fill="none" stroke="var(--palette-night-ink)" strokeWidth="44" />
      <path d="M330 504 A230 230 0 0 1 250 508" fill="none" stroke="var(--palette-night-ink)" strokeWidth="44" />
      <path d="M160 476 A230 230 0 0 1 128 452" fill="none" stroke="var(--palette-night-ink)" strokeWidth="44" />
    </svg>
  );
}

function GoogleMark() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true">
      <path d="M21.6 12.2c0-.7-.1-1.4-.2-2H12v3.8h5.4a4.6 4.6 0 0 1-2 3v2.5h3.2c1.9-1.7 3-4.3 3-7.3z" fill="#4285F4" />
      <path d="M12 22c2.7 0 5-.9 6.6-2.5l-3.2-2.5c-.9.6-2 1-3.4 1-2.6 0-4.8-1.8-5.6-4.1H3.1v2.6A10 10 0 0 0 12 22z" fill="#34A853" />
      <path d="M6.4 13.9a6 6 0 0 1 0-3.8V7.5H3.1a10 10 0 0 0 0 9z" fill="#FBBC05" />
      <path d="M12 6c1.5 0 2.8.5 3.8 1.5l2.9-2.9A10 10 0 0 0 3.1 7.5l3.3 2.6C7.2 7.8 9.4 6 12 6z" fill="#EA4335" />
    </svg>
  );
}

function AlertIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="12" cy="12" r="9" fill="none" stroke="var(--color-alert)" strokeWidth="1.9" />
      <path d="M12 7.5v5.5M12 16.3v.1" stroke="var(--color-alert)" strokeWidth="2" strokeLinecap="round" />
    </svg>
  );
}

function ShieldIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true">
      <path d="M12 3.5 5 6.5v5c0 4.4 3 7.9 7 9 4-1.1 7-4.6 7-9v-5z" fill="none" stroke="var(--color-muted)" strokeWidth="1.75" strokeLinejoin="round" />
    </svg>
  );
}
