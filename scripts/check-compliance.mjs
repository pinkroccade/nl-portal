#!/usr/bin/env node
// Guard: site/compliance/normatieve-bronnen.md is het register van normatieve bronnen (ASVS, RFC's, ...) met een
// traceerbaarheidsmatrix bron -> eis -> guardrail of check -> bewijs.
// Faalt op (1) een register-ID dat ergens in site/, backend/ (.kt) of .github/workflows genoemd wordt maar niet
// bestaat, (2) een matrixrij met onbekend bron-ID, onbekende status of ontbrekende velden, (3) een bron zonder
// enige check, afwijkingsbesluit of getoetst principe, (4) een bron zonder versie, datum of wijze van raadplegen,
// (5) een principe in site/architectuur/principes.md zonder bron-ID of zonder toetsing van een bekende soort, of met
// een reviewpunt dat niet naar CODING_STANDARDS.md verwijst, een toetsing zonder verwijzing, of een verwijzing naar
// een testklasse, mise-taak, workflow, bestand of CODING_STANDARDS-anker dat niet bestaat, (6) een principe-ID dat
// elders genoemd wordt maar niet in het principeregister staat, (7) een principekop zonder geldig P-nn-ID, een dubbel
// principe of veld, of een ontbrekend principeregister. Een bron met een Open matrixrij wordt niet gedekt door alleen
// een getoetst principe. Zie ADR 0023.
// Run via `mise run compliance:check`; CI draait het ook.
// Usage: node scripts/check-compliance.mjs [--root <repo-root>]

