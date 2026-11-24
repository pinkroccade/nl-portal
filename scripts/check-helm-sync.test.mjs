import { test } from "node:test";
import assert from "node:assert/strict";
import { chartKeys, referenceKeys, vergelijk } from "./check-helm-sync.mjs";

test("referenceKeys leest actieve en uitgecommentarieerde sleutels, geen prozacommentaar", () => {
  const env = [
    "# NL Portal Backend Configuration",
    "# REDIS_SSL staat standaard aan; compose zet hem uit.",
    "KEYCLOAK_BROKER_URL=http://localhost:8082",
    "#NLPORTAL_CONFIG_KVK_ENABLED=false",
    "",
    "LOGLEVEL=INFO",
  ].join("\n");
  assert.deepEqual([...referenceKeys(env)].sort(), ["KEYCLOAK_BROKER_URL", "LOGLEVEL", "NLPORTAL_CONFIG_KVK_ENABLED"]);
});

test("chartKeys leest ConfigMap-data en container-env uit gerenderde YAML", () => {
  const yaml = [
    "apiVersion: v1",
    "kind: ConfigMap",
    "data:",
    '  LOGLEVEL: "INFO"',
    '  nl-portal.flagd.json: |',
    "    {}",
    "---",
    "kind: Deployment",
    "          env:",
    "            - name: REDIS_PASSWORD",
    "              valueFrom:",
  ].join("\n");
  assert.deepEqual([...chartKeys(yaml)].sort(), ["LOGLEVEL", "REDIS_PASSWORD"]);
});

test("vergelijk is groen als elke referentiesleutel gerenderd wordt", () => {
  const r = vergelijk({ reference: new Set(["A", "B"]), chart: new Set(["A", "B", "C"]), allowlist: [] });
  assert.deepEqual(r.ontbreekt, []);
  assert.deepEqual(r.problemen, []);
});

test("vergelijk faalt op een sleutel in de referentie die de chart niet rendert", () => {
  const r = vergelijk({ reference: new Set(["A", "NIEUW"]), chart: new Set(["A"]), allowlist: [] });
  assert.deepEqual(r.ontbreekt, ["NIEUW"]);
  assert.match(r.problemen.join("\n"), /NIEUW/);
});

test("een allowlist-entry met reden dekt een ontbrekende sleutel", () => {
  const r = vergelijk({ reference: new Set(["A", "X"]), chart: new Set(["A"]), allowlist: [{ key: "X", reason: "alleen lokaal" }] });
  assert.deepEqual(r.problemen, []);
});

test("een allowlist-entry zonder reden is een probleem", () => {
  const r = vergelijk({ reference: new Set(["X"]), chart: new Set(), allowlist: [{ key: "X", reason: " " }] });
  assert.match(r.problemen.join("\n"), /reden/);
});

test("een verouderde allowlist-entry (sleutel staat niet meer in de referentie of de chart rendert hem wel) is een probleem", () => {
  const r = vergelijk({
    reference: new Set(["A"]),
    chart: new Set(["A"]),
    allowlist: [{ key: "WEG", reason: "oud" }, { key: "A", reason: "onnodig" }],
  });
  assert.match(r.problemen.join("\n"), /WEG/);
  assert.match(r.problemen.join("\n"), /A\b/);
});

test("een allowlist-prefix dekt een groep sleutels en faalt als hij niets meer dekt", () => {
  const dekt = vergelijk({ reference: new Set(["P_A", "P_B"]), chart: new Set(), allowlist: [{ prefix: "P_", reason: "doorgeefmap" }] });
  assert.deepEqual(dekt.problemen, []);
  const leeg = vergelijk({ reference: new Set(["Q"]), chart: new Set(["Q"]), allowlist: [{ prefix: "P_", reason: "oud" }] });
  assert.match(leeg.problemen.join("\n"), /P_/);
});
