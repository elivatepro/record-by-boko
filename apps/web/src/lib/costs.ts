// The cost dashboard's words (milestone 7): Scribe hours, OpenAI tokens, stored audio.

import type { CostLine } from "../api/types";

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
