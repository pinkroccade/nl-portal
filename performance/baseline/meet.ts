// Draait de journeys PERF_RUNS keer (standaard 3) met profiel PERF_PROFILE (standaard load) en bewaart per run de
// k6-summary in performance/resultaten/ (gitignored). Daarna `vastleggen.ts` of `vergelijken.ts` (zie README).
import { execFileSync } from "node:child_process";
import { mkdirSync, readdirSync, rmSync } from "node:fs";
import { fileURLToPath } from "node:url";

const resultaten = fileURLToPath(new URL("../resultaten/", import.meta.url));
const journeys = fileURLToPath(new URL("../scenarios/journeys.ts", import.meta.url));
const profiel = process.env.PERF_PROFILE ?? "load";
const runs = Number(process.env.PERF_RUNS ?? "3");

rmSync(resultaten, { recursive: true, force: true });
mkdirSync(resultaten, { recursive: true });
for (let run = 1; run <= runs; run++) {
  const exportBestand = `${resultaten}${profiel}-${run}.json`;
  console.log(`Run ${run} van ${runs} (profiel ${profiel})`);
  try {
    execFileSync("k6", ["run", "--quiet", `--summary-export=${exportBestand}`, journeys], {
      stdio: "inherit",
      env: { ...process.env, PERF_PROFILE: profiel },
    });
  } catch {
    // Een gemiste threshold geeft exitcode 99; de summary staat er dan wel. Signalerend, dus doorgaan.
    console.warn(`Run ${run}: k6 eindigde met een fout of een gemiste threshold`);
  }
}
console.log(`Summaries: ${readdirSync(resultaten).join(", ")}`);
