import { test } from "node:test";
import assert from "node:assert/strict";
import { bouwRijen, leesStandaardsleutels, renderTabel, vervangTabel, controleer, START, EINDE } from "./secret-tabel.mjs";

const SCHEMA = {
  properties: {
    backend: {
      properties: {
        secretKeys: {
          properties: {
            redisPassword: { "x-secret": { nodig: "altijd", betekenis: "Wachtwoord van Redis" } },
            openklant2Token: { "x-secret": { nodig: "`modules.openklant2.enabled`", betekenis: "Token van OpenKlant 2" } },
          },
          "x-extraRijen": [{ key: "NL_PORTAL_GEHEIMEN_<OIN>_<NAAM>", nodig: "per tenantbestand", betekenis: "Tenantgeheimen" }],
        },
      },
    },
  },
};
const VALUES = ["backend:", "  secretKeys:", "    redisPassword: REDIS_PASSWORD", "    # commentaar", "    openklant2Token: OK2_TOKEN", "  secretVersion: \"\""].join("\n");
const TEMPLATE = [
  "            - name: REDIS_PASSWORD",
  "              valueFrom:",
  "                secretKeyRef:",
  "                  key: {{ $b.secretKeys.redisPassword }}",
  "            - name: OK2_TOKEN",
  "              valueFrom:",
  "                secretKeyRef:",
  "                  key: {{ $b.secretKeys.openklant2Token }}",
].join("\n");

test("leesStandaardsleutels leest alleen het secretKeys-blok", () => {
  assert.deepEqual(leesStandaardsleutels(VALUES), { redisPassword: "REDIS_PASSWORD", openklant2Token: "OK2_TOKEN" });
});

test("bouwRijen volgt de volgorde van het schema en voegt de extra rijen toe", () => {
  const rijen = bouwRijen(SCHEMA, leesStandaardsleutels(VALUES), TEMPLATE);
  assert.deepEqual(rijen.map((r) => r.key), ["REDIS_PASSWORD", "OK2_TOKEN", "NL_PORTAL_GEHEIMEN_<OIN>_<NAAM>"]);
});

test("bouwRijen faalt bij een sleutel zonder x-secret-beschrijving", () => {
  const schema = structuredClone(SCHEMA);
  delete schema.properties.backend.properties.secretKeys.properties.redisPassword["x-secret"];
  assert.throws(() => bouwRijen(schema, leesStandaardsleutels(VALUES), TEMPLATE), /redisPassword.*x-secret/);
});

test("bouwRijen faalt bij een sleutel zonder standaardwaarde in values.yaml", () => {
  assert.throws(() => bouwRijen(SCHEMA, { redisPassword: "REDIS_PASSWORD" }, TEMPLATE), /openklant2Token.*values\.yaml/);
});

test("bouwRijen faalt als de template de sleutel niet onder dezelfde env-naam gebruikt", () => {
  const template = TEMPLATE.replace("OK2_TOKEN", "ANDERE_NAAM");
  assert.throws(() => bouwRijen(SCHEMA, leesStandaardsleutels(VALUES), template), /openklant2Token.*template/);
});

test("renderTabel maakt een markdowntabel", () => {
  const tabel = renderTabel([{ key: "A", nodig: "altijd", betekenis: "Betekenis" }]);
  assert.equal(tabel, "| Key (standaardnaam) | Nodig wanneer | Betekenis |\n|---|---|---|\n| `A` | altijd | Betekenis |");
});

test("vervangTabel vervangt alleen het blok tussen de markers", () => {
  const md = `voor\n${START}\noud\n${EINDE}\nna\n`;
  assert.equal(vervangTabel(md, "nieuw"), `voor\n${START}\nnieuw\n${EINDE}\nna\n`);
  assert.throws(() => vervangTabel("geen markers", "x"), /marker/);
});

test("controleer is leeg bij een actuele tabel en meldt een verouderde", () => {
  const rijen = bouwRijen(SCHEMA, leesStandaardsleutels(VALUES), TEMPLATE);
  const actueel = vervangTabel(`${START}\n${EINDE}\n`, renderTabel(rijen));
  assert.equal(controleer(actueel, rijen), null);
  const verouderd = actueel.replace("OK2_TOKEN", "OUDE_NAAM");
  assert.match(controleer(verouderd, rijen), /niet actueel/);
});
