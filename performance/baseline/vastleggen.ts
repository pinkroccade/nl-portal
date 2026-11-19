// Legt de mediaan van de runs uit `meet.ts` vast als baseline (performance/baseline/<profiel>.json). De baseline
// verandert alleen via een PR (ADR 0018); vermeld in die PR waarom en op welke machine is gemeten.
import { writeFileSync } from "node:fs";
import { ongeldigeRuns, vergelijk } from "../lib/baseline.ts";
import { baselineBestand, leesRuns } from "./leesRuns.ts";

const profiel = process.env.PERF_PROFILE ?? "load";
const runs = leesRuns(profiel);
const ongeldig = ongeldigeRuns(runs);
if (ongeldig.length > 0) {
  console.error(`Run ${ongeldig.map((i) => i + 1).join(", ")} is mislukt (te veel fouten); geen baseline vastgelegd. Controleer de stack en draai perf:meet opnieuw.`);
  process.exit(1);
}
// Vergelijken met de eerste run levert per operation de mediaan over alle runs (`huidig`).
const operaties = Object.fromEntries(vergelijk(runs[0]!, runs).map(({ operatie, huidig }) => [operatie, huidig]));
const baseline = {
  profiel,
  piekVus: Number(process.env.PERF_PEAK_VUS ?? (profiel === "stress" ? 100 : 10)),
  runs: runs.length,
  gemetenOp: new Date().toISOString().slice(0, 10),
  omgeving: process.env.PERF_OMGEVING ?? "lokaal",
  operaties,
};
writeFileSync(baselineBestand(profiel), `${JSON.stringify(baseline, null, 2)}\n`);
console.log(`Baseline vastgelegd in ${baselineBestand(profiel)}`);
console.table(operaties);
