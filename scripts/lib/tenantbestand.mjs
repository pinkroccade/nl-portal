// Leest de tenantbestanden (`base.yaml` + `<oin>.yaml`, zie backend/schema/tenant.schema.json) voor de dev-scripts.
// Het repo heeft geen root-`package.json` en dus geen YAML-dependency; dit is een parser voor precies de vorm van het
// tenantbestand: `sleutel: waarde`, geneste mappen, lijsten (blok en `[a, b]`), commentaar en gequote tekst. Alles daarbuiten
// geeft een fout. De echte validatie (schema en regels) blijft die van de tenantvalidator in `backend/core`; deze lezer
// controleert alleen wat het genereren nodig heeft. Samenvoegen als in `TenantFileLoader`: mappen diep, lijsten vervangen.
// Zusterbestand: capabilities-yaml.mjs leest een andere deelverzameling met dezelfde reden; ze delen bewust geen code (andere vormen).
// `voegTenantSamen` (lijsten vervangen, als `TenantFileLoader`) en `voegSamen` in realm.mjs (lijsten achter elkaar) hebben bewust tegengestelde lijstregels.

import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

function waarde(ruw, regel) {
  const s = ruw.trim();
  if (s.startsWith("&") || s.startsWith("*")) throw new Error(`Regel ${regel}: alias of anker in YAML wordt niet ondersteund`);
  if (s.startsWith("{") || s.startsWith("|") || s.startsWith(">") || s.startsWith("!")) throw new Error(`Regel ${regel}: YAML-vorm buiten het tenantbestand`);
  if (s.startsWith("[")) {
    if (!s.endsWith("]")) throw new Error(`Regel ${regel}: lijst niet afgesloten`);
    const binnen = s.slice(1, -1).trim();
    return binnen === "" ? [] : binnen.split(",").map((deel) => waarde(deel, regel));
  }
  if (s.startsWith('"') || s.startsWith("'")) {
    if (s.length < 2 || !s.endsWith(s[0])) throw new Error(`Regel ${regel}: tekst niet afgesloten`);
    return s.slice(1, -1);
  }
  if (s === "true") return true;
  if (s === "false") return false;
  if (/^-?\d+$/.test(s)) return Number(s);
  return s;
}

/** Haalt een commentaar aan het eind van de regel weg, buiten aanhalingstekens. */
function zonderCommentaar(regel) {
  let quote = null;
  for (let i = 0; i < regel.length; i++) {
    const c = regel[i];
    if (quote) {
      if (c === quote) quote = null;
    } else if (c === '"' || c === "'") quote = c;
    else if (c === "#" && (i === 0 || /\s/.test(regel[i - 1]))) return regel.slice(0, i);
  }
  return regel;
}

export function parseTenantYaml(tekst) {
  const regels = tekst
    .split(/\r?\n/)
    .map((ruw, i) => ({ ruw, nummer: i + 1 }))
    .filter(({ ruw }) => zonderCommentaar(ruw).trim() !== "")
    .map(({ ruw, nummer }) => {
      const zonder = zonderCommentaar(ruw).replace(/\s+$/, "");
      if (/^\s*\t/.test(zonder)) throw new Error(`Regel ${nummer}: tab als inspringing is niet toegestaan`);
      return { inspringing: zonder.length - zonder.trimStart().length, tekst: zonder.trim(), nummer };
    });
  let positie = 0;

  function blok(inspringing) {
    const eerste = regels[positie];
    return eerste.tekst.startsWith("- ") || eerste.tekst === "-" ? lijst(inspringing) : map(inspringing);
  }

  function lijst(inspringing) {
    const uit = [];
    while (positie < regels.length && regels[positie].inspringing === inspringing && regels[positie].tekst.startsWith("-")) {
      const { tekst, nummer } = regels[positie++];
      const rest = tekst.slice(1).trim();
      if (rest === "" || /^[^"'\s][^"']*:\s*(.*)$/.test(rest) && !rest.startsWith("[")) throw new Error(`Regel ${nummer}: lijsten van mappen horen niet in een tenantbestand`);
      uit.push(waarde(rest, nummer));
    }
    return uit;
  }

  function map(inspringing) {
    const uit = {};
    while (positie < regels.length && regels[positie].inspringing === inspringing) {
      const { tekst, nummer } = regels[positie++];
      const m = /^([A-Za-z0-9_.-]+):(?:\s+(.*))?$/.exec(tekst);
      if (!m) throw new Error(`Regel ${nummer}: verwacht 'sleutel: waarde'`);
      if (Object.hasOwn(uit, m[1])) throw new Error(`Regel ${nummer}: dubbele sleutel ${m[1]}`);
      if (m[2] === undefined || m[2] === "") {
        const volgende = regels[positie];
        uit[m[1]] = volgende && volgende.inspringing > inspringing ? blok(volgende.inspringing) : null;
      } else {
        uit[m[1]] = waarde(m[2], nummer);
      }
    }
    if (positie < regels.length && regels[positie].inspringing > inspringing) throw new Error(`Regel ${regels[positie].nummer}: onverwachte inspringing`);
    return uit;
  }

  if (regels.length === 0) return {};
  const uit = blok(regels[0].inspringing);
  if (positie < regels.length) throw new Error(`Regel ${regels[positie].nummer}: onverwachte inhoud`);
  return uit;
}

const isMap = (x) => x !== null && typeof x === "object" && !Array.isArray(x);

/** Mappen diep samenvoegen, al het andere (ook lijsten) vervangt de basis. */
export function voegTenantSamen(basis, tenant) {
  const uit = { ...basis };
  for (const [sleutel, w] of Object.entries(tenant)) uit[sleutel] = isMap(w) && isMap(uit[sleutel]) ? voegTenantSamen(uit[sleutel], w) : w;
  return uit;
}

/** Leest alle tenants uit een map, elk samengevoegd met `base.yaml`. */
export function leesTenantbestanden(map) {
  if (!statSync(map).isDirectory()) throw new Error(`${map} is geen map`);
  const bestanden = readdirSync(map).filter((naam) => naam.endsWith(".yaml")).sort();
  const basis = bestanden.includes("base.yaml") ? parseTenantYaml(readFileSync(join(map, "base.yaml"), "utf8")) : {};
  const tenants = bestanden
    .filter((naam) => naam !== "base.yaml")
    .map((naam) => {
      const tenant = voegTenantSamen(basis, parseTenantYaml(readFileSync(join(map, naam), "utf8")));
      if (`${tenant.oin}.yaml` !== naam) throw new Error(`${naam}: de bestandsnaam moet het OIN zijn (${tenant.oin}.yaml)`);
      return tenant;
    });
  if (tenants.length === 0) throw new Error(`Geen tenantbestanden in ${map}`);
  return tenants;
}
