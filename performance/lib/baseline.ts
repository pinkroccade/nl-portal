/** De meetwaarden van één GraphQL-operation in een run of in de baseline. */
export interface Meting {
  p95Ms: number;
  /** Percentage mislukte verzoeken (0–100). */
  foutpercentage: number;
  doorvoerPerSeconde: number;
}

export type Metingen = Record<string, Meting>;

interface K6Export {
  metrics: Record<string, Record<string, unknown>>;
}

const rond = (waarde: number, decimalen: number) => Math.round(waarde * 10 ** decimalen) / 10 ** decimalen;

function getal(metrics: K6Export["metrics"], metric: string, veld: string, operatie: string): number {
  const waarde = metrics[`${metric}{name:${operatie}}`]?.[veld];
  if (typeof waarde !== "number") {
    throw new Error(
      `Geen ${metric}.${veld} voor operation ${operatie} in de k6-export; staat er een threshold op ${metric}{name:${operatie}}?`,
    );
  }
  return waarde;
}

/** Leest uit een `k6 run --summary-export`-bestand de meetwaarden per GraphQL-operation (de `name`-tag). */
export function samenvatting(k6Export: K6Export, operaties: readonly string[]): Metingen {
  const { metrics } = k6Export;
  return Object.fromEntries(
    operaties.map((operatie) => [
      operatie,
      {
        p95Ms: rond(getal(metrics, "http_req_duration", "p(95)", operatie), 1),
        foutpercentage: rond(getal(metrics, "http_req_failed", "value", operatie) * 100, 2),
        doorvoerPerSeconde: rond(getal(metrics, "http_reqs", "rate", operatie), 2),
      },
    ]),
  );
}

/** Drempels voor een waarschuwing: ruim boven de ruis van GitHub-hosted runners (±20–30%, ADR 0018). */
export const DREMPELS = { p95StijgingProcent: 30, doorvoerDalingProcent: 30, foutStijgingProcentpunt: 1 } as const;

export interface Vergelijking {
  operatie: string;
  baseline: Meting;
  huidig?: Meting;
  p95DeltaProcent?: number;
  doorvoerDeltaProcent?: number;
  foutDeltaProcentpunt?: number;
  waarschuwingen: string[];
}

function mediaan(waarden: number[]): number {
  const gesorteerd = [...waarden].sort((a, b) => a - b);
  const midden = Math.floor(gesorteerd.length / 2);
  return gesorteerd.length % 2 === 1 ? gesorteerd[midden]! : (gesorteerd[midden - 1]! + gesorteerd[midden]!) / 2;
}

const deltaProcent = (huidig: number, basis: number) => (basis === 0 ? 0 : Math.round(((huidig - basis) / basis) * 100));

/**
 * Vergelijkt runs met de baseline: per operation de mediaan van elke meetwaarde over de runs, de delta en
 * waarschuwingen bij een verslechtering boven [DREMPELS]. Signalerend: de uitkomst laat niets falen.
 */
export function vergelijk(baseline: Metingen, runs: Metingen[]): Vergelijking[] {
  return Object.entries(baseline).map(([operatie, basis]) => {
    const metingen = runs.map((run) => run[operatie]).filter((meting): meting is Meting => meting !== undefined);
    if (metingen.length === 0) {
      return { operatie, baseline: basis, waarschuwingen: ["ontbreekt in de run"] };
    }
    const huidig: Meting = {
      p95Ms: mediaan(metingen.map((m) => m.p95Ms)),
      foutpercentage: mediaan(metingen.map((m) => m.foutpercentage)),
      doorvoerPerSeconde: mediaan(metingen.map((m) => m.doorvoerPerSeconde)),
    };
    const p95DeltaProcent = deltaProcent(huidig.p95Ms, basis.p95Ms);
    const doorvoerDeltaProcent = deltaProcent(huidig.doorvoerPerSeconde, basis.doorvoerPerSeconde);
    const foutDeltaProcentpunt = rond(huidig.foutpercentage - basis.foutpercentage, 2);
    const waarschuwingen: string[] = [];
    if (p95DeltaProcent > DREMPELS.p95StijgingProcent) waarschuwingen.push(`p95 +${p95DeltaProcent}%`);
    if (-doorvoerDeltaProcent > DREMPELS.doorvoerDalingProcent) waarschuwingen.push(`doorvoer ${doorvoerDeltaProcent}%`);
    if (foutDeltaProcentpunt > DREMPELS.foutStijgingProcentpunt) waarschuwingen.push(`fouten +${foutDeltaProcentpunt} procentpunt`);
    return { operatie, baseline: basis, huidig, p95DeltaProcent, doorvoerDeltaProcent, foutDeltaProcentpunt, waarschuwingen };
  });
}

/** Een run met meer dan dit foutpercentage bij een operation is mislukt (stack weg, backend stuk), geen meting. */
export const ONGELDIG_FOUTPERCENTAGE = 5;

/** Indices van runs waarin een operation boven [ONGELDIG_FOUTPERCENTAGE] zit: die horen niet in een mediaan. */
export function ongeldigeRuns(runs: Metingen[]): number[] {
  return runs.flatMap((run, index) =>
    Object.values(run).some((meting) => meting.foutpercentage > ONGELDIG_FOUTPERCENTAGE) ? [index] : [],
  );
}
