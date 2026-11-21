#!/usr/bin/env node
// Guard: site/compliance/normatieve-bronnen.md is het register van normatieve bronnen (ASVS, RFC's, ...) met een
// traceerbaarheidsmatrix bron -> eis -> guardrail of check -> bewijs.
// Faalt op (1) een register-ID dat ergens in site/, backend/ (.kt) of .github/workflows genoemd wordt maar niet
// bestaat, (2) een matrixrij met onbekend bron-ID, onbekende status of ontbrekende velden, (3) een bron zonder
// enige check, afwijkingsbesluit of getoetst principe, (4) een bron zonder versie, datum of wijze van raadplegen,
// (5) een principe in site/architectuur/principes.md zonder bron-ID of zonder toetsing van een bekende soort, of met
// een reviewpunt dat niet naar CODING_STANDARDS.md verwijst, (6) een principe-ID dat elders genoemd wordt maar niet
// in het principeregister staat. Zie ADR 0023.
// Run via `mise run compliance:check`; CI draait het ook.
// Usage: node scripts/check-compliance.mjs [--root <repo-root>]

import { readFileSync, readdirSync } from "node:fs";
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
const stripTicks = (cell) => cell.trim().replace(/^`|`$/g, "");

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
const TOETSING_SOORTEN = ["ArchUnit-regel", "Fitness test", "CI-controle", "Lintregel", "Reviewpunt"];

// Een principe is een kop "### P-<nn> <naam>" met velden "**<Veld>:** <tekst>" eronder.
function principleSections(markdown) {
  return markdown
    .split(/^### /m)
    .slice(1)
    .map((section) => {
      const id = section.match(/^(P-\d{2})\b/)?.[1];
      const fields = new Map();
      let current;
      for (const line of section.split(/\r?\n/).slice(1)) {
        const field = line.match(/^\*\*([^*:]+):\*\*(.*)$/);
        if (field) {
          current = field[1].trim();
          fields.set(current, field[2].trim());
        } else if (current) {
          fields.set(current, `${fields.get(current)} ${line.trim()}`.trim());
        }
      }
      return { id, fields };
    })
    .filter(({ id }) => id);
}

// Controleert elk principe op bron en toetsing. Een register-bron waarnaar een principe met geldige toetsing
// verwijst telt als gedekt: de keten bron -> principe -> toetsing is naast de matrix een tweede dekkingspad.
function checkPrinciples(root, problems, sources) {
  let markdown;
  try {
    markdown = readFileSync(join(root, PRINCIPLES_PATH), "utf8");
  } catch (error) {
    if (error.code === "ENOENT") return null;
    throw error;
  }
  const sections = principleSections(markdown);
  for (const { id, fields } of sections) {
    const where = `${PRINCIPLES_PATH}: principe ${id}`;
    const before = problems.length;
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
      } else if (soort === "Reviewpunt" && !item.includes("CODING_STANDARDS.md")) {
        problems.push(`${where} heeft een reviewpunt zonder verwijzing naar CODING_STANDARDS.md`);
      }
    }
    if (problems.length === before) {
      for (const bronId of bronIds) {
        if (sources.has(bronId)) sources.get(bronId).covered = true;
      }
    }
  }
  return new Set(sections.map(({ id }) => id));
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
    } else if (isEmpty(decision)) {
      problems.push(`${where} is Open zonder verwijzing naar een issue`);
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
    if (principles === null || file === principlesFile) continue;
    for (const id of new Set(content.match(PRINCIPLE_ID_PATTERN) ?? [])) {
      if (!principles.has(id)) problems.push(`${where}: noemt principe ${id}, dat niet in ${PRINCIPLES_PATH} staat`);
    }
  }

  return { sources: [...sources.keys()], problems };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const rootIndex = process.argv.indexOf("--root");
  const root = rootIndex >= 0 ? resolve(process.argv[rootIndex + 1]) : resolve(fileURLToPath(new URL("..", import.meta.url)));
  try {
    const { sources, problems } = checkCompliance(root);
    if (problems.length > 0) {
      console.error(`Compliance-register klopt niet:\n- ${problems.join("\n- ")}`);
      process.exit(1);
    }
    console.log(`Compliance-register consistent: ${sources.length} bron(nen)`);
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}
