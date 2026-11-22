// Statische realmtest (#505, seam 3 van epic #499): toetst de realms die het ene template per tenant oplevert.
// De test kijkt naar het gegenereerde realm, niet naar het template of de generator.
import assert from "node:assert/strict";
import { test } from "node:test";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { DEV_BROKER_URL, TENANTS_MAP } from "../dev-setup.mjs";
import { maakClientSleutel } from "./clientsleutel.mjs";
import { genereerRealm } from "./realm.mjs";
import { leesTenantbestanden } from "./tenantbestand.mjs";

const sleutel = maakClientSleutel();
const OIN = "00000099000000000001";

// De demo-tenants uit het repo (`docker-compose/imports/tenants`), gelezen zoals `dev:setup` ze leest: de realmtest toetst dus
// ook het tenantbestand en `base.yaml`, niet alleen een kopie ervan.
const REPO = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const DEMO_TENANTS = leesTenantbestanden(join(REPO, TENANTS_MAP));
const [DEV] = DEMO_TENANTS;
const OTA = { ...DEV, omgevingstype: "test", domeinen: ["mijn.test.example"] };
const ACC = { oin: OIN, naam: "Gemeente De Bosrand", omgevingstype: "acceptatie", domeinen: ["mijn.debosrand.example"], thema: { titel: "Mijn De Bosrand" }, inlogmiddelen: ["digid", "wallet"], minimumNiveau: "midden" };
const PRD = { ...ACC, omgevingstype: "productie", inlogmiddelen: ["digid"], minimumNiveau: "substantieel" };
const PRD_WALLET = { ...PRD, inlogmiddelen: ["digid", "wallet"] };

const IDP = {
  digid: { singleSignOnServiceUrl: "https://digid.example/sso", idpEntityId: "https://digid.example/idp", signingCertificate: "MIIBcertificaat" },
  wallet: { authorizationUrl: "https://wallet.example/auth", tokenUrl: "https://wallet.example/token", jwksUrl: "https://wallet.example/jwks", issuer: "https://wallet.example", clientId: "nl-portal" },
};
const basis = (tenant, extra = {}) => ({
  tenant,
  realm: "nlportal",
  brokerUrl: tenant.omgevingstype === "ontwikkeling" ? DEV_BROKER_URL : "https://login.example",
  clientSleutels: [sleutel.publiek],
  idp: IDP,
  ...extra,
});
const realm = (tenant, extra) => genereerRealm(basis(tenant, extra));

const client = (r, id) => r.clients.find((c) => c.clientId === id);
const noteMappers = (c) => c.protocolMappers.filter((m) => m.protocolMapper === "oidc-usersessionmodel-note-mapper");
const idpMappers = (r, alias) => r.identityProviderMappers.filter((m) => m.identityProviderAlias === alias);
const ALLE = {
  ...Object.fromEntries(DEMO_TENANTS.map((t) => [`demo-tenant ${t.oin}`, t])),
  acceptatie: ACC,
  productie: PRD,
  "productie met wallet": PRD_WALLET,
};
const NIET_DEV = { acceptatie: ACC, test: OTA, productie: PRD, "productie met wallet": PRD_WALLET };

