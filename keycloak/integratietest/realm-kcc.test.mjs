// Integratietest van het realm-template (#505, seam 3 van epic #499): laadt keycloak-config-cli de realms die het template
// per tenant oplevert in een echte Keycloak (eigen image, met de mappers van #527)? Dezelfde kcc-versie en -instellingen als
// de compose-stack (`docker-compose/docker-compose.yaml`, service nl-portal-keycloak-config).
//
// Drie realms: `nlportal` uit een ontwikkeltenant (testinlogmiddel), `acceptatie` uit een tenant met DigiD en wallet zonder
// testinlogmiddel en `samllogin` uit een ontwikkeltenant met DigiD (gesimuleerd via SAML, zodat een echte SAML-login door het
// gegenereerde realm loopt). Er is alleen nog de client `nl-portal-backend`: geen SPA-client, geen m2m-client, geen token exchange.
// Wat de echte Keycloak toevoegt boven de statische realmtest (scripts/lib/realm.test.mjs):
// het laden slaagt (ook de mappertypen van het eigen image), een tweede run is idempotent en herstelt drift, en een echte
// code-flow met `private_key_jwt` (PS256) levert een ID-token.
import { before, describe, it } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { clientAssertion, maakClientSleutel } from "../../scripts/lib/clientsleutel.mjs";
import { genereerRealm } from "../../scripts/lib/realm.mjs";
import { leesTenantbestanden } from "../../scripts/lib/tenantbestand.mjs";
import { KC, admin, decodeJwt, login, waitForKeycloak } from "./stack.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
// Eén plek voor de kcc-versie: de compose-stack.
const COMPOSE = readFileSync(join(repoRoot, "docker-compose", "docker-compose.yaml"), "utf8");
const KCC_IMAGE = /image:\s*(adorsys\/keycloak-config-cli@sha256:[0-9a-f]{64})/.exec(COMPOSE)?.[1];
/** De letterlijke `IMPORT_*`- en `KEYCLOAK_AVAILABILITYCHECK_*`-instellingen uit het environment-blok van de kcc-service in de compose. */
function kccInstellingenUitCompose() {
  const blok = /\n    nl-portal-keycloak-config:\n([\s\S]*?)\n\n/.exec(COMPOSE)?.[1] ?? "";
  const uit = [];
  for (const m of blok.matchAll(/^\s{12}((?:IMPORT|KEYCLOAK_AVAILABILITYCHECK)_[A-Z_]+):\s*"?([^"\n#]+?)"?\s*$/gm)) {
    if (m[1] === "IMPORT_FILES_LOCATIONS") continue; // hier een andere (tijdelijke) map
    uit.push("-e", `${m[1]}=${m[2]}`);
  }
  // Een andere opmaak van de compose mag de test niet stil zonder kcc-instellingen laten draaien.
  assert.ok(uit.includes("IMPORT_VAR_SUBSTITUTION_ENABLED=true"), "de kcc-instellingen uit de compose zijn niet gelezen: opmaak veranderd?");
  return uit;
}
const NETWERK = "nlp-keycloak-it_default";
const WALLET_SECRET = "wallet-testgeheim";
const DOCKER = process.env.DOCKER_BIN ?? "docker";

const OIN_DEV = "00000099000000000001";
const OIN_ACC = "00000099000000000002";
const OIN_SAML = "00000099000000000003";
// De ontwikkeltenant is het demo-tenantbestand uit het repo (zoals `dev:setup` het leest).
const [DEV] = leesTenantbestanden(join(repoRoot, "docker-compose", "imports", "tenants"));
const ACC = { oin: OIN_ACC, naam: "Gemeente Het Wijde Veld", omgevingstype: "acceptatie", domeinen: ["mijn.hetwijdeveld.example"], thema: { titel: "Mijn Het Wijde Veld" }, inlogmiddelen: ["digid", "wallet"], minimumNiveau: "midden" };

const SAML = { oin: OIN_SAML, naam: "Gemeente Samlogin", omgevingstype: "ontwikkeling", domeinen: ["saml.localhost"], thema: { titel: "Samlogin" }, inlogmiddelen: ["digid"], minimumNiveau: "midden" };
const SAML_REDIRECT = `http://saml.localhost:3000/login/oauth2/code/${OIN_SAML}`;

const sleutel = maakClientSleutel();
const REDIRECT = `http://localhost:3000/login/oauth2/code/${OIN_DEV}`;
const CLIENT = "nl-portal-backend";

/** Draait keycloak-config-cli (zelfde instellingen als de compose) over de gegeven realms; geeft de uitvoer. */
function kcc(realms) {
  const map = mkdtempSync(join(tmpdir(), "kcc-realm-"));
  try {
    mkdirSync(map, { recursive: true });
    for (const [naam, inhoud] of Object.entries(realms)) writeFileSync(join(map, `${naam}.json`), JSON.stringify(inhoud, null, 2));
    const r = spawnSync(
      DOCKER,
      [
        "run", "--rm", "--network", NETWERK, "-v", `${map}:/config:ro`,
        "-e", "KEYCLOAK_URL=http://keycloak:18080", "-e", "KEYCLOAK_USER=admin", "-e", "KEYCLOAK_PASSWORD=admin",
        "-e", "IMPORT_FILES_LOCATIONS=/config/*.json", ...kccInstellingenUitCompose(),
        "-e", `KEYCLOAK_WALLET_CLIENT_SECRET=${WALLET_SECRET}`,
        KCC_IMAGE,
      ],
      { encoding: "utf8" },
    );
    if (r.error) throw new Error(`docker niet uit te voeren: ${r.error.message}`);
    const uit = `${r.stdout}${r.stderr}`;
    assert.equal(r.status, 0, `keycloak-config-cli faalde:\n${uit}`);
    assert.match(uit, /Using version: 26\.8\./, "kcc moet Keycloak 26.8 zien");
    assert.doesNotMatch(uit, /\b(WARN|ERROR)\b/, `kcc meldt een waarschuwing of fout:\n${uit}`);
    return uit;
  } finally {
    rmSync(map, { recursive: true, force: true });
  }
}

async function digidCertificaat() {
  const keys = await (await admin("/idp-sim/keys")).json();
  return keys.keys.find((k) => k.type === "RSA" && k.status === "ACTIVE" && k.use === "SIG").certificate;
}

let realmDev;
let realmAcc;
let realmSaml;
const rep = async (naam, pad = "") => (await admin(`/${naam}${pad}`)).json();

before(async () => {
  assert.ok(KCC_IMAGE, "docker-compose.yaml moet keycloak-config-cli op digest vastpinnen");
  await waitForKeycloak();
  realmDev = genereerRealm({ tenant: DEV, realm: "nlportal", brokerUrl: KC, clientSleutels: [sleutel.publiek] });
  const idp = {
    digid: { singleSignOnServiceUrl: "https://digid.example/sso", idpEntityId: "https://digid.example/idp", signingCertificate: await digidCertificaat() },
    wallet: { authorizationUrl: "https://wallet.example/auth", tokenUrl: "https://wallet.example/token", jwksUrl: "https://wallet.example/jwks", issuer: "https://wallet.example", clientId: "nl-portal" },
  };
  realmAcc = genereerRealm({ tenant: ACC, realm: "acceptatie", brokerUrl: "https://login.example", clientSleutels: [sleutel.publiek], idp });
  // De DigiD-simulator kent een SAML-client per realm van de broker: een kopie van die van realm `portal` voor `samllogin`.
  const entityId = `${KC}/realms/samllogin`;
  await simulatorClientVoor(entityId, `${entityId}/broker/digid/endpoint`);
  realmSaml = genereerRealm({
    tenant: SAML, realm: "samllogin", brokerUrl: KC, clientSleutels: [sleutel.publiek],
    idp: { digid: { singleSignOnServiceUrl: `${KC}/realms/idp-sim/protocol/saml`, idpEntityId: `${KC}/realms/idp-sim`, signingCertificate: await digidCertificaat() } },
  });
  kcc({ nlportal: realmDev, acceptatie: realmAcc, samllogin: realmSaml });
});

/** Maakt in de simulator-IdP (realm idp-sim) een SAML-client voor de broker van een ander realm, als kopie van die voor realm `portal`. */
async function simulatorClientVoor(entityId, consumerUrl) {
  const bron = (await (await admin("/idp-sim/clients?clientId=" + encodeURIComponent(`${KC}/realms/portal`))).json())[0];
  assert.ok(bron, "de simulator-client van realm portal ontbreekt");
  const { id, ...kopie } = bron;
  const bestaand = (await (await admin("/idp-sim/clients?clientId=" + encodeURIComponent(entityId))).json())[0];
  if (bestaand) await admin(`/idp-sim/clients/${bestaand.id}`, { method: "DELETE" });
  kopie.clientId = entityId;
  kopie.redirectUris = [consumerUrl];
  kopie.attributes = { ...kopie.attributes, saml_assertion_consumer_url_post: consumerUrl };
  kopie.protocolMappers?.forEach((m) => delete m.id);
  await admin("/idp-sim/clients", { method: "POST", body: JSON.stringify(kopie) });
}

describe("keycloak-config-cli laadt het realm-template", () => {
  it("maakt alle realms aan met de verwachte clients en zonder waarschuwing (zie kcc())", async () => {
    for (const [naam, verwacht] of [["nlportal", realmDev], ["acceptatie", realmAcc], ["samllogin", realmSaml]]) {
      const clients = await rep(naam, "/clients");
      for (const c of verwacht.clients) {
        const echt = clients.find((x) => x.clientId === c.clientId);
        assert.ok(echt, `${naam}: client ${c.clientId} ontbreekt`);
        assert.equal(echt.enabled, c.enabled !== false, `${naam}: ${c.clientId} enabled`);
      }
    }
  });

  it("nl-portal-backend staat als vertrouwelijke client met private_key_jwt, PS256 en PKCE S256", async () => {
    const [c] = (await rep("nlportal", `/clients?clientId=${CLIENT}`));
    assert.equal(c.clientAuthenticatorType, "client-jwt");
    assert.equal(c.publicClient, false);
    assert.equal(c.serviceAccountsEnabled, false);
    assert.equal(c.directAccessGrantsEnabled, false);
    assert.equal(c.attributes["token.endpoint.auth.signing.alg"], "PS256");
    assert.equal(c.attributes["pkce.code.challenge.method"], "S256");
    assert.equal(c.attributes["access.token.header.type.rfc9068"], "true");
    assert.equal(JSON.parse(c.attributes["jwks.string"]).keys[0].kid, sleutel.kid);
    assert.ok(!c.defaultClientScopes.includes("acr"), "scope acr hoort van nl-portal-backend af");
    assert.deepEqual(c.protocolMappers.map((m) => m.name).sort(), ["acr", "acting-subject-id", "audience", "inlogmiddel", "legal-subject-id", "test-acr", "test-bsn", "test-inlogmiddel"]);
  });

  it("de accountconsole staat uit en het accountrealm is niet bereikbaar voor gebruikers", async () => {
    for (const id of ["account", "account-console"]) {
      const [c] = await rep("nlportal", `/clients?clientId=${id}`);
      assert.equal(c.enabled, false, id);
    }
  });

  it("het testinlogmiddel: demo-gebruikers in het ontwikkelrealm, geen gebruikers en geen lokale login in het acceptatierealm", async () => {
    const dev = await rep("nlportal", "/users");
    assert.deepEqual(dev.map((u) => u.username).filter((n) => ["burger", "bedrijf"].includes(n)).sort(), ["burger"]);
    const burger = dev.find((u) => u.username === "burger");
    assert.deepEqual(burger.attributes.bsn, ["999993847"], "attribuut bsn blijft staan (unmanagedAttributePolicy)");
    assert.equal((await rep("acceptatie", "/users")).length, 0);
    assert.equal((await rep("acceptatie")).browserFlow, "alleen-koppelingen");
    assert.equal((await rep("nlportal")).browserFlow, "browser");
  });

  it("de koppelingen van het acceptatierealm: DigiD en wallet met alle mappers van het eigen image", async () => {
    const idps = await rep("acceptatie", "/identity-provider/instances");
    assert.deepEqual(idps.map((i) => i.alias).sort(), ["digid", "wallet"]);
    const digid = idps.find((i) => i.alias === "digid");
    assert.equal(digid.config.validateSignature, "true");
    assert.equal(digid.config.wantAssertionsSigned, "true");
    assert.equal(digid.config.signingCertificate, realmAcc.identityProviders[0].config.signingCertificate);
    assert.deepEqual(JSON.parse(digid.config.authnContextClassRefs), ["urn:oasis:names:tc:SAML:2.0:ac:classes:MobileTwoFactorContract"]);
    const mappers = await rep("acceptatie", "/identity-provider/instances/digid/mappers");
    assert.deepEqual(mappers.map((m) => m.identityProviderMapper).filter((m) => m.startsWith("nlportal-")).sort(), [
      "nlportal-saml-attribute-note-mapper", "nlportal-saml-attribute-note-mapper", "nlportal-saml-authncontext-note-mapper",
    ]);
    const walletMappers = await rep("acceptatie", "/identity-provider/instances/wallet/mappers");
    assert.equal(walletMappers.filter((m) => m.identityProviderMapper === "nlportal-oidc-claim-note-mapper").length, 3);
    const wallet = idps.find((i) => i.alias === "wallet");
    assert.notEqual(wallet.config.clientSecret, WALLET_SECRET, "Keycloak toont het geheim nooit terug");
  });

  it("een tweede run is idempotent en herstelt drift (IMPORT_CACHE_ENABLED=false)", async () => {
    const [c] = await rep("nlportal", `/clients?clientId=${CLIENT}`);
    c.redirectUris = [...c.redirectUris, "https://kwaadaardig.example/*"];
    c.attributes["token.endpoint.auth.signing.alg"] = "RS256";
    await admin(`/nlportal/clients/${c.id}`, { method: "PUT", body: JSON.stringify(c) });
    kcc({ nlportal: realmDev, acceptatie: realmAcc, samllogin: realmSaml });
    const [hersteld] = await rep("nlportal", `/clients?clientId=${CLIENT}`);
    assert.deepEqual([...hersteld.redirectUris].sort(), [...realmDev.clients.find((x) => x.clientId === CLIENT).redirectUris].sort());
    assert.equal(hersteld.attributes["token.endpoint.auth.signing.alg"], "PS256");
    assert.equal((await rep("acceptatie", "/identity-provider/instances")).length, 2);
  });
});

describe("code-flow met private_key_jwt tegen het geladen realm", () => {
  const wachtwoord = "test-wachtwoord-1";

  before(async () => {
    const bestaand = await rep("nlportal", "/users?username=flowtest&exact=true");
    for (const u of bestaand) await admin(`/nlportal/users/${u.id}`, { method: "DELETE" });
    await admin("/nlportal/users", {
      method: "POST",
      body: JSON.stringify({ username: "flowtest", enabled: true, emailVerified: true, firstName: "Flow", lastName: "Test", email: "flow@example.test", credentials: [{ type: "password", value: wachtwoord, temporary: false }] }),
    });
  });

  async function wissel(code, verifier, extra = {}) {
    const body = new URLSearchParams({ grant_type: "authorization_code", code, redirect_uri: REDIRECT, client_id: CLIENT, code_verifier: verifier, ...extra });
    if (!extra.client_secret) {
      body.set("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
      body.set("client_assertion", clientAssertion(sleutel.prive, { clientId: CLIENT, audience: `${KC}/realms/nlportal` }));
    }
    const r = await fetch(`${KC}/realms/nlportal/protocol/openid-connect/token`, { method: "POST", body });
    return { status: r.status, body: await r.json() };
  }

  const inloggen = () => login({ username: "flowtest", password: wachtwoord, realm: "nlportal", clientId: CLIENT, redirectUri: REDIRECT, formulier: true });

  it("een login met het testinlogmiddel levert met de client-assertion een ID-token", async () => {
    const { code, verifier } = await inloggen();
    assert.ok(code, "geen code ontvangen");
    const { status, body } = await wissel(code, verifier);
    assert.equal(status, 200, JSON.stringify(body));
    const id = decodeJwt(body.id_token);
    assert.equal(id.iss, `${KC}/realms/nlportal`);
    assert.equal(id.azp, CLIENT);
    assert.equal(id.preferred_username, undefined, "geen scope profile: de gebruikersnaam hoort niet in het token");
    assert.equal(id.email, undefined);
    assert.equal(body.refresh_token, undefined, "de BFF gebruikt geen refresh-token");
    assert.equal(id.acr, undefined, "geen koppeling, dus geen acr-claim van de client-mapper (en niet van de scope acr)");
    const at = JSON.parse(Buffer.from(body.access_token.split(".")[0], "base64url").toString());
    assert.equal(at.typ, "at+jwt");
    assert.ok([].concat(decodeJwt(body.access_token).aud).includes(CLIENT), "RFC 9068: aud");
  });

  it("een client_secret in plaats van een assertion wordt geweigerd", async () => {
    const { code, verifier } = await inloggen();
    const { status } = await wissel(code, verifier, { client_secret: "geraden" });
    assert.ok([400, 401].includes(status), `status ${status}`);
  });

  it("een assertion van een andere sleutel wordt geweigerd", async () => {
    const { code, verifier } = await inloggen();
    const vreemd = maakClientSleutel();
    const body = new URLSearchParams({
      grant_type: "authorization_code", code, redirect_uri: REDIRECT, client_id: CLIENT, code_verifier: verifier,
      client_assertion_type: "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
      client_assertion: clientAssertion(vreemd.prive, { clientId: CLIENT, audience: `${KC}/realms/nlportal` }),
    });
    const r = await fetch(`${KC}/realms/nlportal/protocol/openid-connect/token`, { method: "POST", body });
    assert.ok([400, 401].includes(r.status), `status ${r.status}`);
  });

  it("een redirect-URI buiten de exacte registratie wordt geweigerd", async () => {
    const r = await login({ username: "flowtest", password: wachtwoord, realm: "nlportal", clientId: CLIENT, redirectUri: `${REDIRECT}/extra`, formulier: true }).catch((e) => e);
    assert.ok(r instanceof Error || r.code === null, "een prefix-match van de redirect-URI mag niet werken");
  });
});

describe("tussenfase (ASVS 10.4/V13): geen lokale login, geen SPA-client en geen offline tokens buiten ontwikkeling", () => {
  const tokenEndpoint = (realm) => `${KC}/realms/${realm}/protocol/openid-connect/token`;

  it("het acceptatierealm heeft geen SPA-client en geen client met directe grants, wildcard-redirect of offline_access", async () => {
    const eigen = new Set(realmAcc.clients.filter((c) => c.enabled !== false).map((c) => c.clientId)); // niet de ingebouwde clients van Keycloak (admin-cli, broker, ...)
    const clients = (await rep("acceptatie", "/clients")).filter((c) => eigen.has(c.clientId));
    for (const weg of ["nl-portal", "nl-portal-m2m"]) assert.ok(!(await rep("acceptatie", `/clients?clientId=${weg}`)).length, `${weg} bestaat niet meer`);
    for (const c of clients) {
      assert.ok(!c.directAccessGrantsEnabled, `${c.clientId}: directAccessGrantsEnabled`);
      for (const uri of c.redirectUris ?? []) assert.doesNotMatch(uri, /[*+]/, `${c.clientId}: ${uri}`);
      assert.ok(![...(c.defaultClientScopes ?? []), ...(c.optionalClientScopes ?? [])].includes("offline_access"), `${c.clientId}: offline_access`);
    }
  });

  it("grant_type=password op het acceptatierealm wordt geweigerd, voor elke client", async () => {
    for (const client_id of ["nl-portal-backend"]) {
      const r = await fetch(tokenEndpoint("acceptatie"), { method: "POST", body: new URLSearchParams({ grant_type: "password", client_id, username: "burger", password: "demo-wachtwoord-1", scope: "openid offline_access" }) });
      assert.ok([400, 401].includes(r.status), `${client_id}: status ${r.status}`);
      assert.equal((await r.json()).access_token, undefined);
    }
  });

  it("grant_type=password op het ontwikkelrealm wordt ook geweigerd: nl-portal-backend heeft geen directe grants en er is geen SPA-client", async () => {
    const [burger] = await rep("nlportal", "/users?username=burger&exact=true");
    await admin(`/nlportal/users/${burger.id}/reset-password`, { method: "PUT", body: JSON.stringify({ type: "password", value: "demo-wachtwoord-1", temporary: false }) });
    for (const client_id of ["nl-portal-backend", "nl-portal"]) {
      const r = await fetch(tokenEndpoint("nlportal"), { method: "POST", body: new URLSearchParams({ grant_type: "password", client_id, username: "burger", password: "demo-wachtwoord-1", scope: "openid offline_access" }) });
      assert.ok([400, 401].includes(r.status), `${client_id}: status ${r.status}`);
      assert.equal((await r.json()).access_token, undefined);
    }
  });
});

describe("login met het testinlogmiddel en geen token exchange meer", () => {
  const WACHTWOORD = "Demo-Wachtwoord-1!";
  const tokenEndpoint = `${KC}/realms/nlportal/protocol/openid-connect/token`;

  async function burgerLogin(extra = {}) {
    const [burger] = await rep("nlportal", "/users?username=burger&exact=true");
    await admin(`/nlportal/users/${burger.id}/reset-password`, { method: "PUT", body: JSON.stringify({ type: "password", value: WACHTWOORD, temporary: false }) });
    const { code, verifier } = await login({ username: "burger", password: WACHTWOORD, realm: "nlportal", clientId: CLIENT, redirectUri: REDIRECT, formulier: true, ...extra });
    assert.ok(code, "geen code ontvangen");
    return { code, verifier };
  }

  async function wissel({ code, verifier }) {
    const body = new URLSearchParams({
      grant_type: "authorization_code", code, redirect_uri: REDIRECT, client_id: CLIENT, code_verifier: verifier,
      client_assertion_type: "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
      client_assertion: clientAssertion(sleutel.prive, { clientId: CLIENT, audience: `${KC}/realms/nlportal` }),
    });
    const r = await fetch(tokenEndpoint, { method: "POST", body });
    return { status: r.status, body: await r.json() };
  }

  it("burger logt in met nl-portal-backend (private_key_jwt PS256, PKCE, prompt=login) en de mappers geven acr=Basis, inlogmiddel=test en acting_subject_id", async () => {
    const { status, body } = await wissel(await burgerLogin({ prompt: "login" }));
    assert.equal(status, 200, JSON.stringify(body));
    const id = decodeJwt(body.id_token);
    assert.equal(id.iss, `${KC}/realms/nlportal`);
    assert.equal(id.azp, CLIENT);
    assert.equal(id.acr, "Basis");
    assert.equal(id.inlogmiddel, "test");
    assert.equal(id.acting_subject_id, "999993847");
    assert.ok(id.auth_time, "auth_time ontbreekt");
    assert.equal(id.middel, undefined, "de oude claim middel bestaat niet meer");
    assert.equal(id.aanvrager, undefined);
    // De mappers zetten de identiteit alleen in het ID-token: het access-token is voor de BFF zelf en draagt geen BSN.
    assert.ok(!JSON.stringify(decodeJwt(body.access_token)).includes("999993847"), "het access-token draagt geen BSN");
    assert.equal(body.refresh_token, undefined);
  });

  it("token exchange is niet beschikbaar: de client heeft het niet aan en er is geen m2m-client", async () => {
    const { code, verifier } = await burgerLogin();
    const eerste = await wissel({ code, verifier });
    assert.equal(eerste.status, 200, JSON.stringify(eerste.body));
    const [c] = await rep("nlportal", `/clients?clientId=${CLIENT}`);
    assert.ok(!("standard.token.exchange.enabled" in c.attributes) || c.attributes["standard.token.exchange.enabled"] !== "true");
    const poging = await fetch(tokenEndpoint, {
      method: "POST",
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:token-exchange", client_id: CLIENT,
        client_assertion_type: "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
        client_assertion: clientAssertion(sleutel.prive, { clientId: CLIENT, audience: `${KC}/realms/nlportal` }),
        subject_token: eerste.body.access_token, subject_token_type: "urn:ietf:params:oauth:token-type:access_token",
        requested_token_type: "urn:ietf:params:oauth:token-type:access_token",
      }),
    });
    assert.ok([400, 401, 403].includes(poging.status), `token exchange gaf status ${poging.status}`);
    assert.equal((await poging.json()).access_token, undefined);
    assert.equal((await rep("nlportal", "/clients?clientId=nl-portal-m2m")).length, 0);
  });

  it("een gebruiker die via een koppeling binnenkomt (alleen een gebruikersnaam als digid.s00000000:999990008) wordt door het gebruikersprofiel geaccepteerd", async () => {
    const naam = "digid.s00000000:999990008";
    for (const u of await rep("nlportal", `/users?username=${encodeURIComponent(naam)}&exact=true`)) await admin(`/nlportal/users/${u.id}`, { method: "DELETE" });
    const r = await admin("/nlportal/users", { method: "POST", body: JSON.stringify({ username: naam, enabled: true }) });
    assert.equal(r.status, 201);
  });
});

