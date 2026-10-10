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

  return project("roudio", { resources: [db, audio, api, worker] });
});
