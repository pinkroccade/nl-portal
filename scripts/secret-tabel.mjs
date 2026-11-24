#!/usr/bin/env node
// Secret-keys-tabel (#196): de tabel in site/hosting-en-configuratie/kubernetes/referentie-secret-keys.md wordt gegenereerd
// uit de chart, niet met de hand bijgehouden. Bronnen: de sleutelnamen en hun beschrijving (`x-secret`: nodig, betekenis) uit
// helm/nl-portal/values.schema.json, de standaardnamen uit het secretKeys-blok van values.yaml, en de template die controleert
// dat elke sleutel echt onder die env-naam gebruikt wordt. Een ontbrekende beschrijving, standaardwaarde of template-verwijzing
// faalt. `node scripts/secret-tabel.mjs` schrijft (mise run helm:secret-tabel); `--check` faalt bij een afwijkende tabel
// (mise run helm:secret-tabel-check).
import { readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const SCHEMA = "helm/nl-portal/values.schema.json";
const VALUES = "helm/nl-portal/values.yaml";
const TEMPLATE = "helm/nl-portal/templates/deployment-backend.yaml";
const DOC = "site/hosting-en-configuratie/kubernetes/referentie-secret-keys.md";

export const START = "<!-- secret-tabel:start (gegenereerd door scripts/secret-tabel.mjs; niet met de hand wijzigen) -->";
export const EINDE = "<!-- secret-tabel:einde -->";

/** Standaard sleutelnamen uit het `secretKeys:`-blok van values.yaml (onder `backend:`). */
export function leesStandaardsleutels(valuesText) {
  const regels = valuesText.split(/\r?\n/);
  const start = regels.findIndex((r) => /^ {2}secretKeys:\s*$/.test(r));
  if (start < 0) throw new Error("values.yaml bevat geen backend.secretKeys-blok");
  const sleutels = {};
  for (const regel of regels.slice(start + 1)) {
    if (/^ {0,2}\S/.test(regel)) break;
    const m = /^ {4}([A-Za-z0-9]+):\s*(\S+)\s*$/.exec(regel);
    if (m) sleutels[m[1]] = m[2];
  }
  return sleutels;
}

/** Rijen in schemavolgorde; faalt als schema, values.yaml en template elkaar tegenspreken. */
export function bouwRijen(schema, standaard, template) {
  const blok = schema.properties.backend.properties.secretKeys;
  const rijen = [];
  for (const [naam, def] of Object.entries(blok.properties)) {
    const info = def["x-secret"];
    if (!info?.nodig || !info?.betekenis) throw new Error(`secretKeys.${naam} mist een x-secret-beschrijving (nodig, betekenis) in het schema`);
    const key = standaard[naam];
    if (!key) throw new Error(`secretKeys.${naam} heeft geen standaardwaarde in values.yaml`);
    const gebruik = new RegExp(
      String.raw`- name: ${key}\s*\n\s+valueFrom:\s*\n\s+secretKeyRef:\s*\n(?:\s+(?:name|optional): [^\n]*\n)*\s+key: \{\{ \$b\.secretKeys\.${naam} \}\}`,
    );
    if (!gebruik.test(template)) throw new Error(`secretKeys.${naam}: de template gebruikt hem niet als env ${key}`);
    rijen.push({ key, nodig: info.nodig, betekenis: info.betekenis });
  }
  for (const naam of Object.keys(standaard)) {
    if (!(naam in blok.properties)) throw new Error(`values.yaml heeft secretKeys.${naam}, maar het schema niet`);
  }
  return [...rijen, ...(blok["x-extraRijen"] ?? [])];
}

export function renderTabel(rijen) {
  const kop = "| Key (standaardnaam) | Nodig wanneer | Betekenis |\n|---|---|---|";
  return [kop, ...rijen.map((r) => `| \`${r.key}\` | ${r.nodig} | ${r.betekenis} |`)].join("\n");
}

function grenzen(md) {
  const a = md.indexOf(START);
  const b = md.indexOf(EINDE);
  if (a < 0 || b < a) throw new Error(`het document mist de marker ${START} / ${EINDE}`);
  return [a + START.length, b];
}

export function vervangTabel(md, tabel) {
  const [a, b] = grenzen(md);
  return `${md.slice(0, a)}\n${tabel}\n${md.slice(b)}`;
}

/** null als de tabel in het document klopt, anders een melding. */
export function controleer(md, rijen) {
  return vervangTabel(md, renderTabel(rijen)) === md
    ? null
    : `${DOC} is niet actueel met ${SCHEMA}. Draai 'mise run helm:secret-tabel' en commit het resultaat.`;
}

function main() {
  const lees = (p) => readFileSync(resolve(ROOT, p), "utf8");
  const rijen = bouwRijen(JSON.parse(lees(SCHEMA)), leesStandaardsleutels(lees(VALUES)), lees(TEMPLATE));
  const md = lees(DOC);
  if (process.argv.includes("--check")) {
    const melding = controleer(md, rijen);
    if (melding) {
      console.error(melding);
      process.exit(1);
    }
    console.log("De secret-keys-tabel is actueel.");
  } else {
    writeFileSync(resolve(ROOT, DOC), vervangTabel(md, renderTabel(rijen)));
    console.log(`${DOC} bijgewerkt.`);
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    main();
  } catch (e) {
    console.error(e.message);
    process.exit(1);
  }
}
