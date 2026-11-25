import { test } from "node:test";
import assert from "node:assert/strict";
import { planTags } from "./promote-tags.mjs";

test("eerste release zet alle vier de tags", () => {
  assert.deepEqual(planTags("1.2.3", []), ["1.2.3", "1.2", "1", "latest"]);
});

test("nieuwere patch en minor schuiven alle tags door", () => {
  assert.deepEqual(planTags("1.3.0", ["1.2.3", "1.2.4"]), ["1.3.0", "1.3", "1", "latest"]);
});

test("oudere patch van de hoogste minor laat x.y, x en latest staan", () => {
  assert.deepEqual(planTags("1.2.3", ["1.2.3", "1.2.4"]), ["1.2.3"]);
});

test("patch op een oudere minor mag x.y zetten, maar niet x of latest", () => {
  assert.deepEqual(planTags("1.2.5", ["1.2.4", "1.3.0"]), ["1.2.5", "1.2"]);
});

test("patch op een oudere major mag x.y en x zetten, maar niet latest", () => {
  assert.deepEqual(planTags("1.9.1", ["1.9.0", "2.0.0"]), ["1.9.1", "1.9", "1"]);
});

test("opnieuw promoveren van de hoogste versie is idempotent", () => {
  assert.deepEqual(planTags("1.2.4", ["1.2.4"]), ["1.2.4", "1.2", "1", "latest"]);
});

test("versies vergelijken numeriek, niet als tekst", () => {
  assert.deepEqual(planTags("1.10.0", ["1.9.0"]), ["1.10.0", "1.10", "1", "latest"]);
  assert.deepEqual(planTags("1.9.0", ["1.10.0"]), ["1.9.0", "1.9"]);
});

test("een ongeldige versie wordt geweigerd", () => {
  assert.throws(() => planTags("1.2", []), /x\.y\.z/);
});

test("bestaande tags die geen versie zijn, worden genegeerd", () => {
  assert.deepEqual(planTags("1.0.0", ["chart-nl-portal-0.3.0", "latest", "1.0.0-rc.1"]), ["1.0.0", "1.0", "1", "latest"]);
});
