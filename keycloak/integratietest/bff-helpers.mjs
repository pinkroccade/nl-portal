// Gedeelde bouwstenen van de BFF-logintests (bff-login.test.mjs) en de smoke-poort (smoke.test.mjs): een browser die het
// inlogpad van de BFF volgt, het wachtwoordformulier van Keycloak invult en het demo-wachtwoord zet via de admin-API.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { Browser, leesFormulier } from "./browser.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const env = (naam, standaard) => process.env[naam] ?? standaard;

export const OIN = "00000099000000000001";
export const GEBRUIKER = "burger";
export const BSN = "999993847";
// Wegwerpwachtwoord van de demo-gebruiker: de test zet het zelf via de admin-API (het realm bewaart alleen een hash).
export const WACHTWOORD = "Demo-Wachtwoord-1!";
export const KEYCLOAK = env("KEYCLOAK_URL", "http://localhost:8082");
export const REALM = "nlportal";
export const BFF = env("BFF_ADRES", "127.0.0.1:8080");
// Het publieke adres van de tenant, zoals nginx het doorgeeft: de `Host` blijft staan, de verbinding gaat naar de backend.
export const PUBLIEK_HOST = env("PUBLIEK_HOST", "debosrand.localhost:3000");
export const PUBLIEK = `http://${PUBLIEK_HOST}`;

function adminWachtwoord() {
  if (process.env.KEYCLOAK_ADMIN_PASSWORD) return process.env.KEYCLOAK_ADMIN_PASSWORD;
  const dotenv = readFileSync(join(repoRoot, "docker-compose", ".env"), "utf8");
  const m = /^KEYCLOAK_ADMIN_PASSWORD=(.*)$/m.exec(dotenv);
  assert.ok(m, "KEYCLOAK_ADMIN_PASSWORD ontbreekt: draai eerst `mise run dev:setup`");
  return m[1].trim();
}

export async function zetDemoWachtwoord() {
  const token = (
    await (
      await fetch(`${KEYCLOAK}/realms/master/protocol/openid-connect/token`, {
        method: "POST",
        body: new URLSearchParams({ grant_type: "password", client_id: "admin-cli", username: "admin", password: adminWachtwoord() }),
      })
    ).json()
  ).access_token;
  const auth = { authorization: `Bearer ${token}`, "content-type": "application/json" };
  const [gebruiker] = await (await fetch(`${KEYCLOAK}/admin/realms/${REALM}/users?username=${GEBRUIKER}&exact=true`, { headers: auth })).json();
  assert.ok(gebruiker, `gebruiker ${GEBRUIKER} ontbreekt in realm ${REALM}: heeft kcc het realm geladen?`);
  const r = await fetch(`${KEYCLOAK}/admin/realms/${REALM}/users/${gebruiker.id}/reset-password`, {
    method: "PUT",
    headers: auth,
    body: JSON.stringify({ type: "password", value: WACHTWOORD, temporary: false }),
  });
  assert.equal(r.status, 204);
}

export function browser() {
  const kc = new URL(KEYCLOAK);
  return new Browser({
    [PUBLIEK_HOST]: { host: BFF.split(":")[0], port: Number(BFF.split(":")[1]) },
    [kc.host]: { host: kc.hostname === "localhost" ? "127.0.0.1" : kc.hostname, port: Number(kc.port) },
  });
}

/** Het wachtwoordformulier van Keycloak invullen en versturen; geeft de eerste respons (de redirect naar de callback of een fout). */
export async function vulInlogformulierIn(b, pagina, wachtwoord = WACHTWOORD) {
  const formulier = leesFormulier(pagina.tekst);
  assert.ok(formulier, `geen inlogformulier op ${pagina.url}: ${pagina.tekst.slice(0, 300)}`);
  formulier.velden.username = GEBRUIKER;
  formulier.velden.password = wachtwoord;
  return b.verzoek(new URL(formulier.actie, pagina.url).toString(), {
    method: "POST",
    body: new URLSearchParams(formulier.velden).toString(),
    headers: { "content-type": "application/x-www-form-urlencoded" },
  });
}

/** Start de login zoals de SPA (navigatie naar het inlogpad) en geeft de authorization-URL van Keycloak en de loginpagina. */
export async function startLogin(b) {
  const start = await b.verzoek(`${PUBLIEK}/oauth2/authorization/${OIN}?inlogmiddel=test&returnTo=/`);
  assert.equal(start.status, 302, `het inlogpad stuurt door naar Keycloak: ${start.tekst}`);
  const authUrl = new URL(start.headers.location);
  const pagina = await b.volg(authUrl.toString());
  assert.equal(pagina.status, 200, `Keycloak neemt het authorization-request aan: ${pagina.tekst.slice(0, 400)}`);
  return { start, authUrl, pagina };
}
