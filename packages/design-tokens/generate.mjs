// Generates the Compose theme tokens and the web CSS variables from tokens.json.
//
//   node generate.mjs          write the generated files
//   node generate.mjs --check  fail if the generated files are out of date (CI)

import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, "..", "..");
const tokens = JSON.parse(readFileSync(join(here, "tokens.json"), "utf8"));

const HEADER = "Generated from packages/design-tokens/tokens.json by generate.mjs. Do not edit.";

const lexend = readFileSync(join(here, "fonts", "Lexend-Variable.ttf"));

const outputs = {
  "apps/web/src/styles/tokens.css": css(),
  "apps/web/public/fonts/Lexend-Variable.ttf": lexend,
  "apps/android/app/src/main/kotlin/com/boko/roudio/ui/theme/RoudioTokens.kt": kotlin(),
  "apps/android/app/src/main/res/font/lexend_variable.ttf": lexend,
};

function hex(name) {
  const entry = tokens.palette[name];
  if (!entry) throw new Error(`Unknown palette entry "${name}"`);
  if (!/^#[0-9A-F]{6}$/i.test(entry.value)) throw new Error(`Bad colour ${name}: ${entry.value}`);
  return entry.value.toUpperCase();
}

function camel(name) {
  return name.replace(/-([a-z])/g, (_, c) => c.toUpperCase());
}

function pascal(name) {
  const c = camel(name);
  return c[0].toUpperCase() + c.slice(1);
}

function scale() {
  return Object.entries(tokens.type).filter(([k]) => !k.startsWith("$"));
}

function css() {
  const roles = Object.entries(tokens.color);
  const lines = [
    `/* ${HEADER} */`,
    "",
    "@font-face {",
    `  font-family: "${tokens.font.sans}";`,
    '  src: url("/fonts/Lexend-Variable.ttf") format("truetype");',
    "  font-weight: 100 900;",
    "  font-display: swap;",
    "}",
    "",
    ":root {",
  ];
  for (const name of Object.keys(tokens.palette)) lines.push(`  --palette-${name}: ${hex(name)};`);
  lines.push("");
  for (const [role, { light }] of roles) lines.push(`  --color-${role}: var(--palette-${light});`);
  lines.push("");
  lines.push(`  --font-sans: "${tokens.font.sans}", system-ui, sans-serif;`);
  for (const [name, { size, lineHeight }] of scale()) {
    lines.push(`  --text-${name}: ${size}px;`);
    lines.push(`  --leading-${name}: ${lineHeight}px;`);
  }
  for (const [name, px] of Object.entries(tokens.radius)) lines.push(`  --radius-${name}: ${px}px;`);
  lines.push("  color-scheme: light;", "}", "");

  const dark = roles.map(([role, { dark }]) => `  --color-${role}: var(--palette-${dark});`);
  lines.push("@media (prefers-color-scheme: dark) {", "  :root:not([data-theme=\"light\"]) {");
  lines.push(...dark.map((l) => "  " + l), "    color-scheme: dark;", "  }", "}", "");
  lines.push(":root[data-theme=\"dark\"] {", ...dark, "  color-scheme: dark;", "}", "");
  return lines.join("\n");
}

function kotlin() {
  const roles = Object.entries(tokens.color);
  const argb = (name) => `Color(0xFF${hex(name).slice(1)})`;
  const lines = [
    `// ${HEADER}`,
    "package com.boko.roudio.ui.theme",
    "",
    "import androidx.compose.runtime.Immutable",
    "import androidx.compose.ui.graphics.Color",
    "import androidx.compose.ui.unit.dp",
    "import androidx.compose.ui.unit.sp",
    "",
    "/** Raw colours from the Paper file. Prefer [RoudioColors] roles in UI code. */",
    "object RoudioPalette {",
  ];
  for (const name of Object.keys(tokens.palette)) {
    lines.push(`    /** ${tokens.palette[name].description} */`);
    lines.push(`    val ${pascal(name)} = ${argb(name)}`);
  }
  lines.push("}", "", "/** Semantic colour roles. One instance each for light and dark. */", "@Immutable");
  lines.push("data class RoudioColors(");
  for (const [role] of roles) lines.push(`    val ${camel(role)}: Color,`);
  lines.push(")", "");
  for (const mode of ["light", "dark"]) {
    lines.push(`val ${pascal(mode)}RoudioColors = RoudioColors(`);
    for (const [role, map] of roles) lines.push(`    ${camel(role)} = RoudioPalette.${pascal(map[mode])},`);
    lines.push(")", "");
  }
  lines.push("/** Type scale: font size and line height in sp. */", "object RoudioTypeScale {");
  for (const [name, { size, lineHeight }] of scale()) {
    lines.push(`    val ${pascal(name)}Size = ${size}.sp`);
    lines.push(`    val ${pascal(name)}LineHeight = ${lineHeight}.sp`);
  }
  lines.push("}", "", "object RoudioRadius {");
  for (const [name, px] of Object.entries(tokens.radius)) lines.push(`    val ${pascal(name)} = ${px}.dp`);
  lines.push("}", "");
  return lines.join("\n");
}

const check = process.argv.includes("--check");
let stale = 0;
for (const [path, content] of Object.entries(outputs)) {
  const file = join(root, path);
  const current = existsSync(file) ? readFileSync(file) : null;
  if (current && current.equals(Buffer.from(content))) continue;
  if (check) {
    console.error(`Out of date: ${relative(root, file)}`);
    stale++;
  } else {
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, content);
    console.log(`Wrote ${relative(root, file)}`);
  }
}
if (stale) {
  console.error("Run `pnpm tokens` and commit the result.");
  process.exit(1);
}