for (const [naam, tenant] of Object.entries(ALLE)) {
  test(`${naam}: de enige client is nl-portal-backend (geen SPA-client, geen m2m-client); accountconsole staat uit`, () => {
    const r = realm(tenant);
    const aan = r.clients.filter((c) => c.enabled !== false).map((c) => c.clientId);
    assert.deepEqual(aan, ["nl-portal-backend"]);
    assert.ok(!(r.clientScopes ?? []).some((sc) => sc.name === "aanvrager"), "de scope aanvrager hoorde bij de token exchange");
    for (const id of ["account", "account-console"]) assert.equal(client(r, id).enabled, false, id);
  });

  test(`${naam}: nl-portal-backend gebruikt private_key_jwt met PS256, PKCE S256, at+jwt en exacte URI's`, () => {
    const r = realm(tenant);
    const c = client(r, "nl-portal-backend");
    assert.equal(c.publicClient, false);
    assert.equal(c.clientAuthenticatorType, "client-jwt");
    assert.equal(c.secret, undefined);
    assert.equal(c.standardFlowEnabled, true);
    for (const vlag of ["implicitFlowEnabled", "directAccessGrantsEnabled", "serviceAccountsEnabled"]) assert.equal(c[vlag], false, vlag);
    assert.equal(c.attributes["token.endpoint.auth.signing.alg"], "PS256");
    assert.equal(c.attributes["pkce.code.challenge.method"], "S256");
    assert.equal(c.attributes["access.token.header.type.rfc9068"], "true");
    assert.equal(c.attributes["use.jwks.string"], "true");
    assert.ok(Number(c.attributes["token.endpoint.auth.signing.max.exp"]) <= 60);
    const jwks = JSON.parse(c.attributes["jwks.string"]);
    assert.deepEqual(jwks.keys.map((k) => [k.kid, k.alg, k.use, k.kty]), [[sleutel.kid, "PS256", "sig", "RSA"]]);
    assert.equal(jwks.keys[0].d, undefined, "de private sleutel hoort nooit in het realm");
    const origins = tenant.domeinen.map((domein) => (tenant.omgevingstype === "ontwikkeling" ? `http://${domein}:3000` : `https://${domein}`));
    assert.deepEqual(c.redirectUris, origins.map((origin) => `${origin}/login/oauth2/code/${tenant.oin}`));
    assert.equal(c.attributes["post.logout.redirect.uris"], origins.map((origin) => `${origin}/`).join("##"));
    // B-L3: het back-channel-endpoint bestaat nog niet (T24); een URL laat Keycloak elk sessie-einde stil mislukt aanroepen.
    assert.ok(!Object.keys(c.attributes).some((k) => k.startsWith("backchannel.logout")), "geen back-channel-logout registreren zonder endpoint");
    for (const uri of [...c.redirectUris, ...c.attributes["post.logout.redirect.uris"].split("##")]) assert.doesNotMatch(uri, /[*+]/, uri);
    assert.deepEqual(c.webOrigins, []);
  });

  test(`${naam}: de scope acr is van nl-portal-backend af en de client-mappers zetten de notities als claim in het ID-token`, () => {
    const c = client(realm(tenant), "nl-portal-backend");
    assert.ok(!c.defaultClientScopes.includes("acr"));
    assert.ok(!(c.optionalClientScopes ?? []).includes("acr"));
    const notities = noteMappers(c).map((m) => m.config["user.session.note"]).sort();
    assert.deepEqual(notities, ["acr", "acting_subject_id", "identity_provider", "legal_subject_id"]);
    for (const m of noteMappers(c)) {
      assert.equal(m.protocolMapper, "oidc-usersessionmodel-note-mapper");
      assert.equal(m.config["access.token.claim"], "false");
      // Het BSN (acting_subject_id) staat alleen in userinfo: het ID-token gaat als id_token_hint de browser in.
      const bsn = m.config["user.session.note"] === "acting_subject_id";
      assert.equal(m.config["id.token.claim"], String(!bsn), m.name);
      assert.equal(m.config["userinfo.token.claim"], String(bsn), m.name);
    }
  });

  test(`${naam}: elke koppeling heeft een mapper voor elke notitie die een client-mapper leest, en er is geen identiteitskoppeling`, () => {
    const r = realm(tenant);
    const gelezen = noteMappers(client(r, "nl-portal-backend")).map((m) => m.config["user.session.note"]).filter((n) => n !== "identity_provider");
    for (const idp of r.identityProviders) {
      const gezet = idpMappers(r, idp.alias).map((m) => m.config.note).filter(Boolean);
      for (const notitie of gelezen) assert.ok(gezet.includes(notitie), `${idp.alias}: geen mapper voor ${notitie}`);
      assert.equal(idp.linkOnly, false);
      assert.equal(idp.storeToken, false);
      assert.equal(idp.trustEmail, false);
      assert.equal(idp.firstBrokerLoginFlowAlias, "auto-broker-login");
      assert.equal(idp.config.syncMode, "FORCE");
    }
    const flow = r.authenticationFlows.find((f) => f.alias === "auto-broker-login");
    assert.deepEqual(flow.authenticationExecutions.map((e) => [e.authenticator, e.userSetupAllowed]), [["idp-create-user-if-unique", false]]);
  });

  test(`${naam}: realm-instellingen zijn dicht (geen registratie, geen wachtwoordherstel) en sessies zijn niet langer dan het portaal`, () => {
    const r = realm(tenant);
    assert.equal(r.registrationAllowed, false);
    assert.equal(r.resetPasswordAllowed, false);
    assert.equal(r.rememberMe, false);
    assert.equal(r.sslRequired, tenant.omgevingstype === "ontwikkeling" ? "external" : "all");
    assert.equal(r.ssoSessionIdleTimeout, 15 * 60);
    assert.equal(r.ssoSessionMaxLifespan, 8 * 60 * 60);
    assert.equal(r.defaultSignatureAlgorithm, "RS256");
  });

  test(`${naam}: het gebruikersprofiel eist niets van een gebruiker die via een koppeling binnenkomt (geen e-mail, naam of authenticationMethod)`, () => {
    const [profiel] = realm(tenant).components["org.keycloak.userprofile.UserProfileProvider"];
    const config = JSON.parse(profiel.config["kc.user.profile.config"][0]);
    for (const attribuut of config.attributes) assert.equal(attribuut.required, undefined, `${attribuut.name} is verplicht`);
    assert.deepEqual(config.attributes.map((a) => a.name), ["username", "email", "firstName", "lastName"]);
    // Een gebruikersnaam als digid.s00000000:999990008 mag niet op een tekencontrole stranden.
    assert.deepEqual(Object.keys(config.attributes.find((a) => a.name === "username").validations ?? {}), ["length"]);
    // Buiten ontwikkeling kan niemand een gebruikersattribuut schrijven dat een claim kan worden (B-M1).
    assert.equal(config.unmanagedAttributePolicy, tenant.omgevingstype === "ontwikkeling" ? "ADMIN_EDIT" : "ADMIN_VIEW");
  });

  test(`${naam}: er staat geen geheim in het realm; geheimen verwijzen naar kcc-substitutie`, () => {
    const tekst = JSON.stringify(realm(tenant));
    assert.doesNotMatch(tekst, /\{\{[A-Za-z0-9_.]+\}\}/, "onopgeloste placeholder");
    assert.equal(tekst.includes(sleutel.prive.d), false);
    for (const m of tekst.matchAll(/"(secret|clientSecret)":"([^"]*)"/g)) assert.match(m[2], /^\$\(env:[A-Z0-9_]+\)$/, `${m[1]} moet een substitutie zijn`);
  });
}

