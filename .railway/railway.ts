// Roudio's Railway infrastructure (decision 7). Preview with `railway config plan`,
// apply with `railway config apply`. Secrets are never written here: they live in Railway
// as shared variables and are only referenced (ctx.shared.*).
import { bucket, defineRailway, github, postgres, project, service } from "railway/iac";

export default defineRailway((ctx) => {
  const db = postgres("Postgres");
  // Original audio (spec §2). Used from milestone 2.
  const audio = bucket("audio", { region: "ams" });

  // One image, two services (decision 16). Both track master until Roudio goes live.
  const source = github("elivatepro/record-by-boko", {
    branch: "master",
    rootDirectory: "services/backend",
  });
  const build = { builder: "DOCKERFILE", dockerfilePath: "Dockerfile" } as const;

  const env = {
    ENVIRONMENT: ctx.environmentName,
    // The web app's origin, so browsers may call the API (Railway resolves ${{…}}).
    CORS_ORIGINS: "https://${{web.RAILWAY_PUBLIC_DOMAIN}}",
    DATABASE_URL: db.env.DATABASE_URL,
    SESSION_SECRET: ctx.shared.SESSION_SECRET,
    DRIVE_TOKEN_KEY: ctx.shared.DRIVE_TOKEN_KEY,
    OWNER_EMAIL: ctx.shared.OWNER_EMAIL,
    PLAUD_API_BASE: ctx.shared.PLAUD_API_BASE,
  };

  const api = service("api", {
    source,
    build,
    start: "roudio-api",
    preDeploy: "alembic upgrade head",
    healthcheck: "/health",
    deploy: { restartPolicyType: "ON_FAILURE" },
    env,
  });

  const worker = service("worker", {
    source,
    build,
    start: "roudio-worker",
    deploy: { restartPolicyType: "ALWAYS" },
    env,
  });

  // Static web app (decision 15), built from the repo root because it's a pnpm workspace.
  const web = service("web", {
    source: github("elivatepro/record-by-boko", { branch: "master", rootDirectory: "/" }),
    build: { builder: "DOCKERFILE", dockerfilePath: "apps/web/Dockerfile" },
    healthcheck: "/",
    deploy: { restartPolicyType: "ON_FAILURE" },
    env: {
      // Public values, baked into the bundle at build time.
      VITE_API_URL: "https://${{api.RAILWAY_PUBLIC_DOMAIN}}",
      VITE_GOOGLE_CLIENT_ID: ctx.shared.GOOGLE_WEB_CLIENT_ID,
    },
  });

  return project("roudio", { resources: [db, audio, api, worker, web] });
});
