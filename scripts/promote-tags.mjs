#!/usr/bin/env node
// Bepaal welke versietags een promotie mag zetten (ADR 0019, besluit 3 en 7). Gebruik:
//   node scripts/promote-tags.mjs <versie> <bestaande-versie>...
// Schrijft de tags één per regel. x.y.z gaat altijd mee; x.y, x en latest alleen als de versie niet lager is dan de hoogste bestaande
// versie binnen die minor, major of overall, zodat een oudere patch een nieuwere tag of `latest` nooit terugzet.
const SEMVER = /^(\d+)\.(\d+)\.(\d+)$/;

function parse(version) {
  const m = SEMVER.exec(version);
  return m ? m.slice(1).map(Number) : null;
}

function compare(a, b) {
  for (let i = 0; i < 3; i++) if (a[i] !== b[i]) return a[i] - b[i];
  return 0;
}

export function planTags(version, existingVersions) {
  const next = parse(version);
  if (!next) throw new Error(`Versie "${version}" is geen x.y.z.`);
  const existing = existingVersions.map(parse).filter(Boolean);
  const notBelow = (scope) => existing.filter(scope).every((v) => compare(next, v) >= 0);
  const [major, minor] = next;
  const tags = [version];
  if (notBelow((v) => v[0] === major && v[1] === minor)) tags.push(`${major}.${minor}`);
  if (notBelow((v) => v[0] === major)) tags.push(`${major}`);
  if (notBelow(() => true)) tags.push("latest");
  return tags;
}

if (process.argv[1]?.endsWith("promote-tags.mjs")) {
  const [version, ...existing] = process.argv.slice(2);
  if (!version) {
    console.error("Gebruik: node scripts/promote-tags.mjs <versie> <bestaande-versie>...");
    process.exit(1);
  }
  try {
    process.stdout.write(`${planTags(version, existing).join("\n")}\n`);
  } catch (e) {
    console.error(e.message);
    process.exit(1);
  }
}
