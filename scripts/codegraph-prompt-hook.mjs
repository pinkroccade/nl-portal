#!/usr/bin/env node
// Claude Code `UserPromptSubmit` hook (see .claude/settings.json): hands the prompt to `codegraph prompt-hook`,
// which injects CodeGraph context for structural questions.
//
// Why a wrapper: the hook matches words of system-generated messages (CI events, PR commands, slash commands) to
// symbol names and injects kilobytes of unrelated context, and `codegraph.cmd` only exists on Windows.
// Messages that start with `<` are system-generated; those are skipped. This script never fails a prompt:
// every error path exits 0 without output.

import { spawn } from "node:child_process";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

export function shouldSkipPrompt(prompt) {
  return String(prompt ?? "").trimStart().startsWith("<");
}

function readStdin() {
  return new Promise((resolveInput) => {
    let data = "";
    process.stdin.setEncoding("utf8");
    process.stdin.on("data", (chunk) => (data += chunk));
    process.stdin.on("end", () => resolveInput(data));
    process.stdin.on("error", () => resolveInput(data));
  });
}

async function main() {
  const raw = await readStdin();
  let prompt;
  try {
    prompt = JSON.parse(raw).prompt;
  } catch {
    return;
  }
  if (shouldSkipPrompt(prompt)) {
    return;
  }
  await new Promise((done) => {
    // On Windows `codegraph` is a .cmd shim, which only a shell resolves (via PATHEXT). The command string is fixed,
    // so nothing user-controlled reaches the shell.
    const stdio = ["pipe", "inherit", "ignore"];
    const child =
      process.platform === "win32"
        ? spawn("codegraph prompt-hook", { shell: true, stdio })
        : spawn("codegraph", ["prompt-hook"], { stdio });
    child.on("error", () => done());
    child.on("close", () => done());
    child.stdin.on("error", () => {});
    child.stdin.end(raw);
  });
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().then(
    () => process.exit(0),
    () => process.exit(0),
  );
}
