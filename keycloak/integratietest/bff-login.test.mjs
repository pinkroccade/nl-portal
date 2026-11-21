// De echte login van de BFF (#506, slice 1): de backend (`:app:bootRun`) tegen het echte Keycloak van de dev-compose (eigen image,
// realm uit keycloak/realm-template), gedreven als een browser. Bewijst wat de testprovider van de ITs niet kan: dat Keycloak het
// authorization-request van de BFF aanneemt (PS256 `private_key_jwt`, PKCE, `prompt=login`), de callback `iss` meestuurt, het
// ID-token `acr`, `inlogmiddel` en `auth_time` en de userinfo `acting_subject_id` bevat die `Inlogbeslisser` accepteert, de sessiecookie
// werkt en uitloggen de sessie bij Keycloak beëindigt.
//
// Draaien: `mise run keycloak:bff-login-test` (zet de stack zelf op en ruimt hem op). Los tegen een draaiende stack: zie de
// omgevingsvariabelen hieronder.
import { before, describe, it } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { Browser, decodeJwt, leesFormulier, metaRefresh } from "./browser.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const env = (naam, standaard) => process.env[naam] ?? standaard;

const OIN = "00000099000000000001";
const GEBRUIKER = "burger";
const BSN = "999993847";
// Wegwerpwachtwoord van de demo-gebruiker: de test zet het zelf via de admin-API (het realm bewaart alleen een hash).
const WACHTWOORD = "Demo-Wachtwoord-1!";
const KEYCLOAK = env("KEYCLOAK_URL", "http://localhost:8082");
const REALM = "nlportal";
const BFF = env("BFF_ADRES", "127.0.0.1:8080");
// Het publieke adres van de tenant, zoals nginx het doorgeeft: de `Host` blijft staan, de verbinding gaat naar de backend.
const PUBLIEK_HOST = env("PUBLIEK_HOST", "debosrand.localhost:3000");
const PUBLIEK = `http://${PUBLIEK_HOST}`;

function adminWachtwoord() {
  if (process.env.KEYCLOAK_ADMIN_PASSWORD) return process.env.KEYCLOAK_ADMIN_PASSWORD;
  const dotenv = readFileSync(join(repoRoot, "docker-compose", ".env"), "utf8");
  const m = /^KEYCLOAK_ADMIN_PASSWORD=(.*)$/m.exec(dotenv);
  assert.ok(m, "KEYCLOAK_ADMIN_PASSWORD ontbreekt: draai eerst `mise run dev:setup`");
  return m[1].trim();
}

