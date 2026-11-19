// Stelt het omslagpunt per GraphQL-operation vast: draait de journeys met profiel `stap` voor elk aantal
// gelijktijdige gebruikers uit PERF_STAPPEN (standaard 5,10,15,20,30,40,60,80), elk als eigen k6-run, en legt de
// metingen en het omslagpunt vast in performance/baseline/omslagpunt.json. Zie performance/README.md.
import { execFileSync } from "node:child_process";
import { mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { samenvatting } from "../lib/baseline.ts";
import { omslagpunten, STANDAARD_DREMPELS, type Stap } from "../lib/omslagpunt.ts";
import { OPERATIES } from "../lib/thresholds.ts";

const resultaten = fileURLToPath(new URL("../resultaten/", import.meta.url));
const journeys = fileURLToPath(new URL("../scenarios/journeys.ts", import.meta.url));
const uitvoer = fileURLToPath(new URL("./omslagpunt.json", import.meta.url));

const vusPerStap = (process.env.PERF_STAPPEN ?? "5,10,15,20,30,40,60,80").split(",").map((waarde) => Number(waarde.trim()));
if (vusPerStap.some((vus) => !Number.isInteger(vus) || vus < 1)) {
  throw new Error(`PERF_STAPPEN moet kommagescheiden gehele getallen van minimaal 1 zijn, kreeg ${process.env.PERF_STAPPEN}`);
}

rmSync(resultaten, { recursive: true, force: true });
mkdirSync(resultaten, { recursive: true });

const stappen: Stap[] = [];
for (const vus of vusPerStap) {
  const exportBestand = `${resultaten}stap-${vus}.json`;
  console.log(`Stap ${vusPerStap.indexOf(vus) + 1} van ${vusPerStap.length}: ${vus} gelijktijdige gebruikers`);
  try {
    execFileSync("k6", ["run", "--quiet", `--summary-export=${exportBestand}`, journeys], {
      stdio: "inherit",
      env: { ...process.env, PERF_PROFILE: "stap", PERF_PEAK_VUS: String(vus) },
    });
  } catch {
    // Een gemiste threshold geeft exitcode 99; bij het omslagpunt is dat juist de uitkomst. De summary staat er wel.
  }
  stappen.push({ vus, metingen: samenvatting(JSON.parse(readFileSync(exportBestand, "utf8")), OPERATIES) });
}

const resultaat = {
  gemetenOp: new Date().toISOString().slice(0, 10),
  omgeving: process.env.PERF_OMGEVING ?? "lokaal",
  stapSeconden: Number(process.env.PERF_STAP_SECONDEN ?? 90),
  seedGebruikers: Number(process.env.PERF_USERS ?? 10),
  drempels: STANDAARD_DREMPELS,
  stappen,
  omslagpunten: omslagpunten(stappen),
};
writeFileSync(uitvoer, `${JSON.stringify(resultaat, null, 2)}\n`);
console.log(`Omslagpunten vastgelegd in ${uitvoer}`);
console.table(resultaat.omslagpunten);
