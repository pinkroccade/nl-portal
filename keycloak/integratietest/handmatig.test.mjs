// Integratietest van de Keycloak-provider (#527): assertions die de DigiD-simulator van Keycloak niet kan leveren, omdat
// Keycloak als IdP altijd een AuthnContextClassRef zet en geen dubbele of versleutelde attributen maakt. Een kleine
// HTTP-server in de test speelt de IdP van koppeling `digid-handmatig` (zonder handtekeningcontrole, alleen voor deze
// test) en stuurt de browser een zelfgemaakte SAML-response naar het endpoint van de broker.
import { after, before, beforeEach, describe, it } from "node:test";
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { KC, decodeJwt, deletePortalUsers, login, exchange, waitForKeycloak } from "./stack.mjs";

const SP = `${KC}/realms/portal`;
const ENDPOINT = `${SP}/broker/digid-handmatig/endpoint`;
const IDP = "http://localhost:18082/idp";
const MIDDEN = "urn:oasis:names:tc:SAML:2.0:ac:classes:MobileTwoFactorContract";
const ACTING = "urn:nl-eid-gdi:1.0:ActingSubjectID";
const LEGAL = "urn:nl-eid-gdi:1.0:LegalSubjectID";

const afwezig = (waarde) => waarde === undefined || waarde === "";
const esc = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/"/g, "&quot;");

// Wat de volgende SAML-response bevat; elke test zet dit.
let respons;

const ENCRYPTED_ATTRIBUTE = `<saml:EncryptedAttribute><xenc:EncryptedData xmlns:xenc="http://www.w3.org/2001/04/xmlenc#" Type="http://www.w3.org/2001/04/xmlenc#Element"><xenc:EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"/><xenc:CipherData><xenc:CipherValue>AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA</xenc:CipherValue></xenc:CipherData></xenc:EncryptedData></saml:EncryptedAttribute>`;

function attribuut(naam, waarden) {
  const values = waarden
    .map((w) => `<saml:AttributeValue xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:type="xs:string">${esc(w)}</saml:AttributeValue>`)
    .join("");
  return `<saml:Attribute Name="${naam}" NameFormat="urn:oasis:names:tc:SAML:2.0:attrname-format:uri">${values}</saml:Attribute>`;
}

function samlResponse({ requestId, classRef, attributen = [], versleuldAttribuut = false }) {
  const nu = new Date();
  const instant = nu.toISOString();
  const verloopt = new Date(nu.getTime() + 5 * 60_000).toISOString();
  const authn = classRef === null
    ? ""
    : `<saml:AuthnStatement AuthnInstant="${instant}"><saml:AuthnContext><saml:AuthnContextClassRef>${classRef}</saml:AuthnContextClassRef></saml:AuthnContext></saml:AuthnStatement>`;
  const statement = attributen.length || versleuldAttribuut
    ? `<saml:AttributeStatement>${attributen.map((a) => attribuut(a.naam, a.waarden)).join("")}${versleuldAttribuut ? ENCRYPTED_ATTRIBUTE : ""}</saml:AttributeStatement>`
    : "";
  return `<samlp:Response xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol" xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion" ID="_r${Date.now()}" Version="2.0" IssueInstant="${instant}" Destination="${ENDPOINT}" InResponseTo="${requestId}"><saml:Issuer>${IDP}</saml:Issuer><samlp:Status><samlp:StatusCode Value="urn:oasis:names:tc:SAML:2.0:status:Success"/></samlp:Status><saml:Assertion ID="_a${Date.now()}" Version="2.0" IssueInstant="${instant}"><saml:Issuer>${IDP}</saml:Issuer><saml:Subject><saml:NameID Format="urn:oasis:names:tc:SAML:2.0:nameid-format:persistent">handmatig-1</saml:NameID><saml:SubjectConfirmation Method="urn:oasis:names:tc:SAML:2.0:cm:bearer"><saml:SubjectConfirmationData InResponseTo="${requestId}" NotOnOrAfter="${verloopt}" Recipient="${ENDPOINT}"/></saml:SubjectConfirmation></saml:Subject><saml:Conditions NotBefore="${instant}" NotOnOrAfter="${verloopt}"><saml:AudienceRestriction><saml:Audience>${SP}</saml:Audience></saml:AudienceRestriction></saml:Conditions>${authn}${statement}</saml:Assertion></samlp:Response>`;
}

