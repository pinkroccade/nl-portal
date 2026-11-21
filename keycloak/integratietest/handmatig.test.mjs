// Integratietest van de Keycloak-provider (#527): assertions die de DigiD-simulator van Keycloak niet kan leveren, omdat
// Keycloak als IdP altijd een AuthnContextClassRef zet, niets kapot kan maken en geen dubbele of versleutelde attributen
// maakt. Een kleine HTTP-server in de test speelt de IdP van koppeling `digid-handmatig`: hij ondertekent assertions met
// een wegwerpsleutel (zoals de koppeling vereist: handtekening valideren, assertion ondertekend, certificaat vastgepind) en
// stuurt de browser een zelfgemaakte SAML-response naar het endpoint van de broker. Aanvalsgevallen (ongetekend, andere
// sleutel, meerdere assertions, signature wrapping, meerdere AuthnStatements, herhaling) horen bij de verplichte set
// voor elke Keycloak-bump.
import { after, before, beforeEach, describe, it } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { KC, Jar, admin, decodeJwt, deletePortalUsers, exchange, login, waitForKeycloak } from "./stack.mjs";
import { assertion, nieuweSleutel, nieuwId, response } from "./saml-idp.mjs";

const SP = `${KC}/realms/portal`;
const ENDPOINT = `${SP}/broker/digid-handmatig/endpoint`;
const IDP_POORT = 18082;
const IDP = `http://localhost:${IDP_POORT}/idp`;
const BASIS = "urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport";
const MIDDEN = "urn:oasis:names:tc:SAML:2.0:ac:classes:MobileTwoFactorContract";
const HOOG = "urn:oasis:names:tc:SAML:2.0:ac:classes:SmartcardPKI";
const ACTING = "urn:nl-eid-gdi:1.0:ActingSubjectID";
const LEGAL = "urn:nl-eid-gdi:1.0:LegalSubjectID";

const afwezig = (waarde) => waarde === undefined || waarde === "";

// De volgende SAML-response, als functie van het verzoek (`{ requestId }`); elke test zet dit.
let respons;
// De laatste response die naar de browser ging, voor de herhalingstest.
let laatste;
let sleutel;
let andereSleutel;

const ENCRYPTED_ATTRIBUTE = `<saml:EncryptedAttribute><xenc:EncryptedData xmlns:xenc="http://www.w3.org/2001/04/xmlenc#" Type="http://www.w3.org/2001/04/xmlenc#Element"><xenc:EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"></xenc:EncryptionMethod><xenc:CipherData><xenc:CipherValue>AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA</xenc:CipherValue></xenc:CipherData></xenc:EncryptedData></saml:EncryptedAttribute>`;

/** Een volledige, ondertekende assertion in een response; wat afwijkt geef je mee. */
function ondertekend({ authn = [MIDDEN], attributen = [{ naam: ACTING, waarden: ["999990008"] }], metSleutel = sleutel, ...rest } = {}) {
  return ({ requestId }) =>
    response({
      requestId,
      endpoint: ENDPOINT,
      idp: IDP,
      assertions: assertion({ id: nieuwId(), requestId, endpoint: ENDPOINT, sp: SP, idp: IDP, authn, attributen, sleutel: metSleutel, ...rest }),
    });
}

const gemeenschappelijkeVelden = (requestId) => ({ requestId, endpoint: ENDPOINT, sp: SP, idp: IDP });
const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/"/g, "&quot;");
const formulier = ({ xml, relayState }) =>
  `<html><body><form method="post" action="${ENDPOINT}"><input type="hidden" name="SAMLResponse" value="${Buffer.from(xml).toString("base64")}"/><input type="hidden" name="RelayState" value="${esc(relayState)}"/></form></body></html>`;

/** Zet instellingen van de koppeling `digid-handmatig` (de rest blijft zoals in de realm). */
async function zetKoppeling(config) {
  const idp = await (await admin("/portal/identity-provider/instances/digid-handmatig")).json();
  Object.assign(idp.config, config);
  await admin("/portal/identity-provider/instances/digid-handmatig", { method: "PUT", body: JSON.stringify(idp) });
}

