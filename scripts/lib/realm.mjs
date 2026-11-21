// Genereert het Keycloak-realm van één tenant uit het ene realm-template (`keycloak/realm-template/`) en het tenantbestand.
// Het template bestaat uit `realm.json` (de basis) en een fragment per inlogmiddel (`inlogmiddel/<naam>.json`); het resultaat
// laadt keycloak-config-cli. Geheimen staan niet in het resultaat: die verwijzen naar kcc-substitutie (`$(env:NAAM)`).
// Zonder externe dependencies, zoals de andere scripts in deze map.

import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { CLIENT_AUTH_ALG } from "./clientsleutel.mjs";

const TEMPLATE_MAP = join(dirname(fileURLToPath(import.meta.url)), "..", "..", "keycloak", "realm-template");

const SAML_KLASSE = "urn:oasis:names:tc:SAML:2.0:ac:classes:";
/** DigiD-niveaus als `AuthnContextClassRef` (simulatorwaarden uit #527; de echte DigiD-URN's zijn nog niet getest). */
export const DIGID_NIVEAU = {
  basis: `${SAML_KLASSE}PasswordProtectedTransport`,
  midden: `${SAML_KLASSE}MobileTwoFactorContract`,
  substantieel: `${SAML_KLASSE}Smartcard`,
  hoog: `${SAML_KLASSE}SmartcardPKI`,
};
const STANDAARD_NIVEAU = "substantieel";
/** In productie geldt minimaal Substantieel (spec #499, "Testinlogmiddel"); `basis` en `midden` zijn alleen buiten productie toegestaan. */
const PRODUCTIE_NIVEAUS = new Set(["substantieel", "hoog"]);
const OIN = /^[0-9]{20}$/;
const DOMEIN = /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$/;
const MAX_INACTIVITEIT = 15 * 60;
const MAX_ABSOLUUT = 8 * 60 * 60;

/** Inlogmiddelen met een eigen fragment; `test` heeft er geen (de demo-gebruikers komen alleen in ontwikkeling, zie `genereerRealm`). */
const KOPPELINGEN = { digid: ["singleSignOnServiceUrl", "idpEntityId", "signingCertificate"], wallet: ["authorizationUrl", "tokenUrl", "jwksUrl", "issuer", "clientId"] };
const URL_VELDEN = { digid: ["singleSignOnServiceUrl", "idpEntityId"], wallet: ["authorizationUrl", "tokenUrl", "jwksUrl", "issuer"] };
const BEKEND = new Set(["test", ...Object.keys(KOPPELINGEN)]);

const leesJson = (pad) => JSON.parse(readFileSync(join(TEMPLATE_MAP, pad), "utf8"));

