import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { checkCompliance, PRINCIPLES_PATH, REGISTER_PATH, TOETSING_SOORTEN } from "./check-compliance.mjs";

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
const SOURCE_B = "| `N-WCAG-2.2` | WCAG | 2.2 | 2026-11-21 | zelf gelezen |";
const ROW_A = "| `N-ASVS-5.0` | 9.1.1 | Afgedwongen | `JwtDecoderTest` | CI | - |";

function withRepo(files, run) {
  const root = mkdtempSync(join(tmpdir(), "check-compliance-"));
  try {
    // Standaard een leeg principeregister; geef `[PRINCIPLES]: null` mee om het weg te laten.
    const all = { [PRINCIPLES]: "# Principes\n\n## Register\n", ...REFERENCED, ...files };
    for (const [path, content] of Object.entries(all)) {
      if (content === null) continue;
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

// Dingen waar toetsingen naar verwijzen en die dus moeten bestaan.
const REFERENCED = {
  "mise.toml": '[tasks."compliance:check"]\nrun = "node scripts/check-compliance.mjs"\n',
  "mise-tasks/docs/capabilities-check": "#!/usr/bin/env bash\n",
  "backend/arch/src/test/kotlin/ArchitectureRules.kt": "object ArchitectureRules { val cycleFree = 1 }\n",
  "backend/app/src/test/kotlin/FeatureContextPrivacyTest.kt": "class FeatureContextPrivacyTest\n",
  ".github/workflows/secret-scan.yml": "name: secret-scan\n",
  "frontend/eslint.config.mjs": "export default [];\n",
  "CODING_STANDARDS.md": "# Standaarden\n\n## Privacy\n\n## Privacy en dataminimalisatie\n\n### Open standaarden\n",
};

const principles = (...sections) => ["# Principes", "", "## Register", "", ...sections].join("\n");

test("faalt als het principeregister ontbreekt", () => {
  assert.throws(() => withRepo({ ...ok, [PRINCIPLES]: null }, checkCompliance), /principes\.md ontbreekt/);
});

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

test("faalt op een toetsing met een soort maar zonder verwijzing of met een plaatshouder", () => {
  for (const toetsing of ["Lintregel:", "ArchUnit-regel: volgt", "Fitness test: nvt", "CI-controle: -"]) {
    const files = { ...ok, [PRINCIPLES]: principles(principle({ toetsing })) };
    const result = withRepo(files, checkCompliance);
    assert.equal(result.problems.length, 1, toetsing);
    assert.match(result.problems[0], /principe P-01 heeft een toetsing zonder verwijzing/, toetsing);
  }
});

test("faalt op een principekop zonder geldig P-nn-ID", () => {
  for (const id of ["P-5", "P5", "P-100", "Extra"]) {
    const files = { ...ok, [PRINCIPLES]: principles(principle(), principle({ id })) };
    const result = withRepo(files, checkCompliance);
    assert.equal(result.problems.length, 1, id);
    assert.match(result.problems[0], /geen geldig principe-ID/, id);
  }
});

test("faalt op een principe-ID dat twee keer voorkomt", () => {
  const files = { ...ok, [PRINCIPLES]: principles(principle(), principle()) };
  const result = withRepo(files, checkCompliance);
  assert.equal(result.problems.length, 1);
  assert.match(result.problems[0], /principe P-01 komt meer dan eens voor/);
});

test("negeert koppen buiten het principeregister", () => {
  const overzicht = ["## Overzicht", "", "### Toelichting", "", "Tekst.", ""].join("\n");
  const files = { ...ok, [PRINCIPLES]: ["# Principes", "", overzicht, "## Register", "", principle()].join("\n") };
  assert.deepEqual(withRepo(files, checkCompliance).problems, []);
});

test("proza of een kop na het laatste principe hoort niet bij zijn laatste veld", () => {
  const nawoord = ["## Wijzigingen", "", "Zie N-ASVS-5.0 en P-01.", ""].join("\n");
  const files = { ...ok, [PRINCIPLES]: [principles(principle()), nawoord].join("\n") };
  assert.deepEqual(withRepo(files, checkCompliance).problems, []);
});

test("een bron-ID in proza onder het laatste principe telt niet als bron van dat principe", () => {

  const nawoord = ["## Wijzigingen", "", "Nieuw: N-WCAG-2.2.", ""].join("\n");
  const files = {
    [REGISTER_PATH]: register({ sources: [SOURCE_A, SOURCE_B], matrix: [ROW_A] }),
    [PRINCIPLES]: [principles(principle({ bron: null })), "**Bron:**", "", nawoord].join("\n"),
  };
  assert.match(withRepo(files, checkCompliance).problems.join("\n"), /bron N-WCAG-2\.2 heeft geen check/);
});

test("een bron-ID in een alinea na het laatste veld van het laatste principe telt niet als bron", () => {

  const files = {
    [REGISTER_PATH]: register({ sources: [SOURCE_A, SOURCE_B], matrix: [ROW_A] }),
    [PRINCIPLES]: [principles(principle({ bron: "`N-ASVS-5.0`" })), "Los proza over N-WCAG-2.2.", ""].join("\n"),
  };
  assert.match(withRepo(files, checkCompliance).problems.join("\n"), /bron N-WCAG-2\.2 heeft geen check/);
});

test("faalt op een veld dat twee keer in één principe staat", () => {
  const dubbel = `${principle()}\n**Toetsing:** Lintregel: \`x\`\n`;
  const result = withRepo({ ...ok, [PRINCIPLES]: principles(dubbel) }, checkCompliance);
  assert.equal(result.problems.length, 1);
  assert.match(result.problems[0], /principe P-01 heeft het veld Toetsing meer dan eens/);
});

test("een ### in een codeblok splitst een principe niet", () => {
  const metBlok = `${principle()}\n\`\`\`\n### P-99 voorbeeld\n\`\`\`\n`;
  assert.deepEqual(withRepo({ ...ok, [PRINCIPLES]: principles(metBlok) }, checkCompliance).problems, []);
});

test("accepteert toetsingen waarvan de verwijzingen bestaan", () => {
  const toetsing = [
    "CI-controle: `mise run compliance:check`",
    "CI-controle: `mise run docs:capabilities-check`",
    "CI-controle: `secret-scan.yml`",
    "Fitness test: `FeatureContextPrivacyTest`",
    "ArchUnit-regel: `ArchitectureRules.cycleFree`",
    "Lintregel: `eslint-plugin-jsx-a11y` in `frontend/eslint.config.mjs`",
    "Reviewpunt: [privacy](https://x/blob/main/CODING_STANDARDS.md#privacy-en-dataminimalisatie)",
  ].join("; ");
  const files = { ...ok, [PRINCIPLES]: principles(principle({ toetsing })) };
  assert.deepEqual(withRepo(files, checkCompliance).problems, []);
});

test("faalt op een toetsing die naar iets verwijst dat niet bestaat", () => {
  const gevallen = {
    "Fitness test: `BestaatNietTest`": /BestaatNietTest/,
    "ArchUnit-regel: `NietBestaandeRules.regel`": /NietBestaandeRules/,
    "CI-controle: `mise run bestaat:niet`": /bestaat:niet/,
    "CI-controle: `weg.yml`": /weg\.yml/,
    "Lintregel: `frontend/weg.mjs`": /frontend\/weg\.mjs/,
    "Reviewpunt: [x](https://x/blob/main/CODING_STANDARDS.md#bestaat-niet)": /#bestaat-niet/,
  };
  for (const [toetsing, verwacht] of Object.entries(gevallen)) {
    const files = { ...ok, [PRINCIPLES]: principles(principle({ toetsing })) };
    const result = withRepo(files, checkCompliance);
    assert.equal(result.problems.length, 1, toetsing);
    assert.match(result.problems[0], /principe P-01 verwijst naar iets dat niet bestaat/, toetsing);
    assert.match(result.problems[0], verwacht, toetsing);
  }
});

test("een principe met een niet-bestaande verwijzing dekt zijn bron niet", () => {

  const files = {
    [REGISTER_PATH]: register({ sources: [SOURCE_A, SOURCE_B], matrix: [ROW_A] }),
    [PRINCIPLES]: principles(principle({ bron: "`N-WCAG-2.2`", toetsing: "Fitness test: `WegTest`" })),
  };
  assert.match(withRepo(files, checkCompliance).problems.join("\n"), /bron N-WCAG-2\.2 heeft geen check/);
});

test("accepteert meerdere toetsingen, waaronder een reviewpunt met verwijzing", () => {
  const toetsing = "ArchUnit-regel: `ArchitectureRules.cycleFree`; Reviewpunt: [privacy](../../CODING_STANDARDS.md#privacy)";
  const files = { ...ok, [PRINCIPLES]: principles(principle({ toetsing })) };
  assert.deepEqual(withRepo(files, checkCompliance).problems, []);
});

test("een bron zonder matrixrij is gedekt als een principe met geldige toetsing ernaar verwijst", () => {

  const files = {
    [REGISTER_PATH]: register({ sources: [SOURCE_A, SOURCE_B], matrix: [ROW_A] }),
    [PRINCIPLES]: principles(principle({ bron: "`N-WCAG-2.2`" })),
  };
  assert.deepEqual(withRepo(files, checkCompliance).problems, []);
});

test("een principe zonder geldige toetsing dekt zijn bron niet", () => {

  const files = {
    [REGISTER_PATH]: register({ sources: [SOURCE_A, SOURCE_B], matrix: [ROW_A] }),
    [PRINCIPLES]: principles(principle({ bron: "`N-WCAG-2.2`", toetsing: "Reviewpunt: zonder link" })),
  };
  const problems = withRepo(files, checkCompliance).problems.join("\n");
  assert.match(problems, /bron N-WCAG-2\.2 heeft geen check of afwijkingsbesluit/);
});

test("een bron met een Open matrixrij wordt niet gedekt door alleen een getoetst principe", () => {

  const open = "| `N-WCAG-2.2` | 1.4.3 | Open | - | - | #999 |";
  const files = {
    [REGISTER_PATH]: register({ sources: [SOURCE_A, SOURCE_B], matrix: [ROW_A, open] }),
    [PRINCIPLES]: principles(principle({ bron: "`N-WCAG-2.2`" })),
  };
  assert.match(withRepo(files, checkCompliance).problems.join("\n"), /bron N-WCAG-2\.2 heeft geen check/);
});

test("meldt welke bronnen alleen via een principe gedekt zijn", () => {

  const files = {
    [REGISTER_PATH]: register({ sources: [SOURCE_A, SOURCE_B], matrix: [ROW_A] }),
    [PRINCIPLES]: principles(principle({ bron: "`N-WCAG-2.2` en `N-ASVS-5.0`" })),
  };
  const result = withRepo(files, checkCompliance);
  assert.deepEqual(result.problems, []);
  assert.deepEqual(result.viaPrincipe, ["N-WCAG-2.2"]);
});

test("principes.md noemt elke toetsingssoort die de check kent", () => {
  const text = readFileSync(new URL(`../${PRINCIPLES_PATH}`, import.meta.url), "utf8");
  for (const soort of TOETSING_SOORTEN) assert.ok(text.includes(soort), `${soort} ontbreekt in ${PRINCIPLES_PATH}`);
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
