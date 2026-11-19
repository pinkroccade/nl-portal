import type { Options, Stage } from "k6/options";
import { OPERATIES, journeyThresholds } from "./thresholds.ts";

type Env = Record<string, string | undefined>;

const PROFIELEN = ["smoke", "load", "stress", "stap"] as const;
export type ProfielNaam = (typeof PROFIELEN)[number];

function piek(env: Env, standaard: number): number {
  const waarde = Number(env.PERF_PEAK_VUS ?? standaard);
  if (!Number.isInteger(waarde) || waarde < 1) {
    throw new Error(`PERF_PEAK_VUS moet een geheel getal van minimaal 1 zijn, kreeg ${env.PERF_PEAK_VUS}`);
  }
  return waarde;
}

// Average load: normaal verwacht gebruik. Het piekaantal is het aantal gelijktijdige gebruikers.
function loadStages(peak: number): Stage[] {
  return [
    { duration: "1m", target: peak },
    { duration: "5m", target: peak },
    { duration: "30s", target: 0 },
  ];
}

// Stress: in stappen boven normaal gebruik, om te zien waar fouten of latency omslaan.
function stressStages(peak: number): Stage[] {
  const stap = (deel: number) => Math.max(1, Math.round(peak * deel));
  return [
    { duration: "1m", target: stap(0.25) },
    { duration: "1m", target: stap(0.5) },
    { duration: "1m", target: stap(0.75) },
    { duration: "2m", target: peak },
    { duration: "30s", target: 0 },
  ];
}

function stapSeconden(env: Env): number {
  const waarde = Number(env.PERF_STAP_SECONDEN ?? 90);
  if (!Number.isInteger(waarde) || waarde < 1) {
    throw new Error(`PERF_STAP_SECONDEN moet een geheel getal van minimaal 1 zijn, kreeg ${env.PERF_STAP_SECONDEN}`);
  }
  return waarde;
}

/**
 * k6-opties voor een profiel: smoke (1 gebruiker, 1 iteratie), load (gemiddelde belasting), stress (oplopend) of
 * stap (een vast aantal gebruikers, een vaste duur: één meetpunt voor het omslagpunt, zie `omslagpunt.ts`).
 */
export function kiesProfiel(naam: string, env: Env): Options {
  const thresholds = journeyThresholds(OPERATIES);
  switch (naam) {
    case "smoke":
      return { vus: 1, iterations: 1, thresholds };
    case "load":
      return { stages: loadStages(piek(env, 10)), thresholds };
    case "stress":
      return { stages: stressStages(piek(env, 100)), thresholds };
    case "stap":
      return { vus: piek(env, 10), duration: `${stapSeconden(env)}s`, thresholds };
    default:
      throw new Error(`Onbekend profiel "${naam}"; kies uit ${PROFIELEN.join(", ")}`);
  }
}