/** ISO-8601-duur (dagen, uren, minuten, seconden) naar seconden. */
export function duurInSeconden(duur) {
  const m = /^P(?!$)(?:(\d+)D)?(?:T(?=\d)(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/.exec(duur);
  if (!m) throw new Error("Ongeldige duur (verwacht ISO-8601, bijvoorbeeld PT15M)");
  const [d, u, min, s] = m.slice(1).map((n) => Number(n ?? 0));
  return d * 86400 + u * 3600 + min * 60 + s;
}

function opzoeken(context, pad) {
  let waarde = context;
  for (const deel of pad.split(".")) {
    if (waarde === null || typeof waarde !== "object" || !(deel in waarde)) throw new Error(`Onbekende placeholder {{${pad}}} in het realm-template`);
    waarde = waarde[deel];
  }
  return waarde;
}

/** Vult `{{naam}}` in: een tekst die alleen uit een placeholder bestaat krijgt de waarde zelf (ook een lijst), anders wordt het tekst. */
function vul(waarde, context) {
  if (Array.isArray(waarde)) return waarde.map((w) => vul(w, context));
  if (waarde !== null && typeof waarde === "object") return Object.fromEntries(Object.entries(waarde).map(([k, v]) => [k, vul(v, context)]));
  if (typeof waarde !== "string") return waarde;
  const heel = /^\{\{([A-Za-z0-9_.]+)\}\}$/.exec(waarde);
  if (heel) return opzoeken(context, heel[1]);
  return waarde.replace(/\{\{([A-Za-z0-9_.]+)\}\}/g, (_, pad) => String(opzoeken(context, pad)));
}

/** Voegt fragmenten samen: objecten diep, lijsten achter elkaar, de rest wordt vervangen. */
function voegSamen(basis, extra) {
  if (Array.isArray(basis) && Array.isArray(extra)) return [...basis, ...extra];
  if (basis && extra && typeof basis === "object" && typeof extra === "object" && !Array.isArray(basis) && !Array.isArray(extra)) {
    const uit = { ...basis };
    for (const [k, v] of Object.entries(extra)) uit[k] = k in uit ? voegSamen(uit[k], v) : v;
    return uit;
  }
  return extra;
}

function controleerClientSleutels(sleutels) {
  if (!Array.isArray(sleutels) || sleutels.length === 0) throw new Error("Er is minstens één publieke clientsleutel nodig");
  const kids = new Set();
  for (const s of sleutels) {
    if (!s.kid) throw new Error("Een clientsleutel mist de kid (de JWK-thumbprint)");
    if (kids.has(s.kid)) throw new Error(`Dubbele kid ${s.kid} in de clientsleutels`);
    kids.add(s.kid);
    if (s.alg !== CLIENT_AUTH_ALG) throw new Error(`Clientsleutel ${s.kid}: alg moet ${CLIENT_AUTH_ALG} zijn`);
    if (s.use !== "sig" || s.kty !== "RSA") throw new Error(`Clientsleutel ${s.kid}: use moet sig zijn en kty RSA`);
    if (["d", "p", "q", "dp", "dq", "qi"].some((veld) => veld in s)) throw new Error(`Clientsleutel ${s.kid} bevat private sleutelonderdelen`);
  }
}

/** keycloak-config-cli vervangt `$(env:..)` en `$(file:..)` overal in het bestand; een waarde uit een tenantbestand mag dat niet uitlokken. */
function weigerSubstitutie(waarde, waar) {
  if (JSON.stringify(waarde ?? null).includes("$(")) throw new Error(`${waar} bevat "$(": kcc-substitutie uit een tenantbestand is niet toegestaan`);
}

function controleerKoppeling(naam, gegevens, metTls) {
  const g = gegevens ?? {};
  // Alleen een vastgepind certificaat: een metadata-URL laat Keycloak de sleutel van de IdP zelf ophalen en vervangen.
  if (Object.keys(g).some((veld) => /metadata/i.test(veld))) throw new Error(`Koppeling ${naam}: metadata-URL is niet toegestaan; pin het certificaat vast (signingCertificate)`);
  for (const veld of KOPPELINGEN[naam]) {
    if (typeof g[veld] !== "string" || g[veld].trim() === "") throw new Error(`Koppeling ${naam}: ${veld} ontbreekt`);
  }
  if (metTls) {
    for (const veld of URL_VELDEN[naam]) {
      if (!g[veld].startsWith("https://")) throw new Error(`Koppeling ${naam}: ${veld} moet https zijn buiten ontwikkeling`);
    }
  }
}

/**
 * @param {object} p
 * @param {object} p.tenant het samengevoegde tenantbestand (oin, naam, omgevingstype, domeinen, inlogmiddelen, minimumNiveau?, sessie?)
 * @param {string} p.realm naam van het realm
 * @param {string} p.brokerUrl publieke basis-URL van Keycloak, zonder slash
 * @param {object[]} p.clientSleutels publieke JWK's van `nl-portal-backend` (twee tijdens rotatie)
 * @param {object} p.idp koppelingsgegevens per inlogmiddel (`digid`, `wallet`)
 */
export function genereerRealm({ tenant, realm, brokerUrl, clientSleutels, idp = {} }) {
  const { oin, naam, omgevingstype, domeinen, inlogmiddelen } = tenant;
  if (!Array.isArray(domeinen) || domeinen.length === 0) throw new Error("De tenant heeft minstens één domein nodig");
  const productie = omgevingstype === "productie";
  const ontwikkeling = omgevingstype === "ontwikkeling";
  if (!OIN.test(String(oin))) throw new Error(`Ongeldig OIN ${JSON.stringify(oin)}: precies 20 cijfers`);
  weigerSubstitutie({ tenant, idp }, "De tenant of de koppelingsgegevens");
  for (const d of domeinen) if (typeof d !== "string" || !DOMEIN.test(d)) throw new Error(`Ongeldig domein ${JSON.stringify(d)}: alleen een hostnaam in kleine letters`);
  if (!ontwikkeling && !String(brokerUrl).startsWith("https://")) throw new Error("De broker-URL moet https zijn buiten ontwikkeling");
  for (const middel of inlogmiddelen) if (!BEKEND.has(middel)) throw new Error(`Inlogmiddel ${middel} heeft nog geen realm-fragment`);
  if (productie && inlogmiddelen.includes("test")) throw new Error("Het testinlogmiddel is niet toegestaan in productie");
  controleerClientSleutels(clientSleutels);
  for (const middel of inlogmiddelen.filter((m) => m in KOPPELINGEN)) controleerKoppeling(middel, idp[middel], !ontwikkeling);

  // Ontwikkeling draait de frontend op poort 3000 over http; alle andere omgevingen zitten achter TLS op het gemeentedomein.
  const origins = domeinen.map((d) => (ontwikkeling ? `http://${d}:3000` : `https://${d}`));
  const [origin] = origins;
  const sessie = tenant.sessie ?? {};
  const inactiviteit = sessie.inactiviteit ? duurInSeconden(sessie.inactiviteit) : MAX_INACTIVITEIT;
  const absoluut = sessie.absoluut ? duurInSeconden(sessie.absoluut) : MAX_ABSOLUUT;
  if (inactiviteit > MAX_INACTIVITEIT || absoluut > MAX_ABSOLUUT) throw new Error("De sessietijden mogen niet langer zijn dan 15 minuten inactiviteit en 8 uur absoluut");
  if (inactiviteit <= 0 || absoluut <= 0 || inactiviteit > absoluut) throw new Error("De sessietijden moeten positief zijn en de inactiviteit mag niet langer zijn dan de absolute duur");
  const niveau = tenant.minimumNiveau ?? STANDAARD_NIVEAU;
  if (typeof niveau !== "string" || !Object.hasOwn(DIGID_NIVEAU, niveau)) throw new Error(`Onbekend minimumNiveau ${JSON.stringify(niveau)}`);
  if (productie && !PRODUCTIE_NIVEAUS.has(niveau)) throw new Error("In productie is het minimumNiveau minstens substantieel");

  const context = {
    realm,
    naam,
    brokerUrl,
    sslRequired: ontwikkeling ? "external" : "all",
    ssoSessionIdleTimeout: inactiviteit,
    ssoSessionMaxLifespan: absoluut,
    browserFlow: inlogmiddelen.includes("test") ? "browser" : "alleen-koppelingen",
    clientAuthAlg: CLIENT_AUTH_ALG,
    jwks: JSON.stringify({ keys: clientSleutels }),
    redirectUris: origins.map((o) => `${o}/login/oauth2/code/${oin}`),
    postLogoutRedirectUris: origins.map((o) => `${o}/`).join("##"),
    backchannelLogoutUrl: `${origin}/logout/connect/back-channel/${oin}`,
    digidAuthnContextClassRefs: JSON.stringify([DIGID_NIVEAU[niveau]]),
    idp,
  };

  let uit = leesJson("realm.json");
  for (const middel of inlogmiddelen.filter((m) => m in KOPPELINGEN)) uit = voegSamen(uit, leesJson(`inlogmiddel/${middel}.json`));
  // Alleen in ontwikkeling: de demo-gebruikers met vaste wachtwoorden (hashes in Git).
  if (ontwikkeling && inlogmiddelen.includes("test")) uit = voegSamen(uit, leesJson("demo-gebruikers.json"));
  return vul(uit, context);
}
