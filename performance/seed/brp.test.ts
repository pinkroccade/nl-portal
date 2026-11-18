import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { BRP_TESTDATA_GEBRUIKERS, brpTestdataBestand, maakBrpTestdata } from "./brp.ts";
import { maakPlan } from "./plan.ts";

test("maakt per seed-gebruiker een persoon met het BSN en een voorspelbare naam", () => {
  const personen = maakBrpTestdata(maakPlan({ gebruikers: 2, zakenPerGebruiker: 0, takenPerGebruiker: 0, documentenPerZaak: 0 }).gebruikers);
  assert.equal(personen.length, 2);
  assert.equal(personen[0]?.burgerservicenummer, "900000004");
  assert.equal(personen[0]?.naam.geslachtsnaam, "burger-001");
  assert.equal(personen[1]?.naam.geslachtsnaam, "burger-002");
});

test("het BRP-testdatabestand in de perf-overlay dekt de seed-gebruikers en is niet verouderd", () => {
  const verwacht = maakBrpTestdata(
    maakPlan({ gebruikers: BRP_TESTDATA_GEBRUIKERS, zakenPerGebruiker: 0, takenPerGebruiker: 0, documentenPerZaak: 0 }).gebruikers,
  );
  assert.deepEqual(JSON.parse(readFileSync(brpTestdataBestand, "utf8")), verwacht);
});
