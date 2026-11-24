#!/usr/bin/env node
// Vergelijkt <dir>/base/<scenario>.yaml met <dir>/head/<scenario>.yaml en schrijft het PR-comment naar stdout.
// Gerenderd door mise-taak helm:manifest-diff.
import { readdirSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { renderDiffComment } from "./lib/manifest-diff.mjs";

const dir = process.argv[2];
if (!dir) {
  console.error("Gebruik: node scripts/manifest-diff.mjs <map met base/ en head/>");
  process.exit(1);
}

const sections = readdirSync(`${dir}/head`)
  .filter((file) => file.endsWith(".yaml"))
  .sort()
  .map((file) => {
    // Een scenario dat op main nog niet bestaat levert een lege base op: alles telt dan als toevoeging.
    const base = readdirSync(`${dir}/base`).includes(file) ? `${dir}/base/${file}` : "/dev/null";
    const result = spawnSync("git", ["diff", "--no-index", "--no-color", "--unified=3", base, `${dir}/head/${file}`], { encoding: "utf8" });
    if (result.status !== 0 && result.status !== 1) throw new Error(result.stderr);
    // De kop (diff --git, index, ---/+++) bevat tijdelijke paden; houd alleen de hunks.
    const diff = result.stdout.split("\n").filter((l) => !/^(diff --git|index |--- |\+\+\+ |new file mode)/.test(l)).join("\n");
    return { scenario: file.replace(/\.yaml$/, ""), diff };
  });

process.stdout.write(renderDiffComment(sections));
