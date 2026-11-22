import { test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { checkCopyrightHeaders, inScope } from "./check-copyright-headers.mjs";

const SCRIPT = fileURLToPath(new URL("./check-copyright-headers.mjs", import.meta.url));

const body = (copyright) => `/*
 * Copyright ${copyright}
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 */
package nl.nlportal.x
`;
const RITENSE = body("2015-2026 Den Haag, Ritense, the Netherlands.");
const PINK = body("2026 PinkRoccade Local Government B.V.");
const PINK_BUMPED = body("2026-2027 PinkRoccade Local Government B.V.");
const NO_HEADER = "package nl.nlportal.x\n";

const KT = "backend/core/src/main/kotlin/A.kt";

test("a new source file with the PinkRoccade header passes", () => {
  assert.deepEqual(checkCopyrightHeaders([{ status: "A", path: KT, after: PINK }]), []);
});

test("a new source file with the Ritense header fails and names the file and the expected header", () => {
  const [melding] = checkCopyrightHeaders([{ status: "A", path: KT, after: RITENSE }]);
  assert.match(melding, /backend\/core\/src\/main\/kotlin\/A\.kt/);
  assert.match(melding, /PinkRoccade Local Government B\.V\./);
});

test("a new source file without header fails", () => {
  assert.equal(checkCopyrightHeaders([{ status: "A", path: KT, after: NO_HEADER }]).length, 1);
});

test("a changed file keeps its Ritense header byte for byte", () => {
  const changed = RITENSE.replace("package nl.nlportal.x", "package nl.nlportal.y");
  assert.deepEqual(checkCopyrightHeaders([{ status: "M", path: KT, before: RITENSE, after: changed }]), []);
});

test("a changed year in a Ritense header fails and names the original header", () => {
  const [melding] = checkCopyrightHeaders([
    { status: "M", path: KT, before: RITENSE, after: body("2015-2027 Den Haag, Ritense, the Netherlands.") },
  ]);
  assert.match(melding, /A\.kt/);
  assert.match(melding, /2015-2026 Den Haag, Ritense/);
});

test("replacing a Ritense header by the PinkRoccade header fails", () => {
  assert.equal(checkCopyrightHeaders([{ status: "M", path: KT, before: RITENSE, after: PINK }]).length, 1);
});

test("a PinkRoccade end year may move up when the file changes", () => {
  assert.deepEqual(checkCopyrightHeaders([{ status: "M", path: KT, before: PINK, after: PINK_BUMPED }]), []);
});

test("a PinkRoccade file may not switch to another owner", () => {
  const [melding] = checkCopyrightHeaders([
    { status: "M", path: KT, before: PINK, after: body("2026 Iemand Anders") },
  ]);
  assert.match(melding, /A\.kt/);
});

test("a moved or copied upstream file keeps its Ritense header", () => {
  assert.deepEqual(
    checkCopyrightHeaders([{ status: "R", oldPath: "backend/old/A.kt", path: KT, before: RITENSE, after: RITENSE }]),
    [],
  );
});

test("a changed file that never had a header is left alone", () => {
  assert.deepEqual(checkCopyrightHeaders([{ status: "M", path: KT, before: NO_HEADER, after: NO_HEADER + "\n" }]), []);
});

test("files outside the header scope are ignored", () => {
  for (const path of ["README.md", "scripts/x.mjs", "backend/x/build/Gen.kt", "frontend/packages/a/src/x.d.ts",
    "frontend/packages/a/node_modules/m/i.js", "frontend/packages/a/src/generated/g.ts"]) {
    assert.equal(inScope(path), false, path);
    assert.deepEqual(checkCopyrightHeaders([{ status: "A", path, after: NO_HEADER }]), [], path);
  }
  for (const path of [KT, "backend/build.gradle.kts", "frontend/packages/a/src/X.tsx", "keycloak/provider/src/A.kt"]) {
    assert.equal(inScope(path), true, path);
  }
});

function git(cwd, ...args) {
  return execFileSync("git", args, { cwd, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
}

function withRepo(run) {
  const root = mkdtempSync(join(tmpdir(), "check-copyright-"));
  try {
    git(root, "init", "-q", "-b", "main");
    git(root, "config", "user.email", "t@example.org");
    git(root, "config", "user.name", "t");
    git(root, "config", "commit.gpgsign", "false");
    const write = (path, content) => {
      mkdirSync(dirname(join(root, path)), { recursive: true });
      writeFileSync(join(root, path), content);
    };
    write("backend/core/src/main/kotlin/Old.kt", RITENSE);
    git(root, "add", "-A");
    git(root, "commit", "-q", "-m", "basis");
    git(root, "switch", "-q", "-c", "werk");
    return run(root, write);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

function cli(root) {
  try {
    execFileSync("node", [SCRIPT, "--root", root, "--base", "main"], { encoding: "utf8", stdio: "pipe" });
    return { code: 0, stderr: "" };
  } catch (e) {
    return { code: e.status, stderr: String(e.stderr) };
  }
}

test("cli: untracked new file with Ritense header fails, with PinkRoccade header passes", () => {
  withRepo((root, write) => {
    write("backend/core/src/main/kotlin/New.kt", RITENSE);
    const rood = cli(root);
    assert.equal(rood.code, 1);
    assert.match(rood.stderr, /New\.kt/);
    write("backend/core/src/main/kotlin/New.kt", PINK);
    assert.equal(cli(root).code, 0);
  });
});

test("cli: a committed change of the Ritense year fails", () => {
  withRepo((root, write) => {
    write("backend/core/src/main/kotlin/Old.kt", body("2015-2027 Den Haag, Ritense, the Netherlands."));
    git(root, "commit", "-q", "-am", "jaar");
    const rood = cli(root);
    assert.equal(rood.code, 1);
    assert.match(rood.stderr, /Old\.kt/);
  });
});

test("cli: a rename of an upstream file with its header unchanged passes", () => {
  withRepo((root) => {
    git(root, "mv", "backend/core/src/main/kotlin/Old.kt", "backend/core/src/main/kotlin/Moved.kt");
    git(root, "commit", "-q", "-am", "verplaatst");
    assert.equal(cli(root).code, 0);
  });
});
