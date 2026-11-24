import { test } from "node:test";
import assert from "node:assert/strict";
import { chartField, chartChangesSection, decideRelease, renderReleaseNotes } from "./chart-release.mjs";

const CHART = ['apiVersion: v2', 'version: 0.1.1', 'appVersion: "0.1.0"', ""].join("\n");

test("chartField leest version en appVersion zonder aanhalingstekens", () => {
  assert.equal(chartField(CHART, "version"), "0.1.1");
  assert.equal(chartField(CHART, "appVersion"), "0.1.0");
  assert.equal(chartField(CHART, "ontbreekt"), null);
});

const CHANGELOG = ["# Changelog", "", "intro", "", "## 0.1.1", "", "- fix een", "", "## 0.1.0", "", "First release.", ""].join("\n");

test("chartChangesSection geeft de sectie van één versie", () => {
  assert.equal(chartChangesSection(CHANGELOG, "0.1.1"), "- fix een");
  assert.equal(chartChangesSection(CHANGELOG, "0.1.0"), "First release.");
});

test("chartChangesSection geeft null voor een onbekende versie en matcht geen prefix", () => {
  assert.equal(chartChangesSection(CHANGELOG, "0.1"), null);
  assert.equal(chartChangesSection(CHANGELOG, "9.9.9"), null);
});

test("een tag-trigger publiceert als de chart nog niet in het register staat", () => {
  const d = decideRelease({ event: "tag", tagVersion: "0.1.1", chartVersion: "0.1.1", ociExists: false, tagExists: true, imagesExist: true });
  assert.deepEqual(d, { publish: true, createTag: false, reason: "Chart 0.1.1 wordt gepubliceerd." });
});

test("een tag die niet bij Chart.yaml past is een fout", () => {
  assert.throws(
    () => decideRelease({ event: "tag", tagVersion: "0.2.0", chartVersion: "0.1.1", ociExists: false, tagExists: true, imagesExist: true }),
    /0\.2\.0.*0\.1\.1/,
  );
});

test("een push naar main publiceert en tagt bij een nieuwe versie", () => {
  const d = decideRelease({ event: "push", chartVersion: "0.1.2", ociExists: false, tagExists: false, imagesExist: true });
  assert.equal(d.publish, true);
  assert.equal(d.createTag, true);
});

test("een push zonder versiebump (tag bestaat) publiceert niets", () => {
  const d = decideRelease({ event: "push", chartVersion: "0.1.1", ociExists: true, tagExists: true, imagesExist: true });
  assert.equal(d.publish, false);
  assert.match(d.reason, /bestaat al/);
});

test("een bestaande tag zonder artifact (app-release in uitvoering) wordt door de push niet overgenomen", () => {
  const d = decideRelease({ event: "push", chartVersion: "0.1.1", ociExists: false, tagExists: true, imagesExist: true });
  assert.equal(d.publish, false);
});

test("een artifact dat al bestaat wordt nooit opnieuw gepubliceerd, ook niet via de tag", () => {
  const d = decideRelease({ event: "tag", tagVersion: "0.1.1", chartVersion: "0.1.1", ociExists: true, tagExists: true, imagesExist: true });
  assert.equal(d.publish, false);
});

test("ontbreken de images van appVersion, dan wacht een push op de app-release en faalt een tag", () => {
  const push = decideRelease({ event: "push", chartVersion: "0.2.0", ociExists: false, tagExists: false, imagesExist: false });
  assert.equal(push.publish, false);
  assert.match(push.reason, /images/);
  assert.throws(
    () => decideRelease({ event: "tag", tagVersion: "0.2.0", chartVersion: "0.2.0", ociExists: false, tagExists: true, imagesExist: false }),
    /images/,
  );
});

test("release notes bevatten changelog, cosign verify met identity en issuer, en de docs-link", () => {
  const notes = renderReleaseNotes({
    version: "0.1.1",
    appVersion: "0.1.0",
    repository: "pinkroccade/nl-portal",
    ref: "refs/heads/main",
    digest: "sha256:abc",
    changes: "- fix een",
  });
  assert.match(notes, /- fix een/);
  assert.match(notes, /oci:\/\/ghcr\.io\/pinkroccade\/charts\/nl-portal --version 0\.1\.1/);
  assert.match(notes, /cosign verify ghcr\.io\/pinkroccade\/charts\/nl-portal@sha256:abc/);
  assert.match(notes, /--certificate-identity https:\/\/github\.com\/pinkroccade\/nl-portal\/\.github\/workflows\/publish-helm-chart\.yml@refs\/heads\/main/);
  assert.match(notes, /--certificate-oidc-issuer https:\/\/token\.actions\.githubusercontent\.com/);
  assert.match(notes, /how-to-handtekening-verifieren/);
  assert.match(notes, /appVersion 0\.1\.0/);
});

test("release notes zonder changelogsectie noemen dat expliciet", () => {
  const notes = renderReleaseNotes({ version: "1.0.0", appVersion: "1.0.0", repository: "o/r", ref: "refs/tags/chart-nl-portal-1.0.0", digest: "sha256:x", changes: null });
  assert.match(notes, /Geen changelog/);
});