let server;
before(async () => {
  await waitForKeycloak();
  sleutel = nieuweSleutel("idp-handmatig");
  andereSleutel = nieuweSleutel("idp-aanvaller");
  server = createServer((req, res) => {
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", () => {
      const form = new URLSearchParams(body);
      const request = Buffer.from(form.get("SAMLRequest") ?? "", "base64").toString("utf8");
      const requestId = /ID="([^"]+)"/.exec(request)?.[1];
      const xml = respons({ requestId });
      laatste = { xml, relayState: form.get("RelayState") ?? "" };
      res.setHeader("content-type", "text/html");
      res.end(formulier(laatste));
    });
  });
  await new Promise((resolve) => server.listen(IDP_POORT, resolve));
});
after(() => server?.close());
beforeEach(async () => {
  await zetKoppeling({ validateSignature: "true", wantAssertionsSigned: "true", signingCertificate: sleutel.certificaat });
  await deletePortalUsers();
});

async function inloggen(opties = {}) {
  const result = await login({ idp: "digid-handmatig", username: "x", password: "x", ...opties });
  if (!result.code) return { result, idToken: null };
  return { result, idToken: decodeJwt((await exchange(result)).id_token) };
}

// Een geweigerde login toont een Keycloak-foutpagina: geen code, geen token, geen terugval op het wachtwoordformulier.
// Alleen idToken === null zou ook slagen bij een kapotte testopzet.
function assertGeweigerd({ result, idToken }) {
  assert.equal(idToken, null);
  assert.equal(result.code, null);
  assert.ok(!result.wachtwoordformulier, `terugval op het wachtwoordformulier: ${result.pageText}`);
  assert.match(result.pageText ?? "", /sorry|error|authenticat|identity provider/i, `geen foutpagina van Keycloak: ${result.pageText}`);
}

