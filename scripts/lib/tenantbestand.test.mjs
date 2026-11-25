import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { leesTenantbestanden, parseTenantYaml } from "./tenantbestand.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");

test("parseTenantYaml leest de vormen uit de tenantbestanden: tekst, gequote tekst, lijsten, geneste mappen, commentaar", () => {
  const yaml = [
    "# commentaar",
    'oin: "00000099000000000001"  # gequote',
    "naam: Gemeente De Bosrand",
    "domeinen:",
    "    - localhost",
    "    - mijn.debosrand.example",
    "inlogmiddelen: [digid, yivi]",
    "thema:",
    "    titel: Mijn De Bosrand",
    "sessie:",
    "    inactiviteit: PT10M",
    "",
  ].join("\n");
  assert.deepEqual(parseTenantYaml(yaml), {
    oin: "00000099000000000001",
    naam: "Gemeente De Bosrand",
    domeinen: ["localhost", "mijn.debosrand.example"],
    inlogmiddelen: ["digid", "yivi"],
    thema: { titel: "Mijn De Bosrand" },
    sessie: { inactiviteit: "PT10M" },
  });
});

test("parseTenantYaml weigert wat buiten de vorm valt in plaats van te raden", () => {
  assert.throws(() => parseTenantYaml("a: &anker x\nb: *anker"), /alias|anker/i);
  assert.throws(() => parseTenantYaml("a: {b: c}"), /buiten/i);
  assert.throws(() => parseTenantYaml("a: 1\na: 2"), /dubbele/i);
  assert.throws(() => parseTenantYaml("a: x\n\tb: y"), /tab/i);
});

function tenantMap(bestanden) {
  const map = mkdtempSync(join(tmpdir(), "tenants-"));
  for (const [naam, inhoud] of Object.entries(bestanden)) writeFileSync(join(map, naam), inhoud);
  return map;
}

test("leesTenantbestanden voegt base.yaml samen met het tenantbestand: mappen diep, lijsten vervangen", () => {
  const map = tenantMap({
    "base.yaml": "omgevingstype: ontwikkeling\ninlogmiddelen: [gebruikersnaam]\nthema:\n  titel: Basis\n  logo: basis.svg\n",
    "00000099000000000001.yaml": 'oin: "00000099000000000001"\nnaam: De Bosrand\ndomeinen:\n  - localhost\nthema:\n  titel: Mijn De Bosrand\ninlogmiddelen: [digid]\n',
  });
  try {
    const [tenant] = leesTenantbestanden(map);
    assert.deepEqual(tenant, {
      omgevingstype: "ontwikkeling",
      inlogmiddelen: ["digid"],
      thema: { titel: "Mijn De Bosrand", logo: "basis.svg" },
      oin: "00000099000000000001",
      naam: "De Bosrand",
      domeinen: ["localhost"],
    });
  } finally {
    rmSync(map, { recursive: true, force: true });
  }
});

test("leesTenantbestanden weigert een bestandsnaam die niet bij het OIN past en een map zonder tenant", () => {
  const fout = tenantMap({ "00000099000000000002.yaml": 'oin: "00000099000000000001"\n' });
  const leeg = tenantMap({ "base.yaml": "omgevingstype: test\n" });
  try {
    assert.throws(() => leesTenantbestanden(fout), /00000099000000000002/);
    assert.throws(() => leesTenantbestanden(leeg), /geen tenantbestanden/i);
    mkdirSync(join(leeg, "submap"));
    assert.throws(() => leesTenantbestanden(join(leeg, "bestaat-niet")));
  } finally {
    rmSync(fout, { recursive: true, force: true });
    rmSync(leeg, { recursive: true, force: true });
  }
});

test("de demo-tenantbestanden van docker-compose zijn leesbaar", () => {
  const tenants = leesTenantbestanden(join(repoRoot, "docker-compose", "imports", "tenants"));
  assert.deepEqual(tenants.map((t) => [t.oin, t.omgevingstype, t.inlogmiddelen]), [["00000099000000000001", "ontwikkeling", ["gebruikersnaam"]]]);
});
