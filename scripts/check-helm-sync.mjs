#!/usr/bin/env node
// Guard (#194): de Helm-chart loopt gelijk met de config-referentie van de app. Elke env-sleutel in
// docker-compose/imports/backend.env en frontend.env (actief of uitgecommentarieerd) moet voorkomen in de gerenderde
// chart (ConfigMap-data of container-env). Een app-property die de chart niet kent, geeft een rode build.
// Bewuste uitzonderingen staan in helm/sync-allowlist.json, elk met een reden (`key`, of `prefix` voor een
// groep). Een entry zonder reden of
// een verouderde entry (sleutel niet meer in de referentie, of de chart rendert hem inmiddels) faalt ook.
// De gerenderde chart komt via stdin binnen: `helm template x helm/nl-portal -f helm/nl-portal/ci/full-values.yaml | node
// scripts/check-helm-sync.mjs` (mise run helm:sync-check); full-values.yaml zet alle modules en opties aan.
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const REFERENCES = ["docker-compose/imports/backend.env", "docker-compose/imports/frontend.env"];
const ALLOWLIST = "helm/sync-allowlist.json";
const ENV_NAME = "[A-Z][A-Z0-9_]*";

/** Sleutels uit een .env-bestand: `KEY=` en `#KEY=` (optionele sleutel), geen prozacommentaar. */
export function referenceKeys(envText) {
  const keys = new Set();
  for (const line of envText.split(/\r?\n/)) {
    const m = new RegExp(`^#?(${ENV_NAME})=`).exec(line);
    if (m) keys.add(m[1]);
  }
  return keys;
}

/** Env-sleutels uit gerenderde YAML: UPPER_SNAKE-sleutels op ConfigMap-dataniveau en `- name: KEY` in container-env. */
export function chartKeys(yamlText) {
  const keys = new Set();
  for (const doc of yamlText.split(/^---\s*$/m)) {
    if (/^kind:\s*ConfigMap\s*$/m.test(doc)) {
      const data = doc.split(/^data:\s*$/m)[1] ?? "";
      for (const m of data.matchAll(new RegExp(`^  (${ENV_NAME}):`, "gm"))) keys.add(m[1]);
    }
    for (const m of doc.matchAll(new RegExp(`^\\s+- name:\\s*"?(${ENV_NAME})"?\\s*$`, "gm"))) keys.add(m[1]);
  }
  return keys;
}

/** Vergelijkt referentie en chart; `problemen` is leeg als alles klopt. */
export function vergelijk({ reference, chart, allowlist }) {
  const problemen = [];
  const toegestaan = new Set();
  const prefixen = [];
  for (const { key, prefix, reason } of allowlist) {
    const naam = key ?? prefix;
    if (!reason || !reason.trim()) problemen.push(`Allowlist-entry ${naam} heeft geen reden.`);
    else if (key !== undefined) {
      if (!reference.has(key)) problemen.push(`Allowlist-entry ${key} staat niet meer in de config-referentie: verwijder hem.`);
      else if (chart.has(key)) problemen.push(`Allowlist-entry ${key} wordt inmiddels door de chart gerenderd: verwijder hem.`);
      else toegestaan.add(key);
    } else if (![...reference].some((k) => k.startsWith(prefix) && !chart.has(k))) {
      problemen.push(`Allowlist-prefix ${prefix} dekt geen enkele sleutel meer die de chart mist: verwijder hem.`);
    } else prefixen.push(prefix);
  }
  const gedekt = (k) => toegestaan.has(k) || prefixen.some((x) => k.startsWith(x));
  const ontbreekt = [...reference].filter((k) => !chart.has(k) && !gedekt(k)).sort();
  for (const k of ontbreekt) {
    problemen.push(
      `${k} staat in de config-referentie maar wordt niet door de chart gerenderd. Voeg hem toe aan helm/nl-portal ` +
        `(templates, values.yaml, README) of zet hem met een reden in ${ALLOWLIST}.`,
    );
  }
  return { ontbreekt, problemen };
}

function main() {
  const reference = new Set(REFERENCES.flatMap((p) => [...referenceKeys(readFileSync(join(ROOT, p), "utf8"))]));
  const allowlist = JSON.parse(readFileSync(join(ROOT, ALLOWLIST), "utf8")).entries;
  const rendered = readFileSync(0, "utf8");
  const { problemen } = vergelijk({ reference, chart: chartKeys(rendered), allowlist });
  if (problemen.length > 0) {
    console.error(`Chart en app-config lopen niet gelijk:\n- ${problemen.join("\n- ")}`);
    process.exit(1);
  }
  console.log(`Chart en config-referentie lopen gelijk (${reference.size} sleutels, ${allowlist.length} uitzonderingen).`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