describe("Ondertekende SAML-assertions via de mappers van het eigen image", () => {
  it("zet de AuthnContextClassRef en de attributen uit een volledige assertion in het ID-token", async () => {
    respons = ondertekend({
      attributen: [{ naam: ACTING, waarden: ["999990008"] }, { naam: LEGAL, waarden: ["999990020"] }],
    });
    const { idToken, result } = await inloggen();
    assert.ok(idToken, `geen token: ${result.pageText}`);
    assert.equal(idToken.acr, MIDDEN);
    assert.equal(idToken.acting_subject_id, "999990008");
    assert.equal(idToken.legal_subject_id, "999990020");
  });

  // De callbacktoets in #506 weigert een lege of ontbrekende acr; hier alleen bewezen dat de mapper geen niveau verzint.
  it("geeft een token zonder acr als het AuthnStatement geen AuthnContextClassRef heeft", async () => {
    respons = ondertekend({ authn: [null] });
    const { idToken, result } = await inloggen();
    assert.ok(idToken, `geen token: ${result.pageText}`);
    assert.ok(afwezig(idToken.acr), `er is toch een acr: ${idToken.acr}`);
    assert.equal(idToken.acting_subject_id, "999990008");
  });

  it("laat een eerder niveau niet staan als dezelfde gebruiker met prompt=login opnieuw inlogt zonder AuthnContextClassRef", async () => {
    const jar = new Jar();
    respons = ondertekend({ authn: [MIDDEN] });
    const eerste = await inloggen({ jar });
    assert.equal(eerste.idToken?.acr, MIDDEN);

    respons = ondertekend({ authn: [null] });
    const tweede = await inloggen({ jar, prompt: "login" });
    assert.ok(tweede.idToken, `geen token: ${tweede.result.pageText}`);
    assert.ok(afwezig(tweede.idToken.acr), `het niveau van de eerdere login bleef staan: ${tweede.idToken.acr}`);
  });

  it("geeft een lege notitie, geen weglating, als een attribuut ontbreekt", async () => {
    respons = ondertekend();
    const { idToken, result } = await inloggen();
    assert.ok(idToken, `geen token: ${result.pageText}`);
    assert.ok(afwezig(idToken.legal_subject_id));
  });

  it("leest een NameID als attribuutwaarde (BSN in de NameID, zonder versleuteling)", async () => {
    const nameId = { xml: `<saml:NameID Format="urn:oasis:names:tc:SAML:2.0:nameid-format:persistent">999990008</saml:NameID>` };
    respons = ondertekend({ attributen: [{ naam: ACTING, waarden: [nameId] }] });
    const { idToken, result } = await inloggen();
    assert.ok(idToken, `geen token: ${result.pageText}`);
    assert.equal(idToken.acting_subject_id, "999990008");
  });

  it("weigert de login bij een EncryptedID als attribuutwaarde", async () => {
    const versleuteld = {
      xml: `<saml:EncryptedID><xenc:EncryptedData xmlns:xenc="http://www.w3.org/2001/04/xmlenc#" Type="http://www.w3.org/2001/04/xmlenc#Element"><xenc:EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"></xenc:EncryptionMethod><xenc:CipherData><xenc:CipherValue>AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA</xenc:CipherValue></xenc:CipherData></xenc:EncryptedData></saml:EncryptedID>`,
    };
    respons = ondertekend({ attributen: [{ naam: ACTING, waarden: [versleuteld] }] });
    assertGeweigerd(await inloggen());
  });

  it("weigert de login bij een dubbel attribuut", async () => {
    respons = ondertekend({
      attributen: [{ naam: ACTING, waarden: ["999990008"] }, { naam: ACTING, waarden: ["999990020"] }],
    });
    assertGeweigerd(await inloggen());
  });

  it("weigert de login bij een attribuut met twee waarden", async () => {
    respons = ondertekend({ attributen: [{ naam: ACTING, waarden: ["999990008", "999990020"] }] });
    assertGeweigerd(await inloggen());
  });

  it("weigert de login bij een lege attribuutwaarde", async () => {
    respons = ondertekend({ attributen: [{ naam: ACTING, waarden: [""] }] });
    assertGeweigerd(await inloggen());
  });

  // Keycloak 26.8 parseert een EncryptedAttribute niet ("Unknown tag") en weigert de response al voor de mapper; de
  // controle in de mapper (unit-test) is een tweede slot voor een Keycloak-versie die dat wel parseert.
  it("weigert de login bij een versleuteld attribuut in plaats van het stil over te slaan", async () => {
    respons = ({ requestId }) => {
      // Het versleutelde attribuut hoort binnen de assertion; de handtekening komt na het invoegen, over de hele assertion.
      const xml = assertion({
        id: nieuwId(),
        requestId,
        endpoint: ENDPOINT,
        sp: SP,
        idp: IDP,
        authn: [MIDDEN],
        attributen: [{ naam: ACTING, waarden: ["999990008"] }],
        versleuteldAttribuut: ENCRYPTED_ATTRIBUTE,
        sleutel,
      });
      return response({ requestId, endpoint: ENDPOINT, idp: IDP, assertions: xml });
    };
    assertGeweigerd(await inloggen());
  });
});

