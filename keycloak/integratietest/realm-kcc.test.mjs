// Integratietest van het realm-template (#505, seam 3 van epic #499): laadt keycloak-config-cli de realms die het template
// per tenant oplevert in een echte Keycloak (eigen image, met de mappers van #527)? Dezelfde kcc-versie en -instellingen als
// de compose-stack (`docker-compose/docker-compose.yaml`, service nl-portal-keycloak-config).
//
// Twee realms: `nlportal` uit een ontwikkeltenant (testinlogmiddel, de huidige SPA-login) en `acceptatie` uit een tenant met
// DigiD en wallet zonder testinlogmiddel. Wat de echte Keycloak toevoegt boven de statische realmtest (scripts/lib/realm.test.mjs):
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
import { KC, admin, decodeJwt, login, waitForKeycloak } from "./stack.mjs";

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
// Eén plek voor de kcc-versie: de compose-stack.
const KCC_IMAGE = /image:\s*(adorsys\/keycloak-config-cli@sha256:[0-9a-f]{64})/.exec(readFileSync(join(repoRoot, "docker-compose", "docker-compose.yaml"), "utf8"))?.[1];
const NETWERK = "nlp-keycloak-it_default";
const M2M_SECRET = "m2m-testgeheim";
const WALLET_SECRET = "wallet-testgeheim";
const DOCKER = process.env.DOCKER_BIN ?? "docker";

const OIN_DEV = "00000099000000000001";
const OIN_ACC = "00000099000000000002";
const DEV = { oin: OIN_DEV, naam: "Gemeente De Bosrand", omgevingstype: "ontwikkeling", domeinen: ["localhost"], thema: { titel: "Mijn De Bosrand" }, inlogmiddelen: ["test"] };
const ACC = { oin: OIN_ACC, naam: "Gemeente Het Wijde Veld", omgevingstype: "acceptatie", domeinen: ["mijn.hetwijdeveld.example"], thema: { titel: "Mijn Het Wijde Veld" }, inlogmiddelen: ["digid", "wallet"], minimumNiveau: "midden" };

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
        "-e", "KEYCLOAK_AVAILABILITYCHECK_ENABLED=true", "-e", "KEYCLOAK_AVAILABILITYCHECK_TIMEOUT=120s",
        "-e", "IMPORT_FILES_LOCATIONS=/config/*.json", "-e", "IMPORT_CACHE_ENABLED=false",
        "-e", "IMPORT_VAR_SUBSTITUTION_ENABLED=true", "-e", "IMPORT_VAR_SUBSTITUTION_UNDEFINED_THROWS_EXCEPTIONS=true",
        "-e", `KEYCLOAK_M2M_CLIENT_SECRET=${M2M_SECRET}`, "-e", `KEYCLOAK_WALLET_CLIENT_SECRET=${WALLET_SECRET}`,
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
  kcc({ nlportal: realmDev, acceptatie: realmAcc });
});

describe("keycloak-config-cli laadt het realm-template", () => {
  it("maakt beide realms aan met de verwachte clients en zonder waarschuwing (zie kcc())", async () => {
    for (const [naam, verwacht] of [["nlportal", realmDev], ["acceptatie", realmAcc]]) {
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
    assert.deepEqual(c.protocolMappers.map((m) => m.name).sort(), ["acr", "acting-subject-id", "inlogmiddel", "legal-subject-id"]);
  });

  it("de m2m-client krijgt zijn geheim uit de omgeving van kcc, niet uit het realm", async () => {
    const [c] = await rep("nlportal", "/clients?clientId=nl-portal-m2m");
    const geheim = await (await admin(`/nlportal/clients/${c.id}/client-secret`)).json();
    assert.equal(geheim.value, M2M_SECRET);
    assert.ok(!JSON.stringify(realmDev).includes(M2M_SECRET));
  });

  it("de accountconsole staat uit en het accountrealm is niet bereikbaar voor gebruikers", async () => {
    for (const id of ["account", "account-console"]) {
      const [c] = await rep("nlportal", `/clients?clientId=${id}`);
      assert.equal(c.enabled, false, id);
    }
  });

  it("het testinlogmiddel: demo-gebruikers in het ontwikkelrealm, geen gebruikers en geen lokale login in het acceptatierealm", async () => {
    const dev = await rep("nlportal", "/users");
    assert.deepEqual(dev.map((u) => u.username).filter((n) => ["burger", "bedrijf"].includes(n)).sort(), ["bedrijf", "burger"]);
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
    kcc({ nlportal: realmDev, acceptatie: realmAcc });
    const [hersteld] = await rep("nlportal", `/clients?clientId=${CLIENT}`);
    assert.deepEqual(hersteld.redirectUris, [REDIRECT]);
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
    assert.equal(id.preferred_username, "flowtest");
    assert.equal(id.acr, undefined, "geen koppeling, dus geen acr-claim van de client-mapper (en niet van de scope acr)");
    const at = JSON.parse(Buffer.from(body.access_token.split(".")[0], "base64url").toString());
    assert.equal(at.typ, "at+jwt");
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

describe("de huidige SPA-login en token exchange blijven werken (tot de BFF van #506)", () => {
  const tokens = async (client, body) => {
    const r = await fetch(`${KC}/realms/nlportal/protocol/openid-connect/token`, { method: "POST", body: new URLSearchParams({ client_id: client, ...body }) });
    return { status: r.status, body: await r.json() };
  };

  it("burger logt in via nl-portal (middel-claim) en de m2m-client wisselt het token om voor de aanvrager met BSN", async () => {
    const [burger] = await rep("nlportal", "/users?username=burger&exact=true");
    await admin(`/nlportal/users/${burger.id}/reset-password`, { method: "PUT", body: JSON.stringify({ type: "password", value: "demo-wachtwoord-1", temporary: false }) });
    const browser = await tokens("nl-portal", { grant_type: "password", username: "burger", password: "demo-wachtwoord-1" });
    assert.equal(browser.status, 200, JSON.stringify(browser.body));
    const claims = decodeJwt(browser.body.access_token);
    assert.equal(claims.middel, "digid");
    assert.ok(!JSON.stringify(claims).includes("999993847"), "het browsertoken draagt geen BSN");
    assert.ok([].concat(claims.aud).includes("nl-portal-m2m"));
    const omgewisseld = await tokens("nl-portal-m2m", {
      grant_type: "urn:ietf:params:oauth:grant-type:token-exchange",
      client_secret: M2M_SECRET,
      subject_token: browser.body.access_token,
      subject_token_type: "urn:ietf:params:oauth:token-type:access_token",
      requested_token_type: "urn:ietf:params:oauth:token-type:access_token",
    });
    assert.equal(omgewisseld.status, 200, JSON.stringify(omgewisseld.body));
    assert.equal(decodeJwt(omgewisseld.body.access_token).aanvrager.bsn, "999993847");
  });

  it("een gebruiker die via een koppeling binnenkomt (alleen een gebruikersnaam als digid.s00000000:999990008) wordt door het gebruikersprofiel geaccepteerd", async () => {
    const naam = "digid.s00000000:999990008";
    for (const u of await rep("nlportal", `/users?username=${encodeURIComponent(naam)}&exact=true`)) await admin(`/nlportal/users/${u.id}`, { method: "DELETE" });
    const r = await admin("/nlportal/users", { method: "POST", body: JSON.stringify({ username: naam, enabled: true }) });
    assert.equal(r.status, 201);
  });
});
