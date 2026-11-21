// Prototype T1 (#500). Wat komt er in het ID-token van `nl-portal-backend` na een login via een DigiD-koppeling
// (SAML, gesimuleerd) en een wallet-koppeling (OIDC, gesimuleerd)? Elke test is een bevinding voor de notitie.
import { after, before, beforeEach, describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  DIGID,
  addPortalIdpMapper,
  clearDigidRequestedLevel,
  decodeJwt,
  deletePortalIdpMapper,
  exchange,
  portalUsersWithAttribute,
  login,
  loginForIdToken,
  provisionDigidSigningCert,
  samlAuthnRequest,
  setDigidRequestedLevel,
  setSimUserAttribute,
  waitForKeycloak,
} from "./stack.mjs";

before(async () => {
  await waitForKeycloak();
  await provisionDigidSigningCert();
});

describe("DigiD via SAML-broker", () => {
  beforeEach(() => setDigidRequestedLevel(DIGID.midden));

  it("zet inlogmiddel digid, betrouwbaarheidsniveau en BSN van de actor als claim in het ID-token", async () => {
    const { idToken } = await loginForIdToken({ idp: "digid", username: "jan", password: "jan" });
    assert.equal(idToken.inlogmiddel, "digid");
    assert.equal(idToken.acr, DIGID.midden);
    assert.equal(idToken.acting_subject_id, "999990008");
    assert.equal(idToken.legal_subject_id, undefined);
  });

  it("levert het niveau dat de koppeling bij DigiD opvraagt, ook Substantieel en Basis", async () => {
    for (const level of ["substantieel", "basis"]) {
      await setDigidRequestedLevel(DIGID[level]);
      const { idToken } = await loginForIdToken({ idp: "digid", username: "jan", password: "jan" });
      assert.equal(idToken.acr, DIGID[level], level);
    }
  });

  it("vraagt het minimum bij DigiD op via RequestedAuthnContext met vergelijking minimum", async () => {
    await setDigidRequestedLevel(DIGID.substantieel);
    const result = await login({ idp: "digid", username: "jan", password: "jan" });
    const request = samlAuthnRequest(result);
    assert.match(request, /<samlp:RequestedAuthnContext Comparison="minimum">/);
    assert.match(request, new RegExp(`<saml:AuthnContextClassRef>${DIGID.substantieel}</saml:AuthnContextClassRef>`));
  });

  it("neemt acr_values van de BFF niet over in de AuthnRequest: het minimum staat in de koppeling, niet in de login-start", async () => {
    await setDigidRequestedLevel(DIGID.midden);
    const result = await login({ idp: "digid", username: "jan", password: "jan", acrValues: "urn:test:hoger-niveau" });
    const request = samlAuthnRequest(result);
    assert.doesNotMatch(request, /hoger-niveau/);
    assert.match(request, new RegExp(DIGID.midden));
  });

  it("geeft bij vertegenwoordiging LegalSubjectID als aparte claim naast de BSN van de actor", async () => {
    const { idToken } = await loginForIdToken({ idp: "digid", username: "maria", password: "maria" });
    assert.equal(idToken.acting_subject_id, "999990020");
    assert.equal(idToken.legal_subject_id, "999990008");
  });

  it("laat legal_subject_id vervallen bij een volgende login zonder LegalSubjectID (geen restant van een eerdere login)", async () => {
    try {
      const eerste = await loginForIdToken({ idp: "digid", username: "maria", password: "maria" });
      assert.equal(eerste.idToken.legal_subject_id, "999990008");
      await setSimUserAttribute("maria", "legal_subject_id", null);
      const tweede = await loginForIdToken({ idp: "digid", username: "maria", password: "maria" });
      assert.equal(tweede.idToken.legal_subject_id, undefined);
    } finally {
      await setSimUserAttribute("maria", "legal_subject_id", ["999990008"]);
    }
  });
});

