// Integratietest van de Keycloak-provider (#527; bewezen in het prototype #500). Wat komt er in het ID-token van
// `nl-portal-backend` na een login via een DigiD-koppeling (SAML, gesimuleerd) en een wallet-koppeling (OIDC, gesimuleerd),
// met de mappers van het eigen image? Eerste login, latere login en herauthenticatie in dezelfde Keycloak-sessie.
import { before, beforeEach, describe, it } from "node:test";
import assert from "node:assert/strict";
import {
  DIGID,
  Jar,
  clearDigidRequestedLevel,
  createPortalUser,
  decodeJwt,
  deletePortalUsers,
  exchange,
  portalUsers,
  removeWalletSimMapper,
  restoreWalletSimMapper,
  login,
  loginForIdToken,
  provisionDigidSigningCert,
  samlAuthnRequest,
  setDigidRequestedLevel,
  setSimUserAttribute,
  userinfo,
  waitForKeycloak,
} from "./stack.mjs";

before(async () => {
  await waitForKeycloak();
  await provisionDigidSigningCert();
});

// Een claim die er niet is of leeg is, geldt voor de backend als afwezig.
// Een geweigerde login toont een Keycloak-foutpagina (geen wachtwoordformulier, geen code). Alleen code === null zou
// ook slagen bij een kapotte simulator; daarom toetsen we ook dat het een foutpagina van Keycloak is.
const assertGeweigerd = (result) => {
  assert.equal(result.code, null);
  assert.ok(!result.wachtwoordformulier, `terugval op het wachtwoordformulier: ${result.pageText}`);
  assert.match(result.pageText ?? "", /sorry|error|authenticat|identity provider/i, `geen foutpagina van Keycloak: ${result.pageText}`);
};

const afwezig = (waarde) => waarde === undefined || waarde === "";

