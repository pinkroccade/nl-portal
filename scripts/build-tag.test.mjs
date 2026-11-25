import { test } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const SCRIPT = fileURLToPath(new URL("./build-tag.mjs", import.meta.url));
const SHA = "1e4215856c0ffee1234567890abcdef123456789";

function run(versionTxt, args) {
  const dir = mkdtempSync(join(tmpdir(), "build-tag-"));
  try {
    const file = join(dir, "version.txt");
    writeFileSync(file, versionTxt);
    return spawnSync("node", [SCRIPT, file, ...args], { encoding: "utf8" });
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

test("geeft <versie>-<sha> met de eerste 12 tekens van de sha", () => {
  const r = run("0.1.0\n", [SHA]);
  assert.equal(r.status, 0);
  assert.equal(r.stdout.trim(), "0.1.0-1e4215856c0f");
});

test("een sha in hoofdletters wordt kleine letters", () => {
  const r = run("1.2.3", [SHA.toUpperCase()]);
  assert.equal(r.stdout.trim(), "1.2.3-1e4215856c0f");
});

test("een versie die geen x.y.z is faalt", () => {
  const r = run("0.1.0-RC-1-SNAPSHOT", [SHA]);
  assert.notEqual(r.status, 0);
  assert.match(r.stderr, /x\.y\.z/);
});

test("een ongeldige of te korte sha faalt", () => {
  assert.notEqual(run("0.1.0", ["xyz"]).status, 0);
  assert.notEqual(run("0.1.0", ["abc"]).status, 0);
});

test("een sha-prefix van enkel cijfers wordt verlengd tot een geldige semver-prerelease", () => {
  // Numerieke prerelease-identifiers mogen geen voorloopnul hebben: de tag moet altijd een geldige prerelease zijn.
  const r = run("0.1.0", ["012345678901abcdef0123456789abcdef012345"]);
  assert.equal(r.status, 0);
  assert.match(r.stdout.trim(), /^0\.1\.0-(?!0\d*$)[0-9a-z]+$/);
});
