import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

// Conventies van de Helm-chart die helm-unittest niet kan toetsen: wat er in de templates en values staat (geen render).
const chart = join(dirname(fileURLToPath(import.meta.url)), "..", "helm", "nl-portal");
const read = (p) => readFileSync(join(chart, p), "utf8");
const templates = readdirSync(join(chart, "templates")).map((f) => [f, read(`templates/${f}`)]);

test("de templates renderen deterministisch: geen lookup, rand*, now of genCA (Argo CD)", () => {
  for (const [naam, inhoud] of templates) {
    const code = [...inhoud.matchAll(/\{\{[\s\S]*?\}\}/g)].map(([m]) => m).join("\n");
    assert.doesNotMatch(code, /\b(lookup|randAlphaNum|randAlpha|randNumeric|randAscii|rand|now|genCA|genPrivateKey|genSelfSignedCert|uuidv4)\b/, naam);
  }
});

test("de chart bevat geen Secret, geen hooks en geen subcharts", () => {
  for (const [naam, inhoud] of templates) {
    assert.doesNotMatch(inhoud, /^kind:\s*Secret\s*$/m, naam);
    assert.doesNotMatch(inhoud, /helm\.sh\/hook/, naam);
  }
  assert.doesNotMatch(read("Chart.yaml"), /^dependencies:/m);
});

test("de sleutels in values.yaml zijn camelCase", () => {
  const sleutels = [...read("values.yaml").matchAll(/^\s*-?\s*([A-Za-z0-9_-]+):/gm)].map(([, k]) => k);
  // Uitzonderingen: de namen van env-variabelen in secretKeys (waarden, geen sleutels) komen hier niet voor.
  const slecht = sleutels.filter((k) => !/^[a-z][a-zA-Z0-9]*$/.test(k) && !/^\d+$/.test(k));
  assert.deepEqual(slecht, []);
});

test("het schema legt de globals vast op de twee ondersteunde waarden", () => {
  const schema = JSON.parse(read("values.schema.json"));
  assert.deepEqual(Object.keys(schema.properties.global.properties).sort(), ["imagePullSecrets", "imageRegistry"]);
  assert.equal(schema.properties.global.additionalProperties, false);
});
