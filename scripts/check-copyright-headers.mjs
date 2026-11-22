#!/usr/bin/env node
// Guard (ADR 0015): nieuwe bronbestanden dragen de PinkRoccade-kop; in een gewijzigd, verplaatst of gekopieerd
// bestand blijft de bestaande kop gelijk. Spotless en ESLint accepteren beide koppen en vangen dit dus niet.
// Een PinkRoccade-kop mag wel een ander (eind)jaar krijgen; een Ritense-kop blijft byte-gelijk, jaartal incluis.
// Toetst op de diff t.o.v. de merge-base met --base (standaard $COPYRIGHT_BASE_REF, anders origin/main), inclusief
// niet-gecommitte en nieuwe bestanden. Draai via `mise run copyright:check`; `mise run lint` en CI doen dat ook.
// Usage: node scripts/check-copyright-headers.mjs [--root <repo-root>] [--base <ref>]

import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const SOURCE = /\.(kt|kts|java|ts|tsx|js|jsx)$/;
const BACKEND_OR_KEYCLOAK = /^(backend|keycloak\/provider)\//;
const FRONTEND = /^frontend\/packages\//;
const EXCLUDED = /(^|\/)(node_modules|dist|build|\.turbo|coverage|generated)\/|\.d\.ts$|\.generated\.ts$/;

export function inScope(path) {
  return SOURCE.test(path) && (BACKEND_OR_KEYCLOAK.test(path) || FRONTEND.test(path)) && !EXCLUDED.test(path);
}

const LEADING_COMMENT = /^[\uFEFF\s]*\/\*[\s\S]*?\*\//;
const header = (content) => LEADING_COMMENT.exec(content.replace(/\r\n/g, "\n"))?.[0].trim() ?? null;
const PINK = /Copyright (?:\d{4}(?:-\d{4})?) PinkRoccade Local Government B\.V\./;
const PINK_YEAR = /(Copyright )\d{4}(?:-\d{4})?( PinkRoccade)/;
const isPink = (h) => h !== null && PINK.test(h);
const firstCopyright = (h) => /Copyright [^\n]*/.exec(h)?.[0] ?? "(geen copyright-regel)";

export function checkCopyrightHeaders(entries) {
  const problems = [];
  for (const { status, path, before, after } of entries) {
    if (!inScope(path)) continue;
    const now = header(after);
    if (status === "A") {
      if (!isPink(now)) {
        problems.push(
          `${path}: nieuw bestand mist de PinkRoccade-kop; verwacht "Copyright <jaar> PinkRoccade Local Government B.V." ` +
            "(sjabloon backend/licenses/pinkroccade.template, ADR 0015)",
        );
      }
      continue;
    }
    const was = header(before);
    if (was === null || !/Copyright/.test(was)) continue;
    const unchanged = isPink(was)
      ? now !== null && now.replace(PINK_YEAR, "$1<jaar>$2") === was.replace(PINK_YEAR, "$1<jaar>$2")
      : now === was;
    if (!unchanged) {
      problems.push(
        `${path}: de bestaande kop is gewijzigd; verwacht de oorspronkelijke kop "${firstCopyright(was)}" byte-gelijk ` +
          (isPink(was) ? "(alleen het jaartal van PinkRoccade mag verschuiven) " : "(ook het jaartal blijft staan) ") +
          "(ADR 0015)",
      );
    }
  }
  return problems;
}

const git = (root, ...args) => execFileSync("git", args, { cwd: root, encoding: "utf8", maxBuffer: 1 << 28 });

function collect(root, base) {
  const mergeBase = git(root, "merge-base", base, "HEAD").trim();
  const entries = [];
  const read = (path) => (existsSync(join(root, path)) ? readFileSync(join(root, path), "utf8") : null);
  const show = (path) => git(root, "show", `${mergeBase}:${path}`);
  const fields = git(root, "diff", "-M", "-C", "--name-status", "-z", mergeBase).split("\0");
  for (let i = 0; i < fields.length && fields[i]; ) {
    const status = fields[i][0];
    if (status === "R" || status === "C") {
      const [from, to] = [fields[i + 1], fields[i + 2]];
      i += 3;
      if (inScope(to) && read(to) !== null) entries.push({ status, path: to, before: show(from), after: read(to) });
    } else {
      const path = fields[i + 1];
      i += 2;
      if (status === "D" || !inScope(path) || read(path) === null) continue;
      entries.push({ status, path, before: status === "A" ? null : show(path), after: read(path) });
    }
  }
  for (const path of git(root, "ls-files", "--others", "--exclude-standard", "-z").split("\0")) {
    if (path && inScope(path)) entries.push({ status: "A", path, after: read(path) });
  }
  return entries;
}

function main() {
  const args = process.argv.slice(2);
  const option = (name) => (args.includes(name) ? args[args.indexOf(name) + 1] : undefined);
  const root = resolve(option("--root") ?? fileURLToPath(new URL("..", import.meta.url)));
  const base = option("--base") ?? process.env.COPYRIGHT_BASE_REF ?? "origin/main";
  const entries = collect(root, base);
  const problems = checkCopyrightHeaders(entries);
  if (problems.length > 0) {
    console.error(`Copyright-koppen (ADR 0015): ${problems.length} afwijking(en) t.o.v. ${base}:\n- ${problems.join("\n- ")}`);
    process.exit(1);
  }
  console.log(`Copyright-koppen in orde (${entries.length} gewijzigde bestanden getoetst t.o.v. ${base}).`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
