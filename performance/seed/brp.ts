import { writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { maakPlan, type PlanGebruiker } from "./plan.ts";

/** Voor zoveel seed-gebruikers (burger-001..) staat een persoon in het BRP-testdatabestand. */
export const BRP_TESTDATA_GEBRUIKERS = 100;

/** Vervangt `/app/Data/test-data.json` in de personen-mock van de perf-overlay (`docker-compose/compose.perf.yaml`). */
export const brpTestdataBestand = fileURLToPath(
  new URL("../../docker-compose/imports/haalcentraal-perf/test-data.json", import.meta.url),
);

/** Een minimale BRP-persoon per seed-gebruiker, zodat de profiel/BRP-journey een BSN uit de seed kan opzoeken. */
export function maakBrpTestdata(gebruikers: PlanGebruiker[]) {
  return gebruikers.map(({ username, bsn }, index) => ({
    aNummer: String(1000000000 + index + 1),
    burgerservicenummer: bsn,
    datumEersteInschrijvingGBA: "20000101",
    geslacht: { code: "M", omschrijving: "man" },
    naam: {
      aanduidingNaamgebruik: { code: "E", omschrijving: "eigen geslachtsnaam" },
      voornamen: "Perf",
      geslachtsnaam: username,
    },
    nationaliteiten: [
      {
        datumIngangGeldigheid: "20000101",
        nationaliteit: { code: "0001", omschrijving: "Nederlandse" },
        redenOpname: { code: "001", omschrijving: "Wet op het Nederlanderschap 1892, art.1, lid 1a" },
      },
    ],
    geboorte: {
      land: { code: "6030", omschrijving: "Nederland" },
      plaats: { code: "0153", omschrijving: "Enschede" },
      datum: "19800101",
    },
    verblijfplaats: {
      functieAdres: { code: "W", omschrijving: "woonadres" },
      datumIngangGeldigheid: "20000101",
      datumAanvangAdreshouding: "20000101",
      straat: "Perfstraat",
      huisnummer: String(index + 1),
      postcode: "7511AG",
    },
    immigratie: {},
    gemeenteVanInschrijving: { code: "0153", omschrijving: "Enschede" },
    datumInschrijvingInGemeente: "20000101",
    gezag: [],
    kinderen: [],
    ouders: [],
    partners: [],
  }));
}

// `node performance/seed/brp.ts` (mise run perf:brp-data) herschrijft het bestand.
if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const { gebruikers } = maakPlan({ gebruikers: BRP_TESTDATA_GEBRUIKERS, zakenPerGebruiker: 0, takenPerGebruiker: 0, documentenPerZaak: 0 });
  writeFileSync(brpTestdataBestand, `${JSON.stringify(maakBrpTestdata(gebruikers), null, 2)}\n`);
}
