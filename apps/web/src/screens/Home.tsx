// Placeholder until W2 Today is built (milestone 6). Proves the session works end to end.
import { useMutation, useQuery } from "@tanstack/react-query";
import { useNavigate } from "@tanstack/react-router";

import { api } from "../api/client";
import { clearSession } from "../auth/session";
import { Logo } from "./Logo";

export function Home() {
  const navigate = useNavigate();
  const me = useQuery({
    queryKey: ["me"],
    queryFn: async () => {
      const { data, error } = await api.GET("/auth/me");
      if (error) throw new Error("Couldn't load your account.");
      return data;
    },
  });
  const signOut = useMutation({
    mutationFn: async () => {
      await api.POST("/auth/sign-out");
    },
    onSettled: async () => {
      clearSession();
      await navigate({ to: "/sign-in" });
    },
  });

  return (
    <main className="home">
      <Logo ink="var(--color-ink)" />
      {me.data && (
        <div className="home__account">
          <h1>Signed in</h1>
          <p>{me.data.email}</p>
          <p className={me.data.drive_connected ? "" : "home__warning"}>
            {me.data.drive_connected ? "Drive backup connected." : "Drive backup isn’t connected yet."}
          </p>
          <button type="button" className="pill-button" onClick={() => signOut.mutate()}>
            Sign out
          </button>
        </div>
      )}
      {me.isError && <p role="alert">{me.error.message}</p>}
    </main>
  );
}
