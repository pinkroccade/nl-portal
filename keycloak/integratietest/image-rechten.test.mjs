// Bewijs in het gebouwde image (nl-portal-keycloak:dev) dat het Keycloak-proces zijn eigen code niet kan overschrijven:
// een kwaadaardige provider of jar kan dan een herstart niet overleven. Alleen data/ en /tmp zijn schrijfbaar.
// Draait zonder de compose-stack; vereist alleen Docker en het image (`mise run keycloak:image`).
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";

// DOCKER_BIN: de mise-taak zet het volledige pad (onder Git Bash kan Node `docker` niet via de PATH vinden).
/** Draait een shell-opdracht als het standaardproces van het image (uid 1000, groep 0). */
function inImage(script) {
  const r = spawnSync(process.env.DOCKER_BIN ?? "docker", ["run", "--rm", "--entrypoint", "sh", "nl-portal-keycloak:dev", "-c", script], { encoding: "utf8" });
  if (r.error) throw new Error(`docker niet uit te voeren: ${r.error.message}`);
  return { status: r.status, out: `${r.stdout}${r.stderr}` };
}

const CODEMAPPEN = [".", "bin", "conf", "lib", "lib/quarkus", "lib/lib/main", "providers", "themes"];

describe("Rechten in het Keycloak-image", () => {
  it("draait als uid 1000, niet als root", () => {
    const r = inImage("id -u");
    assert.equal(r.out.trim(), "1000");
  });

  for (const map of CODEMAPPEN) {
    it(`/opt/keycloak/${map} is niet schrijfbaar en er kan geen bestand in worden gemaakt`, () => {
      const r = inImage(`test ! -w /opt/keycloak/${map} && ! touch /opt/keycloak/${map}/schrijftest 2>/dev/null`);
      assert.equal(r.status, 0, `schrijven naar ${map} is mogelijk: ${r.out}`);
    });
  }

  it("de provider-jar en andere jars kunnen niet worden overschreven of verwijderd", () => {
    const r = inImage(
      "set -e; ! test -w /opt/keycloak/providers/nlportal-keycloak-provider.jar; " +
        "! (echo x >> /opt/keycloak/providers/nlportal-keycloak-provider.jar) 2>/dev/null; " +
        "! rm -f /opt/keycloak/providers/nlportal-keycloak-provider.jar 2>/dev/null; " +
        "test -f /opt/keycloak/providers/nlportal-keycloak-provider.jar; " +
        "j=$(ls /opt/keycloak/lib/lib/main/*.jar | head -1); ! test -w \"$j\"",
    );
    assert.equal(r.status, 0, r.out);
  });

  it("data/ en /tmp blijven schrijfbaar (nodig voor `start --optimized`)", () => {
    const r = inImage("touch /opt/keycloak/data/schrijftest /tmp/schrijftest && mkdir -p /opt/keycloak/data/tmp && touch /opt/keycloak/data/tmp/x");
    assert.equal(r.status, 0, r.out);
  });
});
