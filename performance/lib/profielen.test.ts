import { test } from "node:test";
import assert from "node:assert/strict";
import { kiesProfiel } from "./profielen.ts";

test("smoke is 1 gebruiker met 1 iteratie", () => {
  const opties = kiesProfiel("smoke", {});
  assert.equal(opties.vus, 1);
  assert.equal(opties.iterations, 1);
});

test("average load loopt op naar het piekaantal (standaard 10), houdt vast en loopt af", () => {
  const stages = kiesProfiel("load", {}).stages ?? [];
  assert.deepEqual(
    stages.map((stage) => stage.target),
    [10, 10, 0],
  );
});

test("het piekaantal van load is instelbaar met PERF_PEAK_VUS", () => {
  const stages = kiesProfiel("load", { PERF_PEAK_VUS: "25" }).stages ?? [];
  assert.equal(Math.max(...stages.map((stage) => stage.target)), 25);
});

test("stress loopt in stappen op tot het piekaantal (standaard 100)", () => {
  const targets = (kiesProfiel("stress", {}).stages ?? []).map((stage) => stage.target);
  assert.equal(Math.max(...targets), 100);
  assert.equal(targets.at(-1), 0);
  assert.ok(targets.length > 3, "stress heeft meerdere stappen");
});

test("een onbekend profiel is een fout met de geldige namen", () => {
  assert.throws(() => kiesProfiel("chaos", {}), /smoke, load, stress/);
});

test("een ongeldig piekaantal is een fout", () => {
  assert.throws(() => kiesProfiel("load", { PERF_PEAK_VUS: "veel" }), /PERF_PEAK_VUS/);
});

test("elk profiel begrenst de latency per GraphQL-operation en meet fouten", () => {
  for (const naam of ["smoke", "load", "stress"]) {
    const thresholds = kiesProfiel(naam, {}).thresholds ?? {};
    assert.ok(thresholds["http_req_failed"], `${naam}: http_req_failed`);
    for (const operatie of ["getZaken", "getZaak", "getTakenV2", "getPersoonV2"]) {
      assert.ok(thresholds[`http_req_duration{name:${operatie}}`], `${naam}: ${operatie}`);
    }
  }
});

test("stap houdt een vast aantal gebruikers vast (PERF_PEAK_VUS) gedurende 90 seconden", () => {
  const opties = kiesProfiel("stap", { PERF_PEAK_VUS: "20" });
  assert.equal(opties.vus, 20);
  assert.equal(opties.duration, "90s");
  assert.equal(opties.stages, undefined);
});

test("de duur van een stap is instelbaar met PERF_STAP_SECONDEN", () => {
  assert.equal(kiesProfiel("stap", { PERF_PEAK_VUS: "20", PERF_STAP_SECONDEN: "45" }).duration, "45s");
});

test("PERF_STAP_SECONDEN moet een geheel getal van minimaal 1 zijn", () => {
  assert.throws(() => kiesProfiel("stap", { PERF_STAP_SECONDEN: "snel" }), /PERF_STAP_SECONDEN/);
});
