#!/usr/bin/env node
// Print de onveranderlijke build-tag <versie>-<sha> (ADR 0019, besluit 2). Gebruik:
//   node scripts/build-tag.mjs <pad-naar-version.txt> <commit-sha>
// De versie komt voorlopig nog uit version.txt (#608 vervangt dat). De sha wordt 12 tekens; bestaat dat prefix enkel uit cijfers,
// dan wordt het verlengd tot er een letter in staat, zodat de tag altijd een geldige semver-prerelease is (geen voorloopnul in een
// numerieke identifier).
import { readFileSync } from "node:fs";

const SHA_LENGTH = 12;

export function buildTag(versionTxt, sha) {
  const version = versionTxt.trim();
  if (!/^\d+\.\d+\.\d+$/.test(version)) {
    throw new Error(`Versie "${version}" is geen x.y.z.`);
  }
  const full = sha.trim().toLowerCase();
  if (!/^[0-9a-f]{40}$/.test(full)) {
    throw new Error(`Commit-sha "${sha}" is geen volledige sha van 40 hexadecimale tekens.`);
  }
  let length = SHA_LENGTH;
  while (/^\d+$/.test(full.slice(0, length)) && length < full.length) length++;
  return `${version}-${full.slice(0, length)}`;
}

if (process.argv[1]?.endsWith("build-tag.mjs")) {
  const [versionFile, sha] = process.argv.slice(2);
  if (!versionFile || !sha) {
    console.error("Gebruik: node scripts/build-tag.mjs <version.txt> <commit-sha>");
    process.exit(1);
  }
  try {
    process.stdout.write(`${buildTag(readFileSync(versionFile, "utf8"), sha)}\n`);
  } catch (e) {
    console.error(e.message);
    process.exit(1);
  }
}
