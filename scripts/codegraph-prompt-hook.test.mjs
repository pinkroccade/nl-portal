import { test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { shouldSkipPrompt } from "./codegraph-prompt-hook.mjs";

const SCRIPT = fileURLToPath(new URL("./codegraph-prompt-hook.mjs", import.meta.url));

test("skips system-generated messages that start with a tag", () => {
  assert.equal(shouldSkipPrompt('<ci-monitor-event>"Auto-fix pull requests" is watching PR #171</ci-monitor-event>'), true);
  assert.equal(shouldSkipPrompt("<create-pr-command>\n## Overview"), true);
  assert.equal(shouldSkipPrompt("<command-message>retro</command-message>"), true);
});

test("skips tagged messages after leading whitespace", () => {
  assert.equal(shouldSkipPrompt("\n  <ci-monitor-event>x</ci-monitor-event>"), true);
});

test("passes user prompts on to CodeGraph", () => {
  assert.equal(shouldSkipPrompt("hoe werkt FrontendConfigurationService?"), false);
  assert.equal(shouldSkipPrompt("ja"), false);
  assert.equal(shouldSkipPrompt("Vergelijk a < b in de parser"), false);
});

test("treats a missing prompt as a user prompt", () => {
  assert.equal(shouldSkipPrompt(undefined), false);
});

test("exits 0 without output for a system message, without calling CodeGraph", () => {
  const output = execFileSync("node", [SCRIPT], {
    input: JSON.stringify({ prompt: "<ci-monitor-event>review fix check</ci-monitor-event>", cwd: process.cwd() }),
    encoding: "utf8",
  });
  assert.equal(output, "");
});

test("exits 0 without output for invalid input", () => {
  const output = execFileSync("node", [SCRIPT], { input: "not json", encoding: "utf8" });
  assert.equal(output, "");
});

test("stays silent and exits 0 when CodeGraph is not installed", () => {
  const output = execFileSync("node", [SCRIPT], {
    input: JSON.stringify({ prompt: "hoe werkt FrontendConfigurationService?", cwd: process.cwd() }),
    env: { ...process.env, PATH: dirname(process.execPath) },
    encoding: "utf8",
    stdio: ["pipe", "pipe", "pipe"],
  });
  assert.equal(output, "");
});