async function zetDemoWachtwoord() {
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

function browser() {
  const kc = new URL(KEYCLOAK);
  return new Browser({
    [PUBLIEK_HOST]: { host: BFF.split(":")[0], port: Number(BFF.split(":")[1]) },
    [kc.host]: { host: kc.hostname === "localhost" ? "127.0.0.1" : kc.hostname, port: Number(kc.port) },
  });
}

/** Het wachtwoordformulier van Keycloak invullen en versturen; geeft de eerste respons (de redirect naar de callback of een fout). */
async function vulInlogformulierIn(b, pagina, wachtwoord = WACHTWOORD) {
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
async function startLogin(b) {
  const start = await b.verzoek(`${PUBLIEK}/oauth2/authorization/${OIN}?inlogmiddel=test&returnTo=/`);
  assert.equal(start.status, 302, `het inlogpad stuurt door naar Keycloak: ${start.tekst}`);
  const authUrl = new URL(start.headers.location);
  const pagina = await b.volg(authUrl.toString());
  assert.equal(pagina.status, 200, `Keycloak neemt het authorization-request aan: ${pagina.tekst.slice(0, 400)}`);
  return { start, authUrl, pagina };
}

describe("echte login van de BFF tegen Keycloak", () => {
  before(zetDemoWachtwoord);

  it("zonder sessie meldt het sessie-endpoint dat er niet is ingelogd en welke middelen aan staan", async () => {
    const b = browser();
    const r = await b.verzoek(`${PUBLIEK}/api/public/sessie`);
    assert.equal(r.status, 200);
    assert.deepEqual(JSON.parse(r.tekst), { ingelogd: false, loginPad: `/oauth2/authorization/${OIN}`, inlogmiddelen: ["test"] });
  });

  it("logt in met een PS256-clientassertion, PKCE en een vers wachtwoord, en uit bij Keycloak", async () => {
    const b = browser();
    const { start, authUrl, pagina } = await startLogin(b);

    // Het authorization-request: PKCE, state, nonce en `prompt=login` zodat Keycloak niet stil een bestaande sessie teruggeeft.
    const q = authUrl.searchParams;
    assert.equal(authUrl.origin + authUrl.pathname, `${KEYCLOAK}/realms/${REALM}/protocol/openid-connect/auth`);
    assert.equal(q.get("response_type"), "code");
    assert.equal(q.get("client_id"), "nl-portal-backend");
    assert.equal(q.get("code_challenge_method"), "S256");
    assert.ok(q.get("code_challenge"), "PKCE-challenge ontbreekt");
    assert.ok(q.get("state") && q.get("nonce"), "state en nonce ontbreken");
    assert.equal(q.get("prompt"), "login");
    assert.equal(q.get("redirect_uri"), `${PUBLIEK}/login/oauth2/code/${OIN}`);
    assert.equal(q.get("client_secret"), null, "nooit een client_secret");
    const flow = b.cookie(new URL(PUBLIEK).hostname, "__Host-nlp-flow");
    assert.ok(flow, "flowcookie ontbreekt");
    assert.match(flow.raw, /HttpOnly/i);
    assert.match(flow.raw, /SameSite=Lax/i);

    // Inloggen bij Keycloak; de callback komt met `iss` (RFC 9207) en een code terug.
    const na = await vulInlogformulierIn(b, pagina);
    assert.equal(na.status, 302, `Keycloak stuurt naar de callback: ${na.tekst.slice(0, 300)}`);
    const callback = new URL(na.headers.location);
    assert.equal(callback.origin + callback.pathname, `${PUBLIEK}/login/oauth2/code/${OIN}`);
    assert.equal(callback.searchParams.get("iss"), `${KEYCLOAK}/realms/${REALM}`);
    assert.equal(callback.searchParams.get("state"), q.get("state"));
    assert.ok(callback.searchParams.get("code"));

    // De callback: de BFF wisselt de code in (private_key_jwt) en laat `Inlogbeslisser` het ID-token toetsen. Een weigering is een 302 naar `/?inloggen=mislukt`.
    const terug = await b.verzoek(callback.toString());
    assert.equal(terug.status, 200, `de callback is geweigerd (${terug.headers.location ?? ""}): zie het log van de backend`);
    const doel = metaRefresh(terug.tekst);
    assert.equal(doel, "/", "de tussenpagina verwijst naar het returnTo-pad");
    const sessie = b.cookie(new URL(PUBLIEK).hostname, "__Host-Http-NLP");
    assert.ok(sessie, "sessiecookie ontbreekt");
    assert.match(sessie.raw, /HttpOnly/i);
    assert.match(sessie.raw, /SameSite=Strict/i);
    assert.match(sessie.raw, /Secure/i);
    assert.ok(sessie.waarde.length >= 43, "sessie-ID te kort");
    assert.equal(b.cookie(new URL(PUBLIEK).hostname, "__Host-nlp-flow"), undefined, "flowcookie is na de callback weg");

    // Ingelogd: afgeleide identiteit, nooit een token of BSN.
    const status = await b.verzoek(`${PUBLIEK}/api/public/sessie`);
    assert.equal(status.status, 200);
    const body = JSON.parse(status.tekst);
    assert.equal(body.ingelogd, true);
    assert.equal(body.vertegenwoordigde, "burger");
    assert.equal(body.inlogmiddel, "test");
    assert.equal(body.namensEenAnder, false);
    assert.ok(Date.parse(body.verlooptOp) > Date.now(), "verlooptOp ligt in de toekomst");
    assert.ok(body.inactiviteitSeconden > 0);
    assert.ok(!status.tekst.includes(BSN) && !/eyJ[A-Za-z0-9_-]{10,}/.test(status.tekst), "geen BSN of token in de sessiestatus");

    // Uitloggen: POST met CSRF-token en Origin, daarna RP-initiated logout bij Keycloak.
    const csrf = b.cookie(new URL(PUBLIEK).hostname, "__Host-XSRF-TOKEN");
    assert.ok(csrf, "CSRF-cookie ontbreekt");
    const uit = await b.verzoek(`${PUBLIEK}/logout`, { method: "POST", headers: { origin: PUBLIEK, "x-xsrf-token": csrf.waarde } });
    assert.equal(uit.status, 302);
    const eind = new URL(uit.headers.location);
    assert.equal(eind.origin + eind.pathname, `${KEYCLOAK}/realms/${REALM}/protocol/openid-connect/logout`);
    const hint = eind.searchParams.get("id_token_hint");
    assert.ok(hint, "id_token_hint ontbreekt");
    const claims = decodeJwt(hint);
    assert.equal(claims.iss, `${KEYCLOAK}/realms/${REALM}`);
    assert.equal(claims.acr, "Basis");
    assert.equal(claims.inlogmiddel, "test");
    assert.equal(claims.acting_subject_id, undefined, "het BSN staat niet in het ID-token: id_token_hint gaat de browser in");
    assert.ok(!eind.toString().includes(BSN), "geen BSN in de logout-redirect");
    assert.ok(claims.auth_time, "auth_time ontbreekt");
    assert.equal(eind.searchParams.get("post_logout_redirect_uri"), `${PUBLIEK}/`);
    const klaar = await b.volg(eind.toString(), {}, { stop: (u) => u.startsWith(PUBLIEK) });
    assert.ok(klaar.gestopt?.startsWith(`${PUBLIEK}/`) || klaar.status === 200, "Keycloak rondt het uitloggen af");

    // Bij Keycloak is de sessie weg: een stil authorization-request (prompt=none) vraagt opnieuw om inloggen.
    const stil = new URL(authUrl);
    stil.searchParams.set("prompt", "none");
    stil.searchParams.set("state", "x");
    const antwoord = await b.verzoek(stil.toString());
    assert.equal(antwoord.status, 302);
    assert.equal(new URL(antwoord.headers.location).searchParams.get("error"), "login_required");

    // En de portaalsessie is weg.
    const daarna = await b.verzoek(`${PUBLIEK}/api/public/sessie`);
    assert.equal(JSON.parse(daarna.tekst).ingelogd, false);
  });

  it("weigert een verkeerd wachtwoord zonder sessie", async () => {
    const b = browser();
    const { pagina } = await startLogin(b);
    const na = await vulInlogformulierIn(b, pagina, "fout-wachtwoord");
    assert.equal(na.status, 200, "Keycloak toont het formulier opnieuw");
    const status = await b.verzoek(`${PUBLIEK}/api/public/sessie`);
    assert.equal(JSON.parse(status.tekst).ingelogd, false);
  });
});
