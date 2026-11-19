import { test } from "node:test";
import assert from "node:assert/strict";
import { takenZonderShell } from "./check-mise-shell.mjs";

const taak = (naam, regels) => `[tasks."${naam}"]\ndescription = "x"\n${regels}\n`;

test("een taak met een omgevingsvariabele voor het commando heeft een shell nodig", () => {
  assert.deepEqual(takenZonderShell(taak("a:b", `run = 'RUN_MODE="x" docker compose up'`)), ["a:b"]);
});

test("dezelfde taak met shell = bash -c is in orde", () => {
  assert.deepEqual(takenZonderShell(taak("a:b", `shell = "bash -c"\nrun = 'RUN_MODE="x" docker compose up'`)), []);
});

test("een gewoon commando heeft geen shell nodig", () => {
  assert.deepEqual(takenZonderShell(taak("a:b", `run = "node scripts/x.mjs"`)), []);
});

test("${VAR:-x}, $(...) en ./gradlew zijn bash-syntaxis", () => {
  assert.deepEqual(takenZonderShell(taak("a", `run = 'echo "\${X:-y}"'`)), ["a"]);
  assert.deepEqual(takenZonderShell(taak("b", `run = 'echo $(date)'`)), ["b"]);
  assert.deepEqual(takenZonderShell(taak("c", `run = "./gradlew build"`)), ["c"]);
});

test("een script over meerdere regels met if en [ ] heeft een shell nodig", () => {
  const tekst = taak("a", `run = """\nif [ -n "$X" ]; then\n  echo ja\nfi\n"""`);
  assert.deepEqual(takenZonderShell(tekst), ["a"]);
});

test("een lijst van commando's zonder bash-syntaxis is in orde", () => {
  assert.deepEqual(takenZonderShell(taak("a", `run = ["pnpm lint", "pnpm lint:css"]`)), []);
});

test("de omschrijving en de env-tabel tellen niet mee", () => {
  const tekst = `[tasks."a"]\ndescription = "gebruik \${X} of RUN_MODE=x"\nenv = { PERF_PROFILE = "load" }\nrun = "k6 run x.ts"\n`;
  assert.deepEqual(takenZonderShell(tekst), []);
});

test("taken zonder run (bestandstaken) worden overgeslagen en elke taak wordt apart beoordeeld", () => {
  const tekst = `${taak("zonder", `depends = ["a"]`)}\n${taak("goed", `shell = "bash -c"\nrun = 'X=1 cmd'`)}\n${taak("fout", `run = 'X=1 cmd'`)}`;
  assert.deepEqual(takenZonderShell(tekst), ["fout"]);
});

test("NAAM=waarde als argument van een commando is geen omgevingsvariabele-voorvoegsel", () => {
  assert.deepEqual(takenZonderShell(taak("a", `run = "k6 inspect -e PERF_USERNAME=check x.ts && k6 inspect -e PERF_X=1 y.ts"`)), []);
});

test("NAAM=waarde na && of ; telt wel als voorvoegsel", () => {
  assert.deepEqual(takenZonderShell(taak("a", `run = "cd x && PERF_X=1 k6 run y.ts"`)), ["a"]);
});