test("een tenant met kortere sessietijden krijgt die in de SSO-tijden van het realm", () => {
  const r = realm({ ...ACC, sessie: { inactiviteit: "PT10M", absoluut: "PT2H" } });
  assert.equal(r.ssoSessionIdleTimeout, 600);
  assert.equal(r.ssoSessionMaxLifespan, 7200);
});

test("tijdens sleutelrotatie staan beide publieke sleutels in de JWKS", () => {
  const tweede = maakClientSleutel();
  const jwks = JSON.parse(client(realm(ACC, { clientSleutels: [sleutel.publiek, tweede.publiek] }), "nl-portal-backend").attributes["jwks.string"]);
  assert.deepEqual(jwks.keys.map((k) => k.kid), [sleutel.kid, tweede.kid]);
});

test("er is geen SPA-client, geen m2m-client en geen token exchange meer, ook niet in ontwikkeling (de BFF van #506)", () => {
  const r = realm(DEV);
  assert.equal(client(r, "nl-portal"), undefined);
  assert.equal(client(r, "nl-portal-m2m"), undefined);
  assert.ok(!JSON.stringify(r).includes("standard.token.exchange.enabled"));
  assert.ok(!JSON.stringify(r).includes("KEYCLOAK_M2M_CLIENT_SECRET"));
});

test("de testgebruiker krijgt inlogmiddel test, niveau Basis en zijn BSN op dezelfde claims als de koppelingen", () => {
  const r = realm(DEV);
  const burger = r.users.find((u) => u.username === "burger");
  assert.deepEqual(burger.attributes, { bsn: ["999993847"], inlogmiddel: ["test"], acr: ["Basis"] });
  const mappers = Object.fromEntries(client(r, "nl-portal-backend").protocolMappers.filter((m) => m.name.startsWith("test-")).map((m) => [m.name, m.config]));
  assert.equal(mappers["test-inlogmiddel"]["claim.name"], "inlogmiddel");
  assert.equal(mappers["test-acr"]["claim.name"], "acr");
  assert.equal(mappers["test-bsn"]["claim.name"], "acting_subject_id");
  for (const [naam, m] of Object.entries(mappers)) {
    const bsn = naam === "test-bsn";
    assert.equal(m["id.token.claim"], String(!bsn), naam);
    assert.equal(m["access.token.claim"], "false", naam);
    assert.equal(m["userinfo.token.claim"], String(bsn), naam);
  }
});

