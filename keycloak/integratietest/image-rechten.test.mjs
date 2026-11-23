// Bewijs in het gebouwde image (nl-portal-keycloak:dev) dat het Keycloak-proces zijn eigen code niet kan overschrijven:
// een kwaadaardige provider of jar kan dan een herstart niet overleven. Alleen data/ en /tmp zijn schrijfbaar.
// Draait zonder de compose-stack; vereist alleen Docker en het image (`mise run keycloak:image`).
import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";

// DOCKER_BIN: de mise-taak zet het volledige pad (onder Git Bash kan Node `docker` niet via de PATH vinden).
// KEYCLOAK_IMAGE: het te toetsen image (standaard het lokaal gebouwde); een gemuteerd image bewijst dat de test faalt.
const IMAGE = process.env.KEYCLOAK_IMAGE ?? "nl-portal-keycloak:dev";
/** Draait een shell-opdracht als het standaardproces van het image (uid 1000, groep 0). */
function inImage(script) {
  const r = spawnSync(process.env.DOCKER_BIN ?? "docker", ["run", "--rm", "--entrypoint", "sh", IMAGE, "-c", script], { encoding: "utf8" });
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

  it("buiten data/ is niets in het image schrijfbaar: geen bestand en geen map, ook de provider-jar niet", () => {
    // Eén opdracht over alles, zodat ook een later toegevoegd pad meetelt; `-writable` kijkt naar de rechten van uid 1000 en groep 0.
    const r = inImage("find /opt/keycloak -path /opt/keycloak/data -prune -o -writable -print");
    assert.equal(r.status, 0, r.out);
    assert.equal(r.out.trim(), "", `schrijfbaar buiten data/:
${r.out}`);
  });

  it("de provider-jar bestaat, is leesbaar voor het proces en heeft geen uitvoerbit; een andere jar kan niet worden verwijderd", () => {
    const jar = "/opt/keycloak/providers/nlportal-keycloak-provider.jar";
    const r = inImage(`test -r ${jar} && test ! -x ${jar} && ! rm -f ${jar} 2>/dev/null && test -f ${jar}`);
    assert.equal(r.status, 0, r.out);
  });

  it("data/ en /tmp blijven schrijfbaar (nodig voor `start --optimized`)", () => {
    const r = inImage("touch /opt/keycloak/data/schrijftest /tmp/schrijftest && mkdir -p /opt/keycloak/data/tmp && touch /opt/keycloak/data/tmp/x");
    assert.equal(r.status, 0, r.out);
  });
});
