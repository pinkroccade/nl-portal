import type { Meting, Metingen } from "./baseline.ts";

/** De meetwaarden van één run met een vast aantal gelijktijdige gebruikers (profiel `stap`). */
export interface Stap {
  vus: number;
  metingen: Metingen;
}

export interface Drempels {
  p95Ms: number;
  foutpercentage: number;
}

/** Dezelfde grenzen als de thresholds van de journeys: p95 onder 2 s en minder dan 1% fouten. */
export const STANDAARD_DREMPELS: Drempels = { p95Ms: 2000, foutpercentage: 1 };

export interface Omslagpunt {
  operatie: string;
  /** Eerste stap (aantal gelijktijdige gebruikers) waar een drempel wordt overschreden; ontbreekt als dat nooit gebeurt. */
  omslagVus?: number;
  /** Welke drempels bij `omslagVus` zijn overschreden: `p95` en/of `fouten`. */
  redenen: string[];
  /** Hoogste stap onder het omslagpunt (of de hoogste stap als de operation nooit omslaat); ontbreekt als de laagste stap al omslaat. */
  hoogsteGoedeVus?: number;
}

function overschrijdingen(meting: Meting, drempels: Drempels): string[] {
  const redenen: string[] = [];
  if (meting.p95Ms > drempels.p95Ms) redenen.push("p95");
  if (meting.foutpercentage > drempels.foutpercentage) redenen.push("fouten");
  return redenen;
}

/** Per operation het omslagpunt uit de runs van `perf:omslagpunt`: de stappen worden op aantal gebruikers gesorteerd. */
export function omslagpunten(stappen: Stap[], drempels: Drempels = STANDAARD_DREMPELS): Omslagpunt[] {
  const opVolgorde = [...stappen].sort((a, b) => a.vus - b.vus);
  const operaties = [...new Set(opVolgorde.flatMap((stap) => Object.keys(stap.metingen)))];
  return operaties.map((operatie) => {
    let hoogsteGoedeVus: number | undefined;
    for (const stap of opVolgorde) {
      const meting = stap.metingen[operatie];
      if (meting === undefined) continue;
      const redenen = overschrijdingen(meting, drempels);
      if (redenen.length > 0) {
        return {
          operatie,
          omslagVus: stap.vus,
          redenen,
          ...(hoogsteGoedeVus === undefined ? {} : { hoogsteGoedeVus }),
        };
      }
      hoogsteGoedeVus = stap.vus;
    }
    return { operatie, redenen: [], ...(hoogsteGoedeVus === undefined ? {} : { hoogsteGoedeVus }) };
  });
}
