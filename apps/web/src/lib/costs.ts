// The Usage page's words (milestone 7): what Roudio uses ElevenLabs and OpenAI for, and what it costs.

import type { CostLine, PurposeLine } from "../api/types";

export function usd(amount: number): string {
  if (amount > 0 && amount < 0.01) return "under $0.01";
  return `$${amount.toFixed(2)}`;
}

/** "3.2 h of speech sent", "1.4M tokens", with the service's plain name. */
export function costLine(line: CostLine): { name: string; amount: string } {
  if (line.service === "scribe") {
    const hours = line.unit === "seconds" ? line.units / 3600 : line.units;
    return { name: "Speech to text (Scribe)", amount: `${hours.toFixed(1)} h sent` };
  }
  if (line.service === "openai") {
    return { name: "Notes, digest and Ask (OpenAI)", amount: `${compact(line.units)} tokens` };
  }
  return { name: line.service, amount: `${compact(line.units)} ${line.unit}` };
}

export function bytes(n: number): string {
  if (n >= 1e9) return `${(n / 1e9).toFixed(1)} GB`;
  if (n >= 1e6) return `${Math.round(n / 1e6)} MB`;
  return `${Math.max(1, Math.round(n / 1e3))} KB`;
}

function compact(n: number): string {
  if (n >= 1e6) return `${(n / 1e6).toFixed(1)}M`;
  if (n >= 1e3) return `${Math.round(n / 1e3)}k`;
  return `${Math.round(n)}`;
}

const PURPOSES: Record<string, string> = {
  transcribe: "Speech to text",
  tidy: "Tidying transcripts",
  understand: "Notes, titles and tasks",
  digest: "Daily digests",
  ask: "Ask",
  web_search: "Ask · web searches",
  other: "Earlier, before usage was labelled",
};

/** "Speech to text · 0.4 h", "Ask · 31k tokens", "Ask · web searches · 2". */
export function purposeLine(line: PurposeLine): { name: string; amount: string } {
  const name = PURPOSES[line.purpose] ?? line.purpose;
  if (line.unit === "seconds") return { name, amount: `${(line.units / 3600).toFixed(1)} h of speech` };
  if (line.unit === "searches") return { name, amount: `${Math.round(line.units)} search${line.units === 1 ? "" : "es"}` };
  return { name, amount: `${compact(line.units)} ${line.unit}` };
}

/** The service's own name for a purpose line. */
export function provider(service: string): string {
  return service === "scribe" ? "ElevenLabs" : service === "openai" ? "OpenAI" : service;
}

export function percent(used: number, limit: number): number {
  return limit > 0 ? Math.min(100, Math.round((used / limit) * 100)) : 0;
}

export function grouped(n: number): string {
  return Math.round(n).toLocaleString("en-GB");
}
