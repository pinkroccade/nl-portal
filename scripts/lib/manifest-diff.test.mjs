import { test } from "node:test";
import assert from "node:assert/strict";
import { MARKER, renderDiffComment } from "./manifest-diff.mjs";

test("het comment begint met de vaste marker, zodat het bij nieuwe commits wordt bijgewerkt", () => {
  const body = renderDiffComment([{ scenario: "minimal", diff: "" }]);
  assert.equal(body.split("\n")[0], MARKER);
});

test("scenario's zonder verschil worden als ongewijzigd gemeld, de rest krijgt een diff-blok", () => {
  const body = renderDiffComment([
    { scenario: "minimal", diff: "" },
    { scenario: "full", diff: "-a\n+b" },
  ]);
  assert.match(body, /minimal.*geen verschil/);
  assert.match(body, /<details>[\s\S]*full[\s\S]*```diff\n-a\n\+b\n```/);
});

test("zonder enig verschil staat er één duidelijke regel", () => {
  const body = renderDiffComment([{ scenario: "minimal", diff: "" }]);
  assert.match(body, /Geen verschil in de gerenderde manifests/);
});

test("een te lange diff wordt afgekapt onder de limiet van GitHub", () => {
  const body = renderDiffComment([{ scenario: "full", diff: "+x\n".repeat(60000) }]);
  assert.ok(body.length < 65536);
  assert.match(body, /afgekapt/);
});