for (const [naam, tenant] of [
  ["zonder testinlogmiddel", { ...DEV, inlogmiddelen: ["digid"] }],
  ["acceptatie", ACC],
  ["productie", PRD],
]) {
  test(`B-M1, ${naam}: geen test-mappers die claims uit gebruikersattributen schrijven`, () => {
    const r = realm(tenant);
    const namen = client(r, "nl-portal-backend").protocolMappers.map((m) => m.name);
    assert.deepEqual(namen.filter((n) => n.startsWith("test-")), []);
    assert.ok(!client(r, "nl-portal-backend").protocolMappers.some((m) => m.protocolMapper === "oidc-usermodel-attribute-mapper"));
    assert.equal(r.users, undefined, "geen demo-gebruikers");
  });
}

// --- Testinlogmiddel: alleen in niet-productie ---

test("dev: het testinlogmiddel geeft de standaard browserflow en de demo-gebruikers; geen koppelingen", () => {
  const r = realm(DEV);
  assert.equal(r.browserFlow, "browser");
  assert.deepEqual(r.users.map((u) => u.username).sort(), ["burger"]);
  for (const u of r.users) assert.equal(u.credentials.length, 1);
  assert.deepEqual(r.identityProviders, []);
});

for (const [naam, tenant] of Object.entries({ acceptatie: ACC, productie: PRD, "productie met wallet": PRD_WALLET })) {
  test(`${naam}: zonder testinlogmiddel geen lokale login: geen gebruikers, geen wachtwoordformulier`, () => {
    const r = realm(tenant);
    assert.equal(r.browserFlow, "alleen-koppelingen");
    assert.deepEqual(r.users ?? [], []);
    const flow = r.authenticationFlows.find((f) => f.alias === r.browserFlow);
    assert.deepEqual(flow.authenticationExecutions.map((e) => e.authenticator), ["auth-cookie", "identity-provider-redirector"]);
    assert.ok(!JSON.stringify(flow).includes("username-password-form"));
    assert.ok(!JSON.stringify(r).includes('"password"'), "geen wachtwoordreferentie in het realm");
  });
}

test("een productie-realm met het testinlogmiddel wordt niet gegenereerd", () => {
  assert.throws(() => realm({ ...PRD, inlogmiddelen: ["digid", "test"] }), /test.*productie/i);
});

// --- Koppelingen per ingeschakeld inlogmiddel ---

test("alleen de koppelingen van ingeschakelde inlogmiddelen staan in het realm", () => {
  assert.deepEqual(realm(PRD).identityProviders.map((i) => i.alias), ["digid"]);
  assert.deepEqual(realm(PRD_WALLET).identityProviders.map((i) => i.alias).sort(), ["digid", "wallet"]);
  assert.deepEqual(realm(DEV).identityProviders, []);
});

test("DigiD (SAML): handtekening gecontroleerd, vastgepind certificaat, minimum niveau als authnContextClassRef", () => {
  const r = realm(PRD);
  const digid = r.identityProviders.find((i) => i.alias === "digid");
  assert.equal(digid.providerId, "saml");
  assert.equal(digid.config.validateSignature, "true");
  assert.equal(digid.config.wantAssertionsSigned, "true");
  assert.equal(digid.config.signingCertificate, "MIIBcertificaat");
  assert.notEqual(digid.config.useMetadataDescriptorUrl, "true");
  assert.equal(digid.config.authnContextComparisonType, "minimum");
  assert.deepEqual(JSON.parse(digid.config.authnContextClassRefs), ["urn:oasis:names:tc:SAML:2.0:ac:classes:Smartcard"]);
  assert.equal(digid.config.entityId, "https://login.example/realms/nlportal");
  assert.deepEqual(
    idpMappers(r, "digid")
      .filter((m) => m.config.note)
      .map((m) => [m.identityProviderMapper, m.config.note, m.config["attribute.name"]])
      .sort(),
    [
      ["nlportal-saml-attribute-note-mapper", "acting_subject_id", "urn:nl-eid-gdi:1.0:ActingSubjectID"],
      ["nlportal-saml-attribute-note-mapper", "legal_subject_id", "urn:nl-eid-gdi:1.0:LegalSubjectID"],
      ["nlportal-saml-authncontext-note-mapper", "acr", undefined],
    ],
  );
});