import { existsSync, readFileSync, readdirSync } from "node:fs";
import { join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export const REGISTER_PATH = "site/compliance/normatieve-bronnen.md";
export const PRINCIPLES_PATH = "site/architectuur/principes.md";

// Een ID heeft minstens twee segmenten (N-<BRON>-<...>), zodat woorden als N-SNAPSHOT geen ID zijn; het eindigt nooit op een punt.
const ID_SOURCE = String.raw`N-[A-Z][A-Z0-9]*(?:-[A-Z0-9]+(?:\.[0-9]+)*)+`;
const ID_PATTERN = new RegExp(String.raw`\b${ID_SOURCE}`, "g");
const ID_EXACT = new RegExp(`^${ID_SOURCE}$`);
const SKIP_DIRS = new Set(["node_modules", "build", "dist", "cache", ".git", ".gradle", ".vitepress"]);

const COVERING = new Set(["Afgedwongen", "Getoetst"]);
const STATUSES = new Set([...COVERING, "Bewust niet toegepast", "Open"]);

const isEmpty = (cell) => cell === undefined || cell.trim() === "" || cell.trim() === "-";
const PLACEHOLDERS = new Set(["nvt", "n.v.t.", "volgt", "tbd", "geen", "-"]);
const isPlaceholder = (text) => PLACEHOLDERS.has(text.trim().toLowerCase().replace(/\.$/, "")) || text.trim() === "";
const stripTicks =(cell) => cell.trim().replace(/^`|`$/g, "");

function tableRows(markdown, heading) {
  const section = markdown.split(/^## /m).find((part) => part.startsWith(heading));
  if (!section) return [];
  return section
    .split(/\r?\n/)
    .filter((line) => line.trim().startsWith("|"))
    .map((line) =>
      line
        .trim()
        .replace(/^\||\|$/g, "")
        .split("|")
        .map((cell) => cell.trim()),
    )
    .filter((cells) => !cells.every((cell) => /^:?-+:?$/.test(cell)))
    .slice(1); // koprij
}

// Een toetsing is een ";"-gescheiden lijst "<soort>: <verwijzing>". Een reviewpunt verwijst naar CODING_STANDARDS.md,
// zodat een principe zonder automatische toetsing een expliciet reviewpunt heeft en nooit niets.
const PRINCIPLE_ID_PATTERN = /\bP-\d{2}\b/g;
export const TOETSING_SOORTEN =["ArchUnit-regel", "Fitness test", "CI-controle", "Lintregel", "Reviewpunt"];

// Een principe is een kop "### P-<nn> <naam>" met velden "**<Veld>:** <tekst>" eronder.
function principleSections(markdown) {
  const register = markdown.split(/^## /m).find((part) => part.startsWith("Register"));
  if (!register) return [];
  const sections = [];
  let section;
  let current;
  let inFence = false;
  // Een veld loopt door tot de volgende lege regel of het volgende veld; proza daarna hoort nergens bij.
  for (const line of register.split(/\r?\n/).slice(1)) {
    if (/^\s*(```|~~~)/.test(line)) inFence = !inFence;
    const heading = inFence ? null : line.match(/^### (.*)$/);
    if (heading) {
      const text = heading[1].trim();
      section = { id: text.match(/^(P-\d{2})(?:\s|$)/)?.[1], heading: text, fields: new Map(), duplicates: [] };
      sections.push(section);
      current = undefined;
      continue;
    }
    if (!section) continue;
    const field = inFence ? null : line.match(/^\*\*([^*:]+):\*\*(.*)$/);
    if (field) {
      current = field[1].trim();
      if (section.fields.has(current)) section.duplicates.push(current);
      else section.fields.set(current, field[2].trim());
    } else if (line.trim() === "") {
      current = undefined;
    } else if (current && !section.duplicates.includes(current)) {
      section.fields.set(current, `${section.fields.get(current)} ${line.trim()}`.trim());
    }
  }
  return sections;
}

const escapeRegExp = (text) => text.replace(/[.*+?^${}()|[\]\\]/g, String.raw`\$&`);
const readOptional = (path) => (existsSync(path) ? readFileSync(path, "utf8") : "");
// GitHub-/VitePress-slug van een kop: kleine letters, alleen letters, cijfers en koppeltekens, spaties worden koppeltekens.
const slug = (heading) => heading.trim().toLowerCase().replace(/[^\p{L}\p{N} _-]/gu, "").replace(/ /g, "-");

// Geeft per verwijzing in een toetsing (een `...` of een CODING_STANDARDS-anker) terug wat niet bestaat. Een testklasse
// of ArchUnit-regel moet in backend/**/*.kt voorkomen, een mise-taak in mise.toml of mise-tasks/, een workflow in
// .github/workflows, een pad op schijf en een anker als kop in CODING_STANDARDS.md. Overige tekst tussen backticks
// (bijvoorbeeld een pluginnaam) is geen verwijzing die we kunnen controleren.
function missingReferences(root, item, cache) {
  const missing = [];
  for (const [, token] of item.matchAll(/`([^`]+)`/g)) {
    const task = token.match(/^mise run (\S+)/)?.[1];
    if (task) {
      cache.mise ??= readOptional(join(root, "mise.toml"));
      const defined = new RegExp(String.raw`^\[tasks\.("?)${escapeRegExp(task)}\1\]`, "m").test(cache.mise);
      if (!defined && !existsSync(join(root, "mise-tasks", ...task.split(":")))) missing.push(token);
    } else if (/\.ya?ml$/.test(token)) {
      if (!existsSync(join(root, ".github", "workflows", token))) missing.push(token);
    } else if (token.includes("/") && !token.includes(" ")) {
      if (!existsSync(join(root, token))) missing.push(token);
    } else if (/^[A-Z]\w*(\.\w+)?$/.test(token)) {
      cache.kotlin ??= [...walk(join(root, "backend"), [".kt"])].map((file) => readFileSync(file, "utf8")).join("\n");
      if (!new RegExp(String.raw`\b${escapeRegExp(token.split(".")[0])}\b`).test(cache.kotlin)) missing.push(token);
    }
  }
  for (const [, anchor] of item.matchAll(/CODING_STANDARDS\.md#([\w-]+)/g)) {
    cache.anchors ??= new Set(
      readOptional(join(root, "CODING_STANDARDS.md"))
        .split(/\r?\n/)
        .map((line) => line.match(/^#{1,6} (.*)$/)?.[1])
        .filter(Boolean)
        .map(slug),
    );
    if (!cache.anchors.has(anchor)) missing.push(`CODING_STANDARDS.md#${anchor}`);
  }
  return missing;
}

// Controleert elk principe op bron en toetsing. Een register-bron waarnaar een principe met geldige toetsing
// verwijst telt als gedekt: de keten bron -> principe -> toetsing is naast de matrix een tweede dekkingspad.
function checkPrinciples(root, problems, sources) {
  let markdown;
  try {
    markdown = readFileSync(join(root, PRINCIPLES_PATH), "utf8");
  } catch (error) {
    if (error.code === "ENOENT") throw new Error(`${PRINCIPLES_PATH} ontbreekt`);
    throw error;
  }
  const sections = principleSections(markdown);
  const seen = new Set();
  const cache = {};
  for (const { id, heading, fields, duplicates } of sections) {
    if (!id) {
      problems.push(`${PRINCIPLES_PATH}: kop "### ${heading}" heeft geen geldig principe-ID (P-nn)`);
      continue;
    }
    if (seen.has(id)) {
      problems.push(`${PRINCIPLES_PATH}: principe ${id} komt meer dan eens voor`);
      continue;
    }
    seen.add(id);
    const where = `${PRINCIPLES_PATH}: principe ${id}`;
    const before = problems.length;
    for (const name of new Set(duplicates)) problems.push(`${where} heeft het veld ${name} meer dan eens`);
    const bron = fields.get("Bron");
    const bronIds = isEmpty(bron) ? [] : (bron.match(ID_PATTERN) ?? []);
    if (isEmpty(bron)) problems.push(`${where} heeft geen bron`);
    else if (bronIds.length === 0) problems.push(`${where} noemt geen bron-ID uit ${REGISTER_PATH}`);
    const toetsing = fields.get("Toetsing");
    if (isEmpty(toetsing)) {
      problems.push(`${where} heeft geen toetsing`);
      continue;
    }
    for (const item of toetsing.split(";").map((part) => part.trim())) {
      const soort = TOETSING_SOORTEN.find((candidate) => item.startsWith(`${candidate}:`));
      if (!soort) {
        problems.push(`${where} heeft een toetsing zonder soort (${TOETSING_SOORTEN.join(", ")})`);
      } else if (isPlaceholder(item.slice(soort.length + 1))) {
        problems.push(`${where} heeft een toetsing zonder verwijzing (${soort})`);
      } else if (soort === "Reviewpunt" && !item.includes("CODING_STANDARDS.md")) {
        problems.push(`${where} heeft een reviewpunt zonder verwijzing naar CODING_STANDARDS.md`);
      } else {
        const missing = missingReferences(root, item, cache);
        if (missing.length > 0) problems.push(`${where} verwijst naar iets dat niet bestaat: ${missing.join(", ")}`);
      }
    }
    if (problems.length === before) {
      for (const bronId of bronIds) {
        // Een bron met een Open matrixrij heeft een bekend gat; een principe dekt dat niet af.
        const source = sources.get(bronId);
        if (source && !source.covered && !source.open) Object.assign(source, { covered: true, viaPrincipe: true });
      }
    }
  }
  return seen;
}

function* walk(dir, extensions) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.isDirectory()) {
      if (!SKIP_DIRS.has(entry.name)) yield* walk(join(dir, entry.name), extensions);
    } else if (extensions.some((ext) => entry.name.endsWith(ext))) {
      yield join(dir, entry.name);
    }
  }
}

function scanTargets(root) {
  const targets = [];
  for (const [dir, extensions] of [
    ["site", [".md"]],
    ["backend", [".kt"]],
    [".github/workflows", [".yml", ".yaml"]],
  ]) {
    try {
      targets.push(...walk(join(root, dir), extensions));
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
    }
  }
  return targets;
}

export function checkCompliance(root) {
  let markdown;
  try {
    markdown = readFileSync(join(root, REGISTER_PATH), "utf8");
  } catch (error) {
    if (error.code === "ENOENT") throw new Error(`${REGISTER_PATH} ontbreekt`);
    throw error;
  }
  const problems = [];

  const sources = new Map();
  for (const [rawId, , version, consulted, how] of tableRows(markdown, "Register")) {
    const id = stripTicks(rawId);
    if (!ID_EXACT.test(id)) {
      problems.push(`${REGISTER_PATH}: "${id}" is geen geldig bron-ID`);
      continue;
    }
    if (sources.has(id)) problems.push(`${REGISTER_PATH}: bron-ID ${id} komt meer dan eens voor`);
    sources.set(id, { covered: false });
    for (const [value, label] of [
      [version, "versie"],
      [consulted, "geraadpleegd op"],
      [how, "wijze van raadplegen"],
    ]) {
      if (isEmpty(value)) problems.push(`${REGISTER_PATH}: bron ${id} heeft geen ${label}`);
    }
  }

  for (const [rawId, eis, status, guardrail, evidence, decision] of tableRows(markdown, "Traceerbaarheidsmatrix")) {
    const id = stripTicks(rawId);
    const where = `${REGISTER_PATH}: matrixrij ${id} / ${eis}`;
    const source = sources.get(id);
    if (!source) {
      problems.push(`${where} verwijst naar een onbekend bron-ID`);
      continue;
    }
    if (!STATUSES.has(status)) {
      problems.push(`${where} heeft onbekende status "${status}" (toegestaan: ${[...STATUSES].join(", ")})`);
      continue;
    }
    if (COVERING.has(status)) {
      if (isEmpty(guardrail) || isEmpty(evidence)) {
        problems.push(`${where} is ${status} maar noemt geen guardrail of check en bewijs`);
        continue;
      }
      source.covered = true;
    } else if (status === "Bewust niet toegepast") {
      if (isEmpty(decision)) {
        problems.push(`${where} is bewust niet toegepast zonder besluit`);
        continue;
      }
      source.covered = true;
    } else {
      source.open = true;
      if (isEmpty(decision)) problems.push(`${where} is Open zonder verwijzing naar een issue`);
    }
  }

  const principles = checkPrinciples(root, problems, sources);

  for (const [id, { covered }] of sources) {
    if (!covered) {
      problems.push(`${REGISTER_PATH}: bron ${id} heeft geen check of afwijkingsbesluit in de matrix of een getoetst principe`);
    }
  }

  const registerFile = join(root, REGISTER_PATH);
  const principlesFile = join(root, PRINCIPLES_PATH);
  for (const file of scanTargets(root)) {
    if (file === registerFile) continue;
    const content = readFileSync(file, "utf8");
    const where = relative(root, file).replaceAll("\\", "/");
    for (const id of new Set(content.match(ID_PATTERN) ?? [])) {
      if (!sources.has(id)) problems.push(`${where}: noemt ${id}, dat niet in het register staat`);
    }
    if (file === principlesFile) continue;
    for (const id of new Set(content.match(PRINCIPLE_ID_PATTERN) ?? [])) {
      if (!principles.has(id)) problems.push(`${where}: noemt principe ${id}, dat niet in ${PRINCIPLES_PATH} staat`);
    }
  }

  const viaPrincipe = [...sources].filter(([, source]) => source.viaPrincipe).map(([id]) => id);
  return { sources: [...sources.keys()], viaPrincipe, problems };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const rootIndex = process.argv.indexOf("--root");
  const root = rootIndex >= 0 ? resolve(process.argv[rootIndex + 1]) : resolve(fileURLToPath(new URL("..", import.meta.url)));
  try {
    const { sources, viaPrincipe, problems } = checkCompliance(root);
    if (problems.length > 0) {
      console.error(`Compliance-register klopt niet:\n- ${problems.join("\n- ")}`);
      process.exit(1);
    }
    console.log(`Compliance-register consistent: ${sources.length} bron(nen)`);
    if (viaPrincipe.length > 0) {
      console.log(`Alleen gedekt via een getoetst principe, niet via de matrix: ${viaPrincipe.join(", ")}`);
    }
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}