describe("SAML-login (gesimuleerd DigiD) tegen het gegenereerde realm", () => {
  const BSN = "999990008";
  const tokenEndpoint = `${KC}/realms/samllogin/protocol/openid-connect/token`;

  async function samlLogin() {
    const { code, verifier, error, pageText } = await login({ idp: "digid", username: "jan", password: "jan", realm: "samllogin", clientId: CLIENT, redirectUri: SAML_REDIRECT });
    assert.ok(code, `geen code: ${error ?? pageText}`);
    const r = await fetch(tokenEndpoint, {
      method: "POST",
      body: new URLSearchParams({
        grant_type: "authorization_code", code, redirect_uri: SAML_REDIRECT, client_id: CLIENT, code_verifier: verifier,
        client_assertion_type: "urn:ietf:params:oauth:client-assertion-type:jwt-bearer",
        client_assertion: clientAssertion(sleutel.prive, { clientId: CLIENT, audience: `${KC}/realms/samllogin` }),
      }),
    });
    const body = await r.json();
    assert.equal(r.status, 200, JSON.stringify(body));
    return body;
  }

  it("het ID-token heeft inlogmiddel digid, het gevraagde niveau als acr en de BSN van de actor", async () => {
    const body = await samlLogin();
    const id = decodeJwt(body.id_token);
    assert.equal(id.iss, `${KC}/realms/samllogin`);
    assert.equal(id.inlogmiddel, "digid");
    assert.equal(id.acr, "urn:oasis:names:tc:SAML:2.0:ac:classes:MobileTwoFactorContract");
    assert.equal(id.acting_subject_id, BSN);
    assert.ok(id.legal_subject_id === undefined || id.legal_subject_id === "");
    assert.ok(!JSON.stringify(decodeJwt(body.access_token)).includes(BSN), "het access-token draagt geen BSN");
  });

  it("gebruikersevents staan uit: er zijn geen events, en nergens een BSN in de events, de gebruiker of de Keycloak-log", async () => {
    await samlLogin();
    const realm = await rep("samllogin");
    assert.equal(realm.eventsEnabled, false);
    assert.equal(realm.adminEventsEnabled, true, "admin-events blijven aan, maar zonder representaties");
    assert.equal(realm.adminEventsDetailsEnabled, false);
    const events = await (await admin("/samllogin/events?max=200")).json();
    assert.deepEqual(events, [], "er mogen geen gebruikersevents zijn");
    const adminEvents = await (await admin("/samllogin/admin-events?max=200")).json();
    assert.ok(!JSON.stringify(adminEvents).includes(BSN), "BSN in een admin-event");
    const gebruikers = await rep("samllogin", "/users?max=50");
    assert.ok(gebruikers.length >= 1, "de gekoppelde gebruiker is niet aangemaakt");
    assert.ok(!JSON.stringify(gebruikers).includes(BSN), "BSN in de gebruikersrepresentatie (naam of attributen)");
    const log = spawnSync(DOCKER, ["logs", "nlp-keycloak-it-keycloak-1"], { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
    assert.equal(log.status, 0, log.stderr);
    assert.ok(!`${log.stdout}${log.stderr}`.includes(BSN), "BSN in de Keycloak-log");
  });

  it("de SAML-koppeling van het gegenereerde realm eist een ondertekende assertion en vraagt het minimumniveau op", async () => {
    const digid = await rep("samllogin", "/identity-provider/instances/digid");
    assert.equal(digid.config.validateSignature, "true");
    assert.equal(digid.config.wantAssertionsSigned, "true");
    assert.deepEqual(JSON.parse(digid.config.authnContextClassRefs), ["urn:oasis:names:tc:SAML:2.0:ac:classes:MobileTwoFactorContract"]);
    assert.equal(digid.config.authnContextComparisonType, "minimum");
  });
});
