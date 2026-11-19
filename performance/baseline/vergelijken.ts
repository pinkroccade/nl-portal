// Vergelijkt de runs uit `meet.ts` met de baseline en schrijft de delta als Markdown-tabel naar stdout en, in
// GitHub Actions, naar de workflow-summary. Een verslechtering wordt een `::warning::`; de exitcode blijft 0
// (signalerend, ADR 0018).
import { appendFileSync, readFileSync } from "node:fs";
import { DREMPELS, ongeldigeRuns, vergelijk, type Vergelijking } from "../lib/baseline.ts";
import { baselineBestand, leesRuns } from "./leesRuns.ts";

const profiel = process.env.PERF_PROFILE ?? "load";
const baseline = JSON.parse(readFileSync(baselineBestand(profiel), "utf8"));
const alleRuns = leesRuns(profiel);
const ongeldig = ongeldigeRuns(alleRuns);
const runs = alleRuns.filter((_, index) => !ongeldig.includes(index));
const rijen = vergelijk(baseline.operaties, runs);

const delta = (waarde: number | undefined, eenheid: string) =>
  waarde === undefined ? "–" : `${waarde > 0 ? "+" : ""}${waarde}${eenheid}`;
const rij = (r: Vergelijking) =>
  `| ${r.operatie} | ${r.baseline.p95Ms} | ${r.huidig?.p95Ms ?? "–"} | ${delta(r.p95DeltaProcent, "%")} | ${r.huidig?.foutpercentage ?? "–"}% | ${delta(r.foutDeltaProcentpunt, " pp")} | ${r.huidig?.doorvoerPerSeconde ?? "–"} | ${delta(r.doorvoerDeltaProcent, "%")} | ${r.waarschuwingen.length ? `⚠️ ${r.waarschuwingen.join(", ")}` : "ok"} |`;

const markdown = [
  `## Performance t.o.v. baseline (${profiel})`,
  "",
  `Baseline van ${baseline.gemetenOp} (${baseline.omgeving}, ${baseline.runs} runs). Mediaan van ${runs.length} runs. Waarschuwing bij p95 > +${DREMPELS.p95StijgingProcent}%, doorvoer < -${DREMPELS.doorvoerDalingProcent}% of fouten > +${DREMPELS.foutStijgingProcentpunt} procentpunt.`,
  "",
  "| Operation | p95 baseline (ms) | p95 nu (ms) | Δ p95 | Fouten nu | Δ fouten | Doorvoer nu (/s) | Δ doorvoer | Signaal |",
  "|---|---|---|---|---|---|---|---|---|",
  ...rijen.map(rij),
  "",
].join("\n");

const runsMelding = ongeldig.length > 0 ? `Run ${ongeldig.map((i) => i + 1).join(", ")} van ${alleRuns.length} is mislukt (te veel fouten) en telt niet mee.` : "";
console.log(markdown);
if (process.env.GITHUB_STEP_SUMMARY) {
  appendFileSync(process.env.GITHUB_STEP_SUMMARY, markdown);
}
if (runsMelding) {
  console.log(`::warning title=Performance-run mislukt::${runsMelding}`);
  if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, `> ⚠️ ${runsMelding}
`);
}
for (const r of rijen.filter((r) => r.waarschuwingen.length > 0)) {
  console.log(`::warning title=Performance ${r.operatie}::${r.waarschuwingen.join(", ")} t.o.v. de baseline`);
}
