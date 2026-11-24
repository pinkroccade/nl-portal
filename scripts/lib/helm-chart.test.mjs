import { test } from "node:test";
import assert from "node:assert/strict";
import { bumpChartMinor, updateChartForRelease } from "./helm-chart.mjs";

const CHART = `apiVersion: v2
name: nl-portal
version: 0.1.0
appVersion: "0.1.0"
annotations:
  category: Government
  artifacthub.io/images: |
    - name: backend
      image: ghcr.io/pinkroccade/nl-portal-backend:0.1.0
    - name: frontend
      image: ghcr.io/pinkroccade/nl-portal-frontend:0.1.0
`;

test("bumpChartMinor verhoogt de minor en zet patch op 0", () => {
  assert.equal(bumpChartMinor("0.1.0"), "0.2.0");
  assert.equal(bumpChartMinor("1.4.7"), "1.5.0");
});

test("bumpChartMinor weigert een ongeldige versie", () => {
  assert.throws(() => bumpChartMinor("1.4"), /SemVer/);
});

test("updateChartForRelease zet appVersion, de image-annotatie en verhoogt de chartversie", () => {
  const { text, chartVersion } = updateChartForRelease(CHART, "0.2.0");
  assert.equal(chartVersion, "0.2.0");
  assert.match(text, /^version: 0\.2\.0$/m);
  assert.match(text, /^appVersion: "0\.2\.0"$/m);
  assert.match(text, /nl-portal-backend:0\.2\.0$/m);
  assert.match(text, /nl-portal-frontend:0\.2\.0$/m);
  assert.doesNotMatch(text, /0\.1\.0/);
  assert.match(text, /category: Government/);
});

test("updateChartForRelease verhoogt de chartversie ook als die al voorloopt op de app", () => {
  const ahead = CHART.replace("version: 0.1.0", "version: 0.4.2");
  assert.equal(updateChartForRelease(ahead, "0.2.0").chartVersion, "0.5.0");
});

test("updateChartForRelease laat een chart zonder image-annotatie heel", () => {
  const plain = "name: x\nversion: 1.0.0\nappVersion: \"1.0.0\"\n";
  const { text } = updateChartForRelease(plain, "1.1.0");
  assert.equal(text, "name: x\nversion: 1.1.0\nappVersion: \"1.1.0\"\n");
});

test("updateChartForRelease faalt zonder version of appVersion", () => {
  assert.throws(() => updateChartForRelease("name: x\n", "1.0.0"), /version/);
  assert.throws(() => updateChartForRelease("version: 1.0.0\n", "1.0.0"), /appVersion/);
});