// Security M1 (#505): het BSN (de NameID van DigiD is `s00000000:<bsn>`) staat niet in de gebruikersnaam en dus niet als
// `preferred_username` in een token of in Keycloak-logregels. Dezelfde gebruikersnaamtemplate als het gegenereerde realm
// (`${ALIAS}.${UUID}`); de koppeling met DigiD loopt via de federated identity, dus een tweede login vindt dezelfde gebruiker.
describe("Het BSN komt niet in de gebruikersnaam of in tokens", () => {
  const NAME_ID = "s00000000:999990008";

  it("geeft een gebrokerde gebruiker een pseudonieme gebruikersnaam en tokens zonder BSN buiten acting_subject_id; een tweede login vindt dezelfde gebruiker", async () => {
    respons = ({ requestId }) =>
      response({
        requestId,
        endpoint: ENDPOINT,
        idp: IDP,
        assertions: assertion({ id: nieuwId(), requestId, endpoint: ENDPOINT, sp: SP, idp: IDP, subject: NAME_ID, authn: [MIDDEN], attributen: [{ naam: ACTING, waarden: ["111222333"] }], sleutel }),
      });
    const log = async () => {
      const result = await login({ idp: "digid-handmatig", username: "x", password: "x" });
      assert.ok(result.code, `geen code: ${result.pageText}`);
      const tokens = await exchange(result);
      return { id: decodeJwt(tokens.id_token), access: decodeJwt(tokens.access_token), tokens };
    };
    const eerste = await log();
    for (const [naam, claims] of [["ID-token", eerste.id], ["access-token", eerste.access]]) {
      assert.ok(!JSON.stringify(claims).includes("999990008"), `${naam} bevat het BSN van de NameID: ${JSON.stringify(claims)}`);
      assert.equal(claims.preferred_username, undefined, `${naam}: preferred_username`);
      assert.equal(claims.email, undefined, `${naam}: email`);
    }
    assert.equal(eerste.id.acting_subject_id, "111222333");
    assert.equal(eerste.tokens.refresh_token, undefined, "de BFF gebruikt geen refresh-token");
    assert.ok([].concat(eerste.access.aud).includes("nl-portal-backend"), "RFC 9068: aud");

    const gebruikers = (await (await admin("/portal/users?max=100")).json()).filter((u) => u.username.startsWith("digid-handmatig."));
    assert.equal(gebruikers.length, 1);
    assert.ok(!gebruikers[0].username.includes("999990008"), `BSN in gebruikersnaam: ${gebruikers[0].username}`);

    const tweede = await log();
    assert.equal(tweede.id.sub, eerste.id.sub, "een tweede login moet dezelfde gebruiker vinden");
    const daarna = (await (await admin("/portal/users?max=100")).json()).filter((u) => u.username.startsWith("digid-handmatig."));
    assert.equal(daarna.length, 1);
  });
});