describe("Beperkingen en randgevallen van Keycloak", () => {
  beforeEach(() => setDigidRequestedLevel(DIGID.midden));

  it("weigert een login met een lager niveau dan het minimum zelf niet: de backend moet het niveau uit de claim toetsen", async () => {
    await clearDigidRequestedLevel(); // een koppeling die niets opvraagt, of een IdP die het verzoek negeert
    const { idToken } = await loginForIdToken({ idp: "digid", username: "jan", password: "jan" });
    assert.equal(idToken.acr, DIGID.basis);
  });

  for (const nullable of [false, true]) {
    it(`standaard attribuutmapper (gebruikersattribuut), allow.nullable.property=${nullable}: LegalSubjectID verdwijnt na een login zonder`, async () => {
      const mapperId = await addPortalIdpMapper({
        name: "legal-als-gebruikersattribuut",
        identityProviderAlias: "digid",
        identityProviderMapper: "saml-user-attribute-idp-mapper",
        config: {
          syncMode: "FORCE",
          "attribute.name": "urn:nl-eid-gdi:1.0:LegalSubjectID",
          "attribute.name.format": "ATTRIBUTE_FORMAT_URI",
          "user.attribute": "legal_attr",
          "allow.nullable.property": String(nullable),
        },
      });
      try {
        await loginForIdToken({ idp: "digid", username: "maria", password: "maria" });
        assert.equal((await portalUsersWithAttribute("legal_attr", "999990008")).length, 1);
        await setSimUserAttribute("maria", "legal_subject_id", null);
        await loginForIdToken({ idp: "digid", username: "maria", password: "maria" });
        const over = await portalUsersWithAttribute("legal_attr", "999990008");
        assert.equal(over.length, 0);
      } finally {
        await setSimUserAttribute("maria", "legal_subject_id", ["999990008"]);
        await deletePortalIdpMapper("digid", mapperId);
      }
    });
  }

  it("houdt bij twee gelijktijdige logins van dezelfde gebruiker elke login bij zijn eigen LegalSubjectID (sessienotitie, geen gebruikersattribuut)", async () => {
    try {
      const a = await login({ idp: "digid", username: "maria", password: "maria" }); // code nog niet ingewisseld
      await setSimUserAttribute("maria", "legal_subject_id", null);
      const b = await loginForIdToken({ idp: "digid", username: "maria", password: "maria" });
      const tokenA = decodeJwt((await exchange(a)).id_token);
      assert.equal(tokenA.legal_subject_id, "999990008");
      assert.equal(b.idToken.legal_subject_id, undefined);
    } finally {
      await setSimUserAttribute("maria", "legal_subject_id", ["999990008"]);
    }
  });
});

describe("Wallet via OIDC-broker", () => {
  it("zet inlogmiddel wallet, het niveau uit de acr van de wallet en de BSN als claim in het ID-token", async () => {
    const { idToken } = await loginForIdToken({ idp: "wallet", username: "jan", password: "jan" });
    assert.equal(idToken.inlogmiddel, "wallet");
    assert.equal(idToken.acr, "http://eidas.europa.eu/LoA/high");
    assert.equal(idToken.acting_subject_id, "999990008");
  });

  it("geeft DigiD en wallet voor dezelfde burger dezelfde BSN-claim, de sleutel voor gegevensaanvragen", async () => {
    const digid = await loginForIdToken({ idp: "digid", username: "jan", password: "jan" });
    const wallet = await loginForIdToken({ idp: "wallet", username: "jan", password: "jan" });
    assert.equal(digid.idToken.acting_subject_id, wallet.idToken.acting_subject_id);
    assert.notEqual(digid.idToken.sub, wallet.idToken.sub);
  });

  it("stuurt acr_values van de login-start door naar de wallet-koppeling", async () => {
    const result = await login({
      idp: "wallet",
      username: "jan",
      password: "jan",
      acrValues: "http://eidas.europa.eu/LoA/substantial",
    });
    const naarWallet = result.urls.find((u) => u.includes("/realms/idp-sim/protocol/openid-connect/auth"));
    assert.ok(naarWallet, "geen redirect naar de wallet-simulator");
    assert.equal(new URL(naarWallet).searchParams.get("acr_values"), "http://eidas.europa.eu/LoA/substantial");
  });
});

after(() => {});