describe("DigiD via SAML-broker", () => {
  beforeEach(() => setDigidRequestedLevel(DIGID.midden));

  it("zet inlogmiddel digid, betrouwbaarheidsniveau en BSN van de actor (in userinfo, niet in het ID-token)", async () => {
    const { idToken, userinfo } = await loginForIdToken({ idp: "digid", username: "jan", password: "jan" });
    assert.equal(idToken.inlogmiddel, "digid");
    assert.equal(idToken.acr, DIGID.midden);
    assert.equal(userinfo.acting_subject_id, "999990008");
    assert.ok(afwezig(idToken.acting_subject_id), "het BSN staat niet in het ID-token");
    assert.ok(afwezig(idToken.legal_subject_id));
  });

  it("levert het niveau dat de koppeling bij DigiD opvraagt, van Basis tot Hoog", async () => {
    for (const level of ["substantieel", "basis", "hoog"]) {
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
    const result = await login({ idp: "digid", username: "jan", password: "jan", acrValues: DIGID.substantieel });
    const request = samlAuthnRequest(result);
    assert.doesNotMatch(request, new RegExp(DIGID.substantieel));
    assert.match(request, new RegExp(DIGID.midden));
  });

  it("geeft bij vertegenwoordiging LegalSubjectID als aparte claim naast de BSN van de actor", async () => {
    const { idToken, userinfo } = await loginForIdToken({ idp: "digid", username: "maria", password: "maria" });
    assert.equal(userinfo.acting_subject_id, "999990020");
    assert.ok(afwezig(idToken.acting_subject_id), "het BSN staat niet in het ID-token");
    assert.equal(idToken.legal_subject_id, "999990008");
  });

  it("laat legal_subject_id vervallen bij een volgende login in een nieuwe browsersessie zonder LegalSubjectID", async () => {
    try {
      const eerste = await loginForIdToken({ idp: "digid", username: "maria", password: "maria" });
      assert.equal(eerste.idToken.legal_subject_id, "999990008");
      await setSimUserAttribute("maria", "legal_subject_id", null);
      const tweede = await loginForIdToken({ idp: "digid", username: "maria", password: "maria" });
      assert.ok(afwezig(tweede.idToken.legal_subject_id));
    } finally {
      await setSimUserAttribute("maria", "legal_subject_id", ["999990008"]);
    }
  });
});

describe("Beperkingen en randgevallen van Keycloak", () => {
  beforeEach(() => setDigidRequestedLevel(DIGID.midden));

  // Bewezen is alleen dit: Keycloak eist zelf geen niveau van een koppeling die niets opvraagt. Dat een IdP die het
  // minimum negeert (Basis geleverd bij minimum Midden) ook slaagt, is niet te simuleren: de Keycloak-simulator levert
  // altijd het gevraagde niveau of weigert. Die laatste stap dekt de backend-toets, niet deze test.
  it("laat een login zonder gevraagd niveau slagen op Basis: Keycloak toetst het niveau zelf niet, de backend moet de claim toetsen", async () => {
    await clearDigidRequestedLevel();
    const { idToken } = await loginForIdToken({ idp: "digid", username: "jan", password: "jan" });
    assert.equal(idToken.acr, DIGID.basis);
  });

  it("houdt bij twee gelijktijdige logins van dezelfde gebruiker elke login bij zijn eigen LegalSubjectID (sessienotitie, geen gebruikersattribuut)", async () => {
    try {
      const a = await login({ idp: "digid", username: "maria", password: "maria" }); // code nog niet ingewisseld
      await setSimUserAttribute("maria", "legal_subject_id", null);
      const b = await loginForIdToken({ idp: "digid", username: "maria", password: "maria" });
      const tokenA = decodeJwt((await exchange(a)).id_token);
      assert.equal(tokenA.legal_subject_id, "999990008");
      assert.ok(afwezig(b.idToken.legal_subject_id));
    } finally {
      await setSimUserAttribute("maria", "legal_subject_id", ["999990008"]);
    }
  });
  it("laat een login met twee waarden voor LegalSubjectID falen in plaats van een waarde te kiezen", async () => {
    try {
      await setSimUserAttribute("maria", "legal_subject_id", ["999990008", "999990016"]);
      const result = await login({ idp: "digid", username: "maria", password: "maria" });
      assertGeweigerd(result);
    } finally {
      await setSimUserAttribute("maria", "legal_subject_id", ["999990008"]);
    }
  });
});

describe("Eerste login van een nieuwe gebruiker", () => {
  beforeEach(() => setDigidRequestedLevel(DIGID.midden));

  it("zet bij DigiD acr, inlogmiddel en de BSN's ook als de Keycloak-gebruiker nog niet bestaat", async () => {
    await deletePortalUsers();
    const { idToken, userinfo } = await loginForIdToken({ idp: "digid", username: "maria", password: "maria" });
    assert.equal(idToken.acr, DIGID.midden);
    assert.equal(idToken.inlogmiddel, "digid");
    assert.equal(userinfo.acting_subject_id, "999990020");
    assert.ok(afwezig(idToken.acting_subject_id), "het BSN staat niet in het ID-token");
    assert.equal(idToken.legal_subject_id, "999990008");
  });

  it("zet bij de wallet acr, inlogmiddel en de BSN ook als de Keycloak-gebruiker nog niet bestaat", async () => {
    await deletePortalUsers();
    const { idToken, userinfo } = await loginForIdToken({ idp: "wallet", username: "jan", password: "jan" });
    assert.equal(idToken.acr, "http://eidas.europa.eu/LoA/high");
    assert.equal(idToken.inlogmiddel, "wallet");
    assert.equal(userinfo.acting_subject_id, "999990008");
    assert.ok(afwezig(idToken.acting_subject_id), "het BSN staat niet in het ID-token");
  });
});

describe("Herauthenticatie in dezelfde Keycloak-sessie", () => {
  beforeEach(() => setDigidRequestedLevel(DIGID.midden));

  it("laat legal_subject_id niet blijven hangen als dezelfde gebruiker met prompt=login opnieuw inlogt zonder LegalSubjectID", async () => {
    const jar = new Jar(); // één browser: de SSO-sessie van Keycloak blijft staan
    try {
      const eerste = await loginForIdToken({ idp: "digid", username: "maria", password: "maria", jar });
      assert.equal(eerste.idToken.legal_subject_id, "999990008");
      await setSimUserAttribute("maria", "legal_subject_id", null);
      const tweede = await loginForIdToken({ idp: "digid", username: "maria", password: "maria", jar, prompt: "login" });
      assert.ok(afwezig(tweede.idToken.legal_subject_id), `restant van de eerdere login: ${tweede.idToken.legal_subject_id}`);
    } finally {
      await setSimUserAttribute("maria", "legal_subject_id", ["999990008"]);
    }
  });

  // De ingebouwde wallet-mapper (oidc-user-session-note-idp-mapper) zet een notitie alleen als de claim er is; een
  // waarde van de eerste login blijft dan staan. Deze test hoort rood te zijn op de ingebouwde mapper en groen op een
  // wissende wallet-mapper.
  it("laat acr en acting_subject_id van de wallet niet blijven hangen als dezelfde gebruiker met prompt=login opnieuw inlogt zonder die claims", async () => {
    const jar = new Jar();
    let verwijderd;
    try {
      const eerste = await loginForIdToken({ idp: "wallet", username: "jan", password: "jan", jar });
      assert.equal(eerste.idToken.acr, "http://eidas.europa.eu/LoA/high");
      assert.ok(eerste.userinfo.acting_subject_id, "de eerste wallet-login levert geen BSN");
      verwijderd = await removeWalletSimMapper("acr-eidas");
      await setSimUserAttribute("jan", "acting_subject_id", null);
      const tweede = await login({ idp: "wallet", username: "jan", password: "jan", jar, prompt: "login" });
      assert.ok(tweede.code, `geen code: ${tweede.pageText ?? tweede.errorDescription}`);
      const tokens = await exchange(tweede);
      const token = decodeJwt(tokens.id_token);
      const info = await userinfo(tokens.access_token);
      assert.equal(token.inlogmiddel, "wallet");
      assert.ok(afwezig(token.acr), `niveau van de eerdere login blijft staan: ${token.acr}`);
      assert.ok(afwezig(info.acting_subject_id), `BSN van de eerdere login blijft staan: ${info.acting_subject_id}`);
    } finally {
      if (verwijderd) await restoreWalletSimMapper(verwijderd);
      await setSimUserAttribute("jan", "acting_subject_id", ["999990008"]);
    }
  });

  // Zonder prompt=login is er geen nieuwe login: Keycloak geeft de bestaande sessie terug, ook als de IdP inmiddels
  // andere gegevens zou leveren. Daarom start de BFF elke login met prompt=login en toetst auth_time.
  it("geeft zonder prompt=login binnen een lopende sessie geen nieuwe login maar het token van de eerdere", async () => {
    const jar = new Jar();
    try {
      const eerste = await loginForIdToken({ idp: "digid", username: "maria", password: "maria", jar });
      await setSimUserAttribute("maria", "legal_subject_id", null);
      const tweede = await loginForIdToken({ idp: "digid", username: "maria", password: "maria", jar });
      assert.equal(tweede.idToken.sid, eerste.idToken.sid);
      assert.equal(tweede.idToken.auth_time, eerste.idToken.auth_time);
      assert.equal(tweede.idToken.legal_subject_id, "999990008");
    } finally {
      await setSimUserAttribute("maria", "legal_subject_id", ["999990008"]);
    }
  });

  // Dit is een gedrag van Keycloak dat de BFF moet afvangen (prompt=login, auth_time-toets), geen fout van de mapper.
  it("geeft zonder prompt=login binnen een lopende DigiD-sessie geen nieuwe login: kc_idp_hint=wallet levert inlogmiddel digid", async () => {
    const jar = new Jar();
    await loginForIdToken({ idp: "digid", username: "jan", password: "jan", jar });
    const wallet = await loginForIdToken({ idp: "wallet", username: "jan", password: "jan", jar });
    assert.equal(wallet.idToken.inlogmiddel, "digid");
  });

  it("weigert met prompt=login een login via een ander inlogmiddel binnen een lopende sessie van een andere identiteit", async () => {
    const jar = new Jar();
    await loginForIdToken({ idp: "digid", username: "jan", password: "jan", jar });
    const wallet = await login({ idp: "wallet", username: "jan", password: "jan", jar, prompt: "login" });
    assertGeweigerd(wallet);
  });
});

describe("Koppeling van identiteiten en browserflow", () => {
  it("koppelt een tweede inlogmiddel met hetzelfde e-mailadres of dezelfde naam niet aan een bestaande gebruiker", async () => {
    await deletePortalUsers();
    const bestaand = await createPortalUser({ username: "jan", email: "jan@example.invalid", firstName: "Jan", lastName: "Jansen" });
    try {
      const result = await loginForIdToken({ idp: "wallet", username: "jan", password: "jan" });
      if (result.idToken) assert.notEqual(result.idToken.sub, bestaand, "de wallet-login is bij de bestaande gebruiker uitgekomen");
      else assertGeweigerd(result);
    } finally {
      await deletePortalUsers();
    }
  });

  it("geeft elke koppeling een eigen gebruikersnaam: de naam van de IdP-gebruiker is niet de sleutel", async () => {
    await deletePortalUsers();
    await setDigidRequestedLevel(DIGID.midden);
    await loginForIdToken({ idp: "wallet", username: "jan", password: "jan" });
    await loginForIdToken({ idp: "digid", username: "jan", password: "jan" });
    const namen = (await portalUsers()).map((u) => u.username).sort();
    assert.equal(namen.length, 2);
    assert.match(namen[0], /^digid\./);
    assert.match(namen[1], /^wallet\./);
  });

  it("biedt zonder koppeling in de login-start geen wachtwoordformulier aan", async () => {
    const result = await login({ idp: undefined, username: "jan", password: "jan" });
    assert.equal(result.code, null);
    assert.ok(!result.pages.some((p) => /name="password"/.test(p)), "er is een wachtwoordformulier getoond");
  });
});

describe("Wallet via OIDC-broker", () => {
  it("zet inlogmiddel wallet, het niveau uit de acr van de wallet en de BSN als claim in het ID-token", async () => {
    const { idToken, userinfo } = await loginForIdToken({ idp: "wallet", username: "jan", password: "jan" });
    assert.equal(idToken.inlogmiddel, "wallet");
    assert.equal(idToken.acr, "http://eidas.europa.eu/LoA/high");
    assert.equal(userinfo.acting_subject_id, "999990008");
    assert.ok(afwezig(idToken.acting_subject_id), "het BSN staat niet in het ID-token");
  });

  it("geeft DigiD en wallet voor dezelfde burger dezelfde BSN-claim, de sleutel voor gegevensaanvragen", async () => {
    const digid = await loginForIdToken({ idp: "digid", username: "jan", password: "jan" });
    const wallet = await loginForIdToken({ idp: "wallet", username: "jan", password: "jan" });
    assert.equal(digid.userinfo.acting_subject_id, wallet.userinfo.acting_subject_id);
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
