import { readdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { samenvatting, type Metingen } from "../lib/baseline.ts";
import { OPERATIES } from "../lib/thresholds.ts";

export const resultatenMap = fileURLToPath(new URL("../resultaten/", import.meta.url));

export const baselineBestand = (profiel: string) => fileURLToPath(new URL(`./${profiel}.json`, import.meta.url));

/** De meetwaarden per run uit de k6-summaries van `meet.ts` voor dit profiel. */
export function leesRuns(profiel: string): Metingen[] {
  const bestanden = readdirSync(resultatenMap).filter((naam) => naam.startsWith(`${profiel}-`) && naam.endsWith(".json"));
  if (bestanden.length === 0) {
    throw new Error(`Geen k6-summaries voor profiel ${profiel} in ${resultatenMap}; draai eerst mise run perf:meet`);
  }
  return bestanden.map((naam) => samenvatting(JSON.parse(readFileSync(`${resultatenMap}${naam}`, "utf8")), OPERATIES));
}
