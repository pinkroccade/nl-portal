import { test } from "node:test";
import assert from "node:assert/strict";
import { ongeldigeRuns, samenvatting, vergelijk } from "./baseline.ts";

// Vorm van `k6 run --summary-export`: submetrics per `name`-tag bestaan alleen als er een threshold op staat.
const k6Export = {
  metrics: {
    "http_req_duration{name:getZaken}": { avg: 120.4, "p(90)": 180.2, "p(95)": 210.66, max: 400 },
    "http_req_failed{name:getZaken}": { passes: 2, fails: 198, value: 0.01 },
    "http_reqs{name:getZaken}": { count: 200, rate: 3.3333 },
    "http_req_duration{name:getZaak}": { avg: 300, "p(95)": 455.1 },
    "http_req_failed{name:getZaak}": { passes: 0, fails: 100, value: 0 },
    "http_reqs{name:getZaak}": { count: 100, rate: 1.66666 },
    http_req_duration: { avg: 200, "p(95)": 420 },
  },
};

test("de samenvatting geeft per GraphQL-operation p95 (ms), foutpercentage en doorvoer (verzoeken per seconde)", () => {
  assert.deepEqual(samenvatting(k6Export, ["getZaken", "getZaak"]), {
    getZaken: { p95Ms: 210.7, foutpercentage: 1, doorvoerPerSeconde: 3.33 },
    getZaak: { p95Ms: 455.1, foutpercentage: 0, doorvoerPerSeconde: 1.67 },
  });
});

test("een operation zonder submetrics in de export geeft een duidelijke fout", () => {
  assert.throws(() => samenvatting(k6Export, ["getTakenV2"]), /getTakenV2/);
});

const baseline = {
  getZaken: { p95Ms: 200, foutpercentage: 0, doorvoerPerSeconde: 10 },
};

test("de vergelijking neemt per operation de mediaan van de runs", () => {
  const runs = [
    { getZaken: { p95Ms: 260, foutpercentage: 0, doorvoerPerSeconde: 9 } },
    { getZaken: { p95Ms: 180, foutpercentage: 0.5, doorvoerPerSeconde: 12 } },
    { getZaken: { p95Ms: 210, foutpercentage: 0, doorvoerPerSeconde: 10 } },
  ];
  const [rij] = vergelijk(baseline, runs);
  assert.deepEqual(rij?.huidig, { p95Ms: 210, foutpercentage: 0, doorvoerPerSeconde: 10 });
  assert.equal(rij?.p95DeltaProcent, 5);
  assert.deepEqual(rij?.waarschuwingen, []);
});

test("een p95 die meer dan 30% stijgt geeft een waarschuwing, 30% zelf nog niet", () => {
  const rond30 = vergelijk(baseline, [{ getZaken: { p95Ms: 260, foutpercentage: 0, doorvoerPerSeconde: 10 } }]);
  const boven30 = vergelijk(baseline, [{ getZaken: { p95Ms: 262, foutpercentage: 0, doorvoerPerSeconde: 10 } }]);
  assert.deepEqual(rond30[0]?.waarschuwingen, []);
  assert.deepEqual(boven30[0]?.waarschuwingen, ["p95 +31%"]);
});

test("een daling van de doorvoer van meer dan 30% en een stijging van het foutpercentage van meer dan 1 procentpunt geven een waarschuwing", () => {
  const [rij] = vergelijk(baseline, [{ getZaken: { p95Ms: 200, foutpercentage: 1.5, doorvoerPerSeconde: 6 } }]);
  assert.deepEqual(rij?.waarschuwingen, ["doorvoer -40%", "fouten +1.5 procentpunt"]);
});

test("een operation die in de runs ontbreekt geeft een waarschuwing", () => {
  const [rij] = vergelijk(baseline, [{}]);
  assert.deepEqual(rij?.waarschuwingen, ["ontbreekt in de run"]);
});

test("een run waarin een operation vrijwel alleen fouten geeft is ongeldig en telt niet mee", () => {
  const goed = { getZaken: { p95Ms: 200, foutpercentage: 0, doorvoerPerSeconde: 10 } };
  const stackWeg = { getZaken: { p95Ms: 120, foutpercentage: 99.96, doorvoerPerSeconde: 4000 } };
  assert.deepEqual(ongeldigeRuns([goed, stackWeg, goed]), [1]);
  assert.deepEqual(ongeldigeRuns([goed, goed]), []);
});

test("een operation met een paar procent fouten is nog een geldige run", () => {
  const meteenFout = { getZaken: { p95Ms: 200, foutpercentage: 4.9, doorvoerPerSeconde: 10 } };
  assert.deepEqual(ongeldigeRuns([meteenFout]), []);
});