let server;
before(async () => {
  await waitForKeycloak();
  server = createServer((req, res) => {
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", () => {
      const form = new URLSearchParams(body);
      const request = Buffer.from(form.get("SAMLRequest") ?? "", "base64").toString("utf8");
      const requestId = /ID="([^"]+)"/.exec(request)?.[1];
      const xml = samlResponse({ requestId, ...respons });
      res.setHeader("content-type", "text/html");
      res.end(
        `<html><body><form method="post" action="${ENDPOINT}"><input type="hidden" name="SAMLResponse" value="${Buffer.from(xml).toString("base64")}"/><input type="hidden" name="RelayState" value="${esc(form.get("RelayState") ?? "")}"/></form></body></html>`,
      );
    });
  });
  await new Promise((resolve) => server.listen(18082, resolve));
});
after(() => server?.close());
beforeEach(() => deletePortalUsers());

async function inloggen() {
  const result = await login({ idp: "digid-handmatig", username: "x", password: "x" });
  if (!result.code) return { result, idToken: null };
  return { result, idToken: decodeJwt((await exchange(result)).id_token) };
}

describe("Zelfgemaakte SAML-assertions via de mappers van het eigen image", () => {
  it("zet de AuthnContextClassRef en de attributen uit een volledige assertion in het ID-token", async () => {
    respons = { classRef: MIDDEN, attributen: [{ naam: ACTING, waarden: ["999990008"] }, { naam: LEGAL, waarden: ["999990020"] }] };
    const { idToken } = await inloggen();
    assert.equal(idToken.acr, MIDDEN);
    assert.equal(idToken.acting_subject_id, "999990008");
    assert.equal(idToken.legal_subject_id, "999990020");
  });

  // De callbacktoets in #506 weigert een lege of ontbrekende acr; hier alleen bewezen dat de mapper geen niveau verzint.
  it("geeft een token zonder acr als de assertion geen AuthnContextClassRef heeft", async () => {
    respons = { classRef: null, attributen: [{ naam: ACTING, waarden: ["999990008"] }] };
    const { idToken, result } = await inloggen();
    assert.ok(idToken, `geen token: ${result.pageText}`);
    assert.ok(afwezig(idToken.acr), `er is toch een acr: ${idToken.acr}`);
    assert.equal(idToken.acting_subject_id, "999990008");
  });

  it("geeft een lege notitie, geen weigering, als een attribuut ontbreekt", async () => {
    respons = { classRef: MIDDEN, attributen: [{ naam: ACTING, waarden: ["999990008"] }] };
    const { idToken } = await inloggen();
    assert.ok(afwezig(idToken.legal_subject_id));
  });

  it("weigert de login bij een dubbel attribuut", async () => {
    respons = {
      classRef: MIDDEN,
      attributen: [{ naam: ACTING, waarden: ["999990008"] }, { naam: ACTING, waarden: ["999990020"] }],
    };
    const { idToken, result } = await inloggen();
    assert.equal(idToken, null);
    assert.equal(result.code, null);
    assert.match(result.pageText ?? "", /sorry|error|authenticat|identity provider/i);
  });

  it("weigert de login bij een attribuut met twee waarden", async () => {
    respons = { classRef: MIDDEN, attributen: [{ naam: ACTING, waarden: ["999990008", "999990020"] }] };
    const { idToken } = await inloggen();
    assert.equal(idToken, null);
  });

  it("weigert de login bij een lege attribuutwaarde", async () => {
    respons = { classRef: MIDDEN, attributen: [{ naam: ACTING, waarden: [""] }] };
    const { idToken } = await inloggen();
    assert.equal(idToken, null);
  });

  // Keycloak 26.8 parseert een EncryptedAttribute niet ("Unknown tag") en weigert de response al voor de mapper; de
  // controle in de mapper (unit-test) is een tweede slot voor een Keycloak-versie die dat wel parseert.
  it("weigert de login bij een versleuteld attribuut in plaats van het stil over te slaan", async () => {
    respons = { classRef: MIDDEN, attributen: [{ naam: ACTING, waarden: ["999990008"] }], versleuldAttribuut: true };
    const { idToken } = await inloggen();
    assert.equal(idToken, null);
  });
});