describe("Aanvallen op de vertrouwensgrens", () => {
  // M1: een ongetekende of met een vreemde sleutel getekende assertion mag geen niveau of BSN in het token zetten.
  it("weigert een ongetekende assertion met een hoog niveau en een BSN naar keuze", async () => {
    respons = ondertekend({ authn: [HOOG], attributen: [{ naam: ACTING, waarden: ["111222333"] }], metSleutel: null });
    assertGeweigerd(await inloggen());
  });

  it("weigert een assertion die met een andere sleutel is getekend dan het vastgepinde certificaat", async () => {
    respons = ondertekend({ authn: [HOOG], attributen: [{ naam: ACTING, waarden: ["111222333"] }], metSleutel: andereSleutel });
    assertGeweigerd(await inloggen());
  });

  // De mapper is het tweede slot: staat de koppeling verkeerd (realm-template, beheerconsole), dan neemt hij niets over,
  // ook niet van een geldig ondertekende assertion.
  for (const [naam, config] of [
    ["handtekeningen niet gevalideerd", { validateSignature: "false" }],
    ["assertion niet ondertekend vereist", { wantAssertionsSigned: "false" }],
    ["geen certificaat vastgepind", { signingCertificate: "" }],
  ]) {
    it(`weigert een geldig ondertekende assertion als de koppeling zo staat: ${naam}`, async () => {
      await zetKoppeling(config);
      respons = ondertekend();
      assertGeweigerd(await inloggen());
    });
  }

  // M2: meerdere assertions en signature wrapping.
  it("weigert een response met een ondertekende en een ongetekende assertion", async () => {
    respons = ({ requestId }) => {
      const gemeenschappelijk = gemeenschappelijkeVelden(requestId);
      return response({
        requestId,
        endpoint: ENDPOINT,
        idp: IDP,
        assertions:
          assertion({ id: nieuwId(), ...gemeenschappelijk, authn: [HOOG], attributen: [{ naam: ACTING, waarden: ["111222333"] }] }) +
          assertion({ id: nieuwId(), ...gemeenschappelijk, authn: [MIDDEN], attributen: [{ naam: ACTING, waarden: ["999990008"] }], sleutel }),
      });
    };
    assertGeweigerd(await inloggen());
  });

  it("weigert een ondertekende assertion die in Extensions is verstopt naast een ongetekende assertion", async () => {
    respons = ({ requestId }) => {
      const gemeenschappelijk = gemeenschappelijkeVelden(requestId);
      const echt = assertion({ id: nieuwId(), ...gemeenschappelijk, authn: [MIDDEN], attributen: [{ naam: ACTING, waarden: ["999990008"] }], sleutel });
      return response({
        requestId,
        endpoint: ENDPOINT,
        idp: IDP,
        voorStatus: `<samlp:Extensions>${echt}</samlp:Extensions>`,
        assertions: assertion({ id: nieuwId(), ...gemeenschappelijk, authn: [HOOG], attributen: [{ naam: ACTING, waarden: ["111222333"] }] }),
      });
    };
    assertGeweigerd(await inloggen());
  });

  it("weigert een ondertekende assertion die in het Advice van een ongetekende assertion is verstopt", async () => {
    respons = ({ requestId }) => {
      const gemeenschappelijk = gemeenschappelijkeVelden(requestId);
      const echt = assertion({ id: nieuwId(), ...gemeenschappelijk, authn: [MIDDEN], attributen: [{ naam: ACTING, waarden: ["999990008"] }], sleutel });
      return response({
        requestId,
        endpoint: ENDPOINT,
        idp: IDP,
        assertions: assertion({ id: nieuwId(), ...gemeenschappelijk, authn: [HOOG], attributen: [{ naam: ACTING, waarden: ["111222333"] }], advice: echt }),
      });
    };
    assertGeweigerd(await inloggen());
  });

  it("weigert een ondertekende assertion met twee AuthnStatements van verschillend niveau", async () => {
    respons = ondertekend({ authn: [BASIS, HOOG] });
    assertGeweigerd(await inloggen());
  });

  // I3 (ASVS 6.8.3). Let op wat dit bewijst: de assertion wordt opnieuw gepost in dezelfde, al afgeronde
  // authenticatiesessie, en Keycloak weigert omdat die sessie weg is. Dat is geen bewijs van een cache van assertion-ID's;
  // het geval met een nieuwe authenticatiesessie staat in de test hieronder.
  it("weigert een opnieuw geposte assertion in een afgeronde authenticatiesessie", async () => {
    respons = ondertekend();
    const jar = new Jar();
    const eerste = await inloggen({ jar });
    assert.ok(eerste.idToken, `eerste login mislukte: ${eerste.result.pageText}`);

    const opnieuw = await fetch(ENDPOINT, {
      method: "POST",
      redirect: "manual",
      headers: { "content-type": "application/x-www-form-urlencoded", cookie: jar.header(ENDPOINT) },
      body: new URLSearchParams({ SAMLResponse: Buffer.from(laatste.xml).toString("base64"), RelayState: laatste.relayState }),
    });
    const location = opnieuw.headers.get("location") ?? "";
    const tekst = await opnieuw.text();
    assert.ok(!/[?&]code=/.test(location), `de herhaalde assertion leverde opnieuw een code: ${location}`);
    assert.ok(opnieuw.status >= 400 || /error|sorry|expired|already/i.test(tekst + location), `niet geweigerd: ${opnieuw.status} ${location}`);
  });

  // ASVS 6.8.3 (criterium op #505, review H1): een eerder gebruikte, nog geldige en correct ondertekende response wordt in een
  // NIEUWE authenticatiesessie (nieuw AuthnRequest, nieuwe sessiecookies, nieuwe RelayState) opnieuw gepost. Keycloak moet hem
  // weigeren: `InResponseTo` hoort bij het eerdere AuthnRequest. Controle dat de test niet leeg is: dezelfde response volgt
  // wel normaal als hij bij het huidige verzoek hoort (eerste login hieronder).
  it("weigert een oude, geldige response in een nieuwe authenticatiesessie (replay)", async () => {
    respons = ondertekend();
    const eerste = await inloggen();
    assert.ok(eerste.idToken, `eerste login mislukte: ${eerste.result.pageText}`);
    const oud = laatste.xml;
    assert.match(oud, /InResponseTo="[^"]+"/);

    // Nieuwe login (nieuwe jar, dus geen SSO-sessie): de IdP-server speelt nu de OUDE response terug, ongeacht het nieuwe verzoek.
    respons = () => oud;
    const nieuw = await inloggen({ jar: new Jar() });
    assertGeweigerd(nieuw);
    assert.equal(nieuw.result.code, null, "de oude response leverde een code in een nieuwe sessie");
  });
});
