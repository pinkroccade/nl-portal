// De smoke-poort van de build-once-workflow (ADR 0019, #524 deel 1): tegen de draaiende quickstart-stack (`mise run smoke:stack`)
// logt `burger` in op De Bosrand via het inlogpad van de BFF, en een GraphQL-verzoek met de sessie geeft 200. Dat is bewust
// de minimale keten: frontend-proxy, backend, Redis-sessie, Keycloak (realm, mappers, private_key_jwt) en GraphQL. De uitgebreide
// controles (uitloggen, tokeninhoud, verkeerd wachtwoord) staan in bff-login.test.mjs; cross-tenant en browser horen bij deel 2.
//
// Het verkeer loopt via de frontend-nginx (BFF_ADRES=127.0.0.1:3000), zodat ook de proxyregels van de frontend meetellen.
import { before, describe, it } from "node:test";
import assert from "node:assert/strict";
import { PUBLIEK, startLogin, vulInlogformulierIn, zetDemoWachtwoord, browser } from "./bff-helpers.mjs";

const graphql = (b, headers = {}) =>
  b.verzoek(`${PUBLIEK}/graphql`, {
    method: "POST",
    headers: { "content-type": "application/json", origin: PUBLIEK, ...headers },
    body: JSON.stringify({ query: "{ __typename }" }),
  });

describe("smoke: burger logt in op De Bosrand en GraphQL antwoordt", () => {
  before(zetDemoWachtwoord);

  it("login via de BFF en daarna GraphQL 200", async () => {
    const b = browser();
    const { pagina } = await startLogin(b);
    const na = await vulInlogformulierIn(b, pagina);
    assert.equal(na.status, 302, `Keycloak stuurt naar de callback: ${na.tekst.slice(0, 300)}`);
    const terug = await b.verzoek(na.headers.location);
    assert.equal(terug.status, 200, `de callback is geweigerd (${terug.headers.location ?? ""})`);

    const sessie = JSON.parse((await b.verzoek(`${PUBLIEK}/api/public/sessie`)).tekst);
    assert.equal(sessie.ingelogd, true, "geen sessie na de callback");
    assert.equal(sessie.vertegenwoordigde, "burger");

    const csrf = b.cookie(new URL(PUBLIEK).hostname, "__Host-XSRF-TOKEN");
    assert.ok(csrf, "CSRF-cookie ontbreekt");
    const r = await graphql(b, { "x-xsrf-token": csrf.waarde });
    assert.equal(r.status, 200, `GraphQL met sessie: ${r.tekst.slice(0, 300)}`);
    assert.equal(JSON.parse(r.tekst).data?.__typename, "Query");
  });

  it("zonder sessie is GraphQL niet bereikbaar (de poort is niet leeg)", async () => {
    const r = await graphql(browser());
    assert.notEqual(r.status, 200, `GraphQL antwoordt zonder sessie: ${r.tekst.slice(0, 200)}`);
  });
});
