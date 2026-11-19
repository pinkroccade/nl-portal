import { test } from "node:test";
import assert from "node:assert/strict";
import { omslagpunten } from "./omslagpunt.ts";
import type { Meting } from "./baseline.ts";

const meting = (p95Ms: number, foutpercentage = 0): Meting => ({ p95Ms, foutpercentage, doorvoerPerSeconde: 5 });

test("het omslagpunt is de eerste stap waar de p95 boven 2 s komt; de stap daarvoor is de hoogste goede", () => {
  const stappen = [
    { vus: 5, metingen: { getZaken: meting(300) } },
    { vus: 10, metingen: { getZaken: meting(500) } },
    { vus: 20, metingen: { getZaken: meting(1500) } },
    { vus: 40, metingen: { getZaken: meting(2600) } },
  ];
  assert.deepEqual(omslagpunten(stappen), [
    { operatie: "getZaken", omslagVus: 40, redenen: ["p95"], hoogsteGoedeVus: 20 },
  ]);
});

test("een stap op precies 2000 ms of 1% fouten is nog goed; erboven slaat de operation om", () => {
  const opDeGrens = [{ vus: 10, metingen: { getZaken: meting(2000, 1) } }];
  const erboven = [{ vus: 10, metingen: { getZaken: meting(2000.1, 1.01) } }];
  assert.equal(omslagpunten(opDeGrens)[0]?.omslagVus, undefined);
  assert.deepEqual(omslagpunten(erboven)[0]?.redenen, ["p95", "fouten"]);
});

test("fouten kunnen een operation laten omslaan terwijl de p95 nog goed is", () => {
  const stappen = [
    { vus: 10, metingen: { getTakenV2: meting(50, 0) } },
    { vus: 20, metingen: { getTakenV2: meting(60, 3) } },
  ];
  assert.deepEqual(omslagpunten(stappen), [
    { operatie: "getTakenV2", omslagVus: 20, redenen: ["fouten"], hoogsteGoedeVus: 10 },
  ]);
});

test("een operation die nooit omslaat heeft geen omslagpunt en de hoogste stap als hoogste goede", () => {
  const stappen = [
    { vus: 10, metingen: { getPersoonV2: meting(30) } },
    { vus: 40, metingen: { getPersoonV2: meting(45) } },
  ];
  assert.deepEqual(omslagpunten(stappen), [{ operatie: "getPersoonV2", hoogsteGoedeVus: 40, redenen: [] }]);
});

test("slaat een operation al bij de laagste stap om, dan is er geen goede stap", () => {
  const [punt] = omslagpunten([{ vus: 5, metingen: { getZaak: meting(3000) } }]);
  assert.equal(punt?.omslagVus, 5);
  assert.equal(punt?.hoogsteGoedeVus, undefined);
});

test("de stappen hoeven niet op volgorde te staan", () => {
  const stappen = [
    { vus: 40, metingen: { getZaken: meting(2600) } },
    { vus: 10, metingen: { getZaken: meting(500) } },
    { vus: 20, metingen: { getZaken: meting(1500) } },
  ];
  assert.deepEqual(omslagpunten(stappen)[0], { operatie: "getZaken", omslagVus: 40, redenen: ["p95"], hoogsteGoedeVus: 20 });
});

test("de drempels zijn aan te passen", () => {
  const stappen = [{ vus: 10, metingen: { getZaken: meting(900) } }];
  assert.equal(omslagpunten(stappen, { p95Ms: 800, foutpercentage: 1 })[0]?.omslagVus, 10);
});