test("het minimumniveau van de tenant bepaalt de authnContextClassRef; zonder niveau geldt substantieel", () => {
  const ref = (tenant) => JSON.parse(realm(tenant).identityProviders[0].config.authnContextClassRefs)[0].split(":").pop();
  assert.equal(ref({ ...ACC, minimumNiveau: "basis" }), "PasswordProtectedTransport");
  assert.equal(ref({ ...ACC, minimumNiveau: "midden" }), "MobileTwoFactorContract");
  assert.equal(ref({ ...PRD, minimumNiveau: "substantieel" }), "Smartcard");
  assert.equal(ref({ ...PRD, minimumNiveau: "hoog" }), "SmartcardPKI");
  assert.equal(ref({ ...PRD, minimumNiveau: undefined }), "Smartcard");
});

test("wallet (OIDC): code-flow met clientauthenticatie, PKCE S256, gevalideerde handtekening, geen geheim in het realm", () => {
  const wallet = realm(PRD_WALLET).identityProviders.find((i) => i.alias === "wallet");
  assert.equal(wallet.providerId, "oidc");
  assert.equal(wallet.config.validateSignature, "true");
  assert.equal(wallet.config.useJwksUrl, "true");
  assert.equal(wallet.config.pkceEnabled, "true");
  assert.equal(wallet.config.pkceMethod, "S256");
  assert.match(wallet.config.clientAuthMethod, /^(client_secret_post|client_secret_basic|private_key_jwt)$/);
  assert.match(wallet.config.clientSecret, /^\$\(env:/);
  for (const sleutelnaam of ["authorizationUrl", "tokenUrl", "jwksUrl", "issuer"]) assert.match(wallet.config[sleutelnaam], /^https:/);
});

test("buiten ontwikkeling weigert de generator elke koppelings-URL zonder TLS", () => {
  const onveilig = { ...IDP, digid: { ...IDP.digid, singleSignOnServiceUrl: "http://digid.example/sso" } };
  assert.throws(() => realm(PRD, { idp: onveilig }), /https/);
  assert.throws(() => realm(PRD_WALLET, { idp: { ...IDP, wallet: { ...IDP.wallet, tokenUrl: "http://wallet.example/token" } } }), /https/);
});

// --- Weigeringen ---

test("de generator weigert een DigiD-koppeling zonder vastgepind certificaat", () => {
  for (const cert of [undefined, "", "   "]) {
    assert.throws(() => realm(PRD, { idp: { ...IDP, digid: { ...IDP.digid, signingCertificate: cert } } }), /signingCertificate/);
  }
});

test("de generator weigert ontbrekende koppelingsgegevens, een onbekend inlogmiddel en ontbrekende clientsleutels", () => {
  assert.throws(() => realm(PRD, { idp: {} }), /digid/);
  assert.throws(() => realm({ ...PRD, inlogmiddelen: ["eherkenning"] }), /eherkenning/);
  assert.throws(() => realm(PRD, { clientSleutels: [] }), /clientsleutel/i);
  assert.throws(() => realm(PRD, { clientSleutels: [{ ...sleutel.publiek, kid: undefined }] }), /kid/);
  assert.throws(() => realm(PRD, { clientSleutels: [{ ...sleutel.publiek, alg: "RS256" }] }), /PS256/);
  assert.throws(() => realm(PRD, { clientSleutels: [sleutel.publiek, sleutel.publiek] }), /kid/i);
  assert.throws(() => realm(PRD, { clientSleutels: [{ ...sleutel.publiek, d: "geheim" }] }), /priv/i);
});

test("het realm-id volgt uit de parameter en een tenant zonder domein wordt geweigerd", () => {
  assert.equal(realm(PRD, { realm: OIN }).realm, OIN);
  assert.throws(() => realm({ ...PRD, domeinen: [] }), /domein/i);
});

// --- Tussenfase (ASVS 10.4/V13): de SPA-client alleen in ontwikkeling; geen lokale login, wildcard-redirect of offline token elders ---

for (const [naam, tenant] of Object.entries(NIET_DEV)) {
  test(`${naam}: geen SPA-client, geen directe grants, geen wildcard-redirect, geen offline tokens`, () => {
    const r = realm(tenant);
    assert.equal(client(r, "nl-portal"), undefined, "er is geen SPA-client meer");
    for (const c of r.clients) {
      assert.ok(!c.directAccessGrantsEnabled, `${c.clientId}: directAccessGrantsEnabled`);
      assert.ok(!c.implicitFlowEnabled, `${c.clientId}: implicitFlowEnabled`);
      for (const uri of [...(c.redirectUris ?? []), ...(c.attributes?.["post.logout.redirect.uris"] ?? "").split("##")]) {
        assert.doesNotMatch(uri, /[*+]/, `${c.clientId}: wildcard in ${uri}`);
      }
    }
  });
}

test("in elk omgevingstype: geen offline_access op een client; offline sessies zijn begrensd tot de SSO-tijd; refresh-tokens roteren", () => {
  for (const tenant of [DEV, ...Object.values(NIET_DEV)]) {
    const r = realm(tenant);
    for (const c of r.clients) {
      for (const scope of [...(c.defaultClientScopes ?? []), ...(c.optionalClientScopes ?? [])]) assert.notEqual(scope, "offline_access", `${tenant.omgevingstype}: ${c.clientId}`);
    }
    assert.equal(r.offlineSessionMaxLifespanEnabled, true);
    assert.ok(r.offlineSessionMaxLifespan <= 8 * 60 * 60);
    assert.equal(r.revokeRefreshToken, true);
  }
});

// --- BSN niet in gebruikersnaam of tokens (ASVS 14.2.6, ADR 0022 besluit 11) ---

for (const [naam, tenant] of Object.entries(ALLE)) {
  test(`${naam}: nl-portal-backend heeft geen scope die gebruikersgegevens in het token zet (profile, email); geen refresh-token; audience`, () => {
    const c = client(realm(tenant), "nl-portal-backend");
    for (const scope of ["profile", "email", "address", "phone"]) assert.ok(!c.defaultClientScopes.includes(scope), `standaardscope ${scope}`);
    assert.equal(c.attributes["use.refresh.tokens"], "false");
    const aud = c.protocolMappers.find((m) => m.protocolMapper === "oidc-audience-mapper");
    assert.equal(aud.config["included.client.audience"], "nl-portal-backend");
    assert.equal(aud.config["access.token.claim"], "true");
  });

  test(`${naam}: de gebruikersnaam van een gebrokerde gebruiker bevat nooit een identificerend nummer (NameID, claim sub, attribuut)`, () => {
    const r = realm(tenant);
    for (const m of r.identityProviderMappers.filter((x) => /username/.test(x.identityProviderMapper))) {
      assert.doesNotMatch(m.config.template, /NAMEID|CLAIM|ATTRIBUTE/, `${m.identityProviderAlias}: ${m.config.template}`);
      assert.ok(m.config.template.includes("${UUID}"), "een willekeurige waarde als gebruikersnaam");
    }
  });
}

// --- Testinlogmiddel: demo-gebruikers alleen in ontwikkeling (review M3) ---

test("test (OTA): testinlogmiddel met de standaard browserflow maar zonder de vaste demo-gebruikers uit Git", () => {
  const r = realm(OTA);
  assert.equal(r.browserFlow, "browser");
  assert.deepEqual(r.users ?? [], []);
});

// --- Generator weigert (review M2, L2, L3; security L3, L5) ---

test("de generator weigert een DigiD-koppeling met metadata-URL (faalt gesloten: alleen een vastgepind certificaat)", () => {
  for (const extra of [{ useMetadataDescriptorUrl: "true" }, { useMetadataDescriptorUrl: "false" }, { metadataDescriptorUrl: "https://digid.example/metadata" }]) {
    assert.throws(() => realm(PRD, { idp: { ...IDP, digid: { ...IDP.digid, ...extra } } }), /metadata/i);
  }
});

test("de generator weigert sessietijden van nul, negatieve of een inactiviteit boven de absolute duur", () => {
  assert.throws(() => realm({ ...ACC, sessie: { inactiviteit: "PT0S" } }), /sessie/i);
  assert.throws(() => realm({ ...ACC, sessie: { absoluut: "PT0S" } }), /sessie/i);
  assert.throws(() => realm({ ...ACC, sessie: { inactiviteit: "PT10M", absoluut: "PT5M" } }), /sessie/i);
});

test("de generator weigert een onbekend minimumniveau en, in productie, een niveau onder substantieel", () => {
  assert.throws(() => realm({ ...ACC, minimumNiveau: "laag" }), /minimumNiveau/);
  for (const spook of ["toString", "constructor", "__proto__", 7]) assert.throws(() => realm({ ...ACC, minimumNiveau: spook }), /minimumNiveau/, String(spook));
  assert.throws(() => realm({ ...PRD, minimumNiveau: "basis" }), /substantieel/);
  assert.throws(() => realm({ ...PRD, minimumNiveau: "midden" }), /substantieel/);
  assert.doesNotThrow(() => realm({ ...PRD, minimumNiveau: "substantieel" }));
});

test("de generator weigert kcc-substitutie en onbruikbare waarden in tenant- of koppelingsgegevens", () => {
  assert.throws(() => realm({ ...ACC, naam: "$(env:KEYCLOAK_WALLET_CLIENT_SECRET)" }), /kcc-substitutie/);
  assert.throws(() => realm({ ...ACC, naam: "$(file:UTF-8:/pad)" }), /kcc-substitutie/);
  assert.throws(() => realm(ACC, { idp: { ...IDP, digid: { ...IDP.digid, idpEntityId: "https://x.example/$(env:A)" } } }), /kcc-substitutie/);
  for (const domein of ["evil.example/x", "a@b.example", "mijn.example:8443", "", "UPPER CASE", "https://mijn.example"]) {
    assert.throws(() => realm({ ...ACC, domeinen: [domein] }), /domein/i, domein);
  }
});

test("buiten ontwikkeling moet de broker-URL https zijn", () => {
  assert.throws(() => realm(PRD, { brokerUrl: "http://login.example" }), /https/);
  assert.doesNotThrow(() => realm(PRD, { brokerUrl: "https://login.example" }));
});

// --- Overig uit de securitytoets (L2, L7, L8) ---

for (const [naam, tenant] of Object.entries(ALLE)) {
  test(`${naam}: brute-force-detectie staat expliciet in het realm en gebruikersevents worden niet opgeslagen (BSN en IP-adres, ASVS 16.2.5/14.2.6; filter volgt in #191)`, () => {
    const r = realm(tenant);
    assert.equal(r.bruteForceProtected, true);
    assert.equal(r.permanentLockout, false);
    assert.ok(r.failureFactor <= 10 && r.failureFactor >= 3, "aantal pogingen vóór blokkade");
    assert.ok(r.waitIncrementSeconds >= 60);
    assert.ok(r.maxFailureWaitSeconds >= r.waitIncrementSeconds);
    assert.ok(r.maxDeltaTimeSeconds >= 3600);
    assert.equal(r.eventsEnabled, false, "gebruikersevents bevatten bij een DigiD-login de NameID (BSN) en het IP-adres");
    assert.deepEqual(r.eventsListeners, [], "ook geen logregel per event (error-events bevatten dezelfde details)");
    assert.equal(r.adminEventsEnabled, true);
    assert.equal(r.adminEventsDetailsEnabled, false, "admin-events zonder representaties (die kunnen een federated identity met BSN bevatten)");
  });
}

test("de wallet-mapper voor legal_subject_id leest een sentinel-claim die een wallet nooit levert", () => {
  const legal = idpMappers(realm(PRD_WALLET), "wallet").find((m) => m.config.note === "legal_subject_id");
  assert.equal(legal.config["claim.name"], "nlportal-nooit-legal_subject_id");
});

test("het OIN is precies twintig cijfers (het gaat in redirect-URI's en de back-channel-URL)", () => {
  for (const oin of ["123", "0000009900000000000a", "00000099000000000001/../x", "$(env:A)", "000000990000000000011", undefined]) assert.throws(() => realm({ ...ACC, oin }), /OIN/, String(oin));
  assert.doesNotThrow(() => realm(ACC));
});
