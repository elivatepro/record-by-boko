// Highlighting: search snippets mark matches with [[ ]] (backend `ts_headline`), and find-in-
// transcript highlights a typed phrase. Both become plain parts the UI renders as text or <mark>.

export type TextPart = { text: string; match: boolean };

/** Split a snippet like "keep the [[provider]] list" into parts. Unclosed marks stay as text. */
export function snippetParts(snippet: string): TextPart[] {
  const parts: TextPart[] = [];
  let rest = snippet;
  while (rest.length > 0) {
    const open = rest.indexOf("[[");
    const close = open === -1 ? -1 : rest.indexOf("]]", open + 2);
    if (open === -1 || close === -1) {
      parts.push({ text: rest, match: false });
      break;
    }
    if (open > 0) parts.push({ text: rest.slice(0, open), match: false });
    const inner = rest.slice(open + 2, close);
    if (inner) parts.push({ text: inner, match: true });
    rest = rest.slice(close + 2);
  }
  return merge(parts);
}

/** Split text around every case-insensitive occurrence of `query`. */
export function highlightParts(text: string, query: string): TextPart[] {
  const q = query.trim().toLocaleLowerCase();
  if (!q) return [{ text, match: false }];
  const lower = text.toLocaleLowerCase();
  const parts: TextPart[] = [];
  let from = 0;
  for (let at = lower.indexOf(q); at !== -1; at = lower.indexOf(q, from)) {
    if (at > from) parts.push({ text: text.slice(from, at), match: false });
    parts.push({ text: text.slice(at, at + q.length), match: true });
    from = at + q.length;
  }
  if (from < text.length) parts.push({ text: text.slice(from), match: false });
  return parts;
}

/** Adjacent parts of the same kind joined (ts_headline marks each word separately). */
function merge(parts: TextPart[]): TextPart[] {
  const out: TextPart[] = [];
  for (const part of parts) {
    const last = out[out.length - 1];
    if (last && last.match === part.match) last.text += part.text;
    else out.push({ ...part });
  }
  // "[[provider]] [[shortlist]]" reads as one highlight.
  const joined: TextPart[] = [];
  for (let i = 0; i < out.length; i++) {
    const part = out[i]!;
    const prev = joined[joined.length - 1];
    const next = out[i + 1];
    if (!part.match && /^\s+$/.test(part.text) && prev?.match && next?.match) {
      prev.text += part.text + next.text;
      i++;
    } else {
      joined.push(part);
    }
  }
  return joined;
}

/** "Speaker 2" for a raw diarisation label like "speaker_1", when the owner hasn't named them. */
export function speakerName(label: string, name: string | null | undefined): string {
  if (name) return name;
  const n = /(\d+)$/.exec(label);
  return n ? `Speaker ${Number(n[1]) + 1}` : label;
}
