import { test } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { delimiter, join } from "node:path";
import { fileURLToPath } from "node:url";

const HOOK = fileURLToPath(new URL("../frontend/.husky/pre-push", import.meta.url));
const ZERO = "0".repeat(40);
const SHA = "a".repeat(40);

// Draait de hook in een lege repo-root met een nep-`mise` die elke aanroep logt in plaats van te linten.
function runHook(stdin) {
  const root = mkdtempSync(join(tmpdir(), "pre-push-hook-"));
  try {
    mkdirSync(join(root, "frontend"));
    mkdirSync(join(root, "backend"));
    const bin = join(root, "bin");
    mkdirSync(bin);
    const log = join(root, "calls.log");
    const fakeMise = join(bin, "mise");
    writeFileSync(fakeMise, `#!/usr/bin/env sh\necho "$*" >> "${log.replaceAll("\\", "/")}"\n`);
    chmodSync(fakeMise, 0o755);
    const result = spawnSync("sh", [HOOK, "origin", "git@example.com:repo.git"], {
      cwd: root,
      input: stdin,
      encoding: "utf8",
      env: { ...process.env, PATH: `${bin}${delimiter}${process.env.PATH}` },
    });
    const calls = existsSync(log) ? readFileSync(log, "utf8").trim().split("\n") : [];
    return { status: result.status, stdout: result.stdout, calls };
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

test("slaat lint en spotless over bij een push die alleen een branch verwijdert", () => {
  const { status, stdout, calls } = runHook(`(delete) ${ZERO} refs/heads/oud ${SHA}\n`);
  assert.equal(status, 0);
  assert.deepEqual(calls, []);
  assert.match(stdout, /alleen verwijderingen/);
});

test("slaat over bij meerdere verwijderingen tegelijk", () => {
  const { status, calls } = runHook(`(delete) ${ZERO} refs/heads/a ${SHA}\n(delete) ${ZERO} refs/heads/b ${SHA}\n`);
  assert.equal(status, 0);
  assert.deepEqual(calls, []);
});

test("slaat over bij lege stdin", () => {
  const { status, calls } = runHook("");
  assert.equal(status, 0);
  assert.deepEqual(calls, []);
});

test("draait lint en spotless bij een gewone push", () => {
  const { status, calls } = runHook(`refs/heads/feat ${SHA} refs/heads/feat ${ZERO}\n`);
  assert.equal(status, 0);
  assert.deepEqual(calls, ["exec -- pnpm lint", "exec -- ./gradlew spotlessCheck"]);
});

test("draait lint en spotless als een push verwijderingen en updates mengt", () => {
  const { calls } = runHook(`(delete) ${ZERO} refs/heads/oud ${SHA}\nrefs/heads/feat ${SHA} refs/heads/feat ${SHA}\n`);
  assert.deepEqual(calls, ["exec -- pnpm lint", "exec -- ./gradlew spotlessCheck"]);
});
