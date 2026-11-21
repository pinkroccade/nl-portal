import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { checkCompliance, REGISTER_PATH } from "./check-compliance.mjs";

function register({ sources, matrix }) {
  return [
    "# Normatieve bronnen",
    "",
    "## Register",
    "",
    "| ID | Bron | Versie | Geraadpleegd op | Wijze van raadplegen |",
    "|---|---|---|---|---|",
    ...sources,
    "",
    "## Traceerbaarheidsmatrix",
    "",
    "| Bron | Eis | Status | Guardrail of check | Bewijs | Besluit |",
    "|---|---|---|---|---|---|",
    ...matrix,
    "",
  ].join("\n");
}

const SOURCE_A = "| `N-ASVS-5.0` | ASVS | 5.0.0 | 2026-11-05 | zelf gelezen |";
const ROW_A = "| `N-ASVS-5.0` | 9.1.1 | Afgedwongen | `JwtDecoderTest` | CI | - |";

function withRepo(files, run) {
  const root = mkdtempSync(join(tmpdir(), "check-compliance-"));
  try {
    for (const [path, content] of Object.entries(files)) {
      mkdirSync(dirname(join(root, path)), { recursive: true });
      writeFileSync(join(root, path), content);
    }
    return run(root);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

const ok = { [REGISTER_PATH]: register({ sources: [SOURCE_A], matrix: [ROW_A] }) };

test("accepteert een register waarin elke bron een check heeft", () => {
  assert.deepEqual(withRepo(ok, checkCompliance).problems, []);
});

test("faalt op een ID dat in een ADR wordt genoemd maar niet bestaat", () => {
  const result = withRepo({ ...ok, "site/development/adr/0001-x.md": "Volgt N-RFC-9999." }, checkCompliance);
  assert.equal(result.problems.length, 1);
  assert.match(result.problems[0], /adr\/0001-x\.md.*N-RFC-9999/);
});

test("faalt op een ID in een Kotlin-because-tekst en in een workflow dat niet bestaat", () => {
  const result = withRepo(
    {
      ...ok,
      "backend/x/src/Rules.kt": '.because("zie N-NOPE-1")',
      ".github/workflows/ci.yml": "# N-ANDERS-2\n",
    },
    checkCompliance,
  );
  assert.equal(result.problems.length, 2);
});

test("accepteert een bestaand ID in een ADR met zinseinde-punt", () => {
  const files = { ...ok, "site/development/adr/0001-x.md": "Voert N-ASVS-5.0 uit." };
  assert.deepEqual(withRepo(files, checkCompliance).problems, []);
});

test("faalt op een bron zonder check of afwijkingsbesluit", () => {
  const files = { [REGISTER_PATH]: register({ sources: [SOURCE_A], matrix: [] }) };
  const result = withRepo(files, checkCompliance);
  assert.equal(result.problems.length, 1);
  assert.match(result.problems[0], /N-ASVS-5\.0.*geen check/);
});

test("een bron met alleen status Open telt niet als gedekt", () => {
  const row = "| `N-ASVS-5.0` | 9.2.2 | Open | - | - | #999 |";
  const files = { [REGISTER_PATH]: register({ sources: [SOURCE_A], matrix: [row] }) };
  assert.equal(withRepo(files, checkCompliance).problems.length, 1);
});

test("accepteert een bron die bewust niet is toegepast met een besluit", () => {
  const row = "| `N-ASVS-5.0` | 9.2.2 | Bewust niet toegepast | - | - | [ADR 0012](x.md) |";
  const files = { [REGISTER_PATH]: register({ sources: [SOURCE_A], matrix: [row] }) };
  assert.deepEqual(withRepo(files, checkCompliance).problems, []);
});

test("faalt op bewust niet toegepast zonder besluit", () => {
  const row = "| `N-ASVS-5.0` | 9.2.2 | Bewust niet toegepast | - | - | - |";
  const files = { [REGISTER_PATH]: register({ sources: [SOURCE_A], matrix: [row] }) };
  assert.match(withRepo(files, checkCompliance).problems.join("\n"), /zonder besluit/);
});

test("faalt op een afgedwongen rij zonder guardrail of bewijs", () => {
  const row = "| `N-ASVS-5.0` | 9.1.1 | Afgedwongen | - | - | - |";
  const files = { [REGISTER_PATH]: register({ sources: [SOURCE_A], matrix: [ROW_A, row] }) };
  assert.match(withRepo(files, checkCompliance).problems.join("\n"), /9\.1\.1.*guardrail/);
});

test("faalt op een matrixrij met onbekend bron-ID en op een onbekende status", () => {
  const rows = [ROW_A, "| `N-XXX-1` | a | Afgedwongen | c | b | - |", "| `N-ASVS-5.0` | b | Misschien | c | b | - |"];
  const files = { [REGISTER_PATH]: register({ sources: [SOURCE_A], matrix: rows }) };
  const result = withRepo(files, checkCompliance);
  assert.equal(result.problems.length, 2);
});

test("faalt op een bron zonder geraadpleegd-datum en op een dubbel ID", () => {
  const files = {
    [REGISTER_PATH]: register({
      sources: ["| `N-ASVS-5.0` | ASVS | 5.0.0 | | zelf gelezen |", SOURCE_A],
      matrix: [ROW_A],
    }),
  };
  assert.equal(withRepo(files, checkCompliance).problems.length, 2);
});

test("negeert woorden die op een ID lijken maar er geen zijn, zoals N-SNAPSHOT", () => {
  const files = { ...ok, ".github/workflows/release.yml": "tag: N-SNAPSHOT" };
  assert.deepEqual(withRepo(files, checkCompliance).problems, []);
});

test("faalt als het register ontbreekt", () => {
  assert.throws(() => withRepo({ "site/x.md": "x" }, checkCompliance), /normatieve-bronnen\.md/);
});

const PRINCIPLES = "site/architectuur/principes.md";

function principle({ id = "P-01", bron = "`N-ASVS-5.0`", toetsing = "CI-controle: `mise run compliance:check`" } = {}) {
  return [
    `### ${id} Security by design`,
    "",
    "**Statement:** Beveiliging is een ontwerpeis.",
    "",
    "**Rationale:** De portal verwerkt BSN.",
    "",
    "**Implications:** Elke wijziging wordt getoetst.",
    "",
    ...(bron === null ? [] : [`**Bron:** ${bron}`, ""]),
    ...(toetsing === null ? [] : [`**Toetsing:** ${toetsing}`, ""]),
  ].join("\n");
}

const principles = (...sections) => ["# Principes", "", "## Register", "", ...sections].join("\n");

test("faalt op een principe zonder bron", () => {
  const files = { ...ok, [PRINCIPLES]: principles(principle({ bron: null })) };
  const result = withRepo(files, checkCompliance);
  assert.equal(result.problems.length, 1);
  assert.match(result.problems[0], /principes\.md: principe P-01 heeft geen bron/);
});

test("faalt op een principe zonder toetsing", () => {
  const files = { ...ok, [PRINCIPLES]: principles(principle({ toetsing: null })) };
  const result = withRepo(files, checkCompliance);
  assert.equal(result.problems.length, 1);
  assert.match(result.problems[0], /principe P-01 heeft geen toetsing/);
});

test("accepteert een principe met bron en toetsing", () => {
  const files = { ...ok, [PRINCIPLES]: principles(principle()) };
  assert.deepEqual(withRepo(files, checkCompliance).problems, []);
});

test("faalt op een principe waarvan de bron geen register-ID noemt", () => {
  const files = { ...ok, [PRINCIPLES]: principles(principle({ bron: "AVG art. 25" })) };
  const result = withRepo(files, checkCompliance);
  assert.equal(result.problems.length, 1);
  assert.match(result.problems[0], /principe P-01 noemt geen bron-ID uit .*normatieve-bronnen\.md/);
});

test("faalt op een toetsing die geen bekende soort noemt", () => {
  const files = { ...ok, [PRINCIPLES]: principles(principle({ toetsing: "we letten erop" })) };
  const result = withRepo(files, checkCompliance);
  assert.equal(result.problems.length, 1);
  assert.match(result.problems[0], /principe P-01 heeft een toetsing zonder soort \(ArchUnit-regel, .*Reviewpunt\)/);
});

test("faalt op een reviewpunt dat niet naar CODING_STANDARDS.md verwijst", () => {
  const files = { ...ok, [PRINCIPLES]: principles(principle({ toetsing: "Reviewpunt: let op privacy" })) };
  const result = withRepo(files, checkCompliance);
  assert.equal(result.problems.length, 1);
  assert.match(result.problems[0], /principe P-01 heeft een reviewpunt zonder verwijzing naar CODING_STANDARDS\.md/);
});

test("accepteert meerdere toetsingen, waaronder een reviewpunt met verwijzing", () => {
  const toetsing = "ArchUnit-regel: `ArchitectureRules.cycleFree`; Reviewpunt: [privacy](../../CODING_STANDARDS.md#privacy)";
  const files = { ...ok, [PRINCIPLES]: principles(principle({ toetsing })) };
  assert.deepEqual(withRepo(files, checkCompliance).problems, []);
});

test("een bron zonder matrixrij is gedekt als een principe met geldige toetsing ernaar verwijst", () => {
  const sourceB = "| `N-WCAG-2.2` | WCAG | 2.2 | 2026-11-21 | zelf gelezen |";
  const files = {
    [REGISTER_PATH]: register({ sources: [SOURCE_A, sourceB], matrix: [ROW_A] }),
    [PRINCIPLES]: principles(principle({ bron: "`N-WCAG-2.2`" })),
  };
  assert.deepEqual(withRepo(files, checkCompliance).problems, []);
});

test("een principe zonder geldige toetsing dekt zijn bron niet", () => {
  const sourceB = "| `N-WCAG-2.2` | WCAG | 2.2 | 2026-11-21 | zelf gelezen |";
  const files = {
    [REGISTER_PATH]: register({ sources: [SOURCE_A, sourceB], matrix: [ROW_A] }),
    [PRINCIPLES]: principles(principle({ bron: "`N-WCAG-2.2`", toetsing: "Reviewpunt: zonder link" })),
  };
  const problems = withRepo(files, checkCompliance).problems.join("\n");
  assert.match(problems, /bron N-WCAG-2\.2 heeft geen check of afwijkingsbesluit/);
});

test("faalt op een principe-ID dat in een ADR wordt genoemd maar niet in het register staat", () => {
  const files = {
    ...ok,
    [PRINCIPLES]: principles(principle()),
    "site/development/adr/0023-x.md": "Volgt P-01 en P-42.",
  };
  const result = withRepo(files, checkCompliance);
  assert.equal(result.problems.length, 1);
  assert.match(result.problems[0], /adr\/0023-x\.md: noemt principe P-42, dat niet in .*principes\.md staat/);
});
