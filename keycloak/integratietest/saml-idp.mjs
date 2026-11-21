// Een minimale SAML-IdP voor de test (#527): een wegwerpsleutel met zelfondertekend certificaat en assertions die in
// exclusieve canonicalisatie al in canonieke vorm worden geschreven, zodat de handtekening zonder XML-bibliotheek te
// berekenen is (geen extra dependency in de supply chain). Alleen voor de integratietest; niet als voorbeeld voor DigiD.
import { createHash, createSign, generateKeyPairSync, randomBytes } from "node:crypto";

const NS_SAML = "urn:oasis:names:tc:SAML:2.0:assertion";
const NS_SAMLP = "urn:oasis:names:tc:SAML:2.0:protocol";
const NS_DS = "http://www.w3.org/2000/09/xmldsig#";
const C14N = "http://www.w3.org/2001/10/xml-exc-c14n#";

// --- zelfondertekend certificaat (DER), zodat Keycloak het certificaat kan vastpinnen ---

const derLength = (n) => (n < 128 ? Buffer.from([n]) : n < 256 ? Buffer.from([0x81, n]) : Buffer.from([0x82, n >> 8, n & 255]));
const tlv = (tag, ...parts) => {
  const content = Buffer.concat(parts);
  return Buffer.concat([Buffer.from([tag]), derLength(content.length), content]);
};
const seq = (...parts) => tlv(0x30, ...parts);
const integer = (buf) => tlv(2, buf[0] & 0x80 ? Buffer.concat([Buffer.from([0]), buf]) : buf);
const oid = (dotted) => {
  const parts = dotted.split(".").map(Number);
  const bytes = [parts[0] * 40 + parts[1]];
  for (const part of parts.slice(2)) {
    const chunk = [part & 0x7f];
    for (let rest = part >> 7; rest; rest >>= 7) chunk.unshift((rest & 0x7f) | 0x80);
    bytes.push(...chunk);
  }
  return tlv(6, Buffer.from(bytes));
};
const utcTime = (date) => tlv(0x17, Buffer.from(`${date.toISOString().slice(2, 19).replace(/[-:T]/g, "")}Z`));

/** Een nieuw RSA-sleutelpaar met zelfondertekend certificaat; `certificaat` is base64 van de DER, zoals Keycloak het verwacht. */
export function nieuweSleutel(naam) {
  const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
  const algoritme = seq(oid("1.2.840.113549.1.1.11"), tlv(5));
  const subject = seq(tlv(0x31, seq(oid("2.5.4.3"), tlv(0x0c, Buffer.from(naam)))));
  const nu = Date.now();
  const serial = randomBytes(8);
  serial[0] &= 0x7f;
  const tbs = seq(
    tlv(0xa0, integer(Buffer.from([2]))),
    integer(serial),
    algoritme,
    subject,
    seq(utcTime(new Date(nu - 3600_000)), utcTime(new Date(nu + 86_400_000))),
    subject,
    publicKey.export({ type: "spki", format: "der" }),
  );
  const handtekening = createSign("sha256").update(tbs).sign(privateKey);
  const der = seq(tbs, algoritme, tlv(3, Buffer.from([0]), handtekening));
  return { privateKey, certificaat: der.toString("base64") };
}

// --- XML in canonieke vorm: geen witruimte, attributen alfabetisch, geen lege-elementnotatie ---

const escapeTekst = (s) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

function attribuut({ naam, waarden }) {
  const values = waarden
    .map((w) => `<saml:AttributeValue>${typeof w === "string" ? escapeTekst(w) : w.xml}</saml:AttributeValue>`)
    .join("");
  return `<saml:Attribute Name="${naam}" NameFormat="urn:oasis:names:tc:SAML:2.0:attrname-format:uri">${values}</saml:Attribute>`;
}

function handtekening(id, inhoudZonderHandtekening, sleutel) {
  const digest = createHash("sha256").update(inhoudZonderHandtekening).digest("base64");
  const signedInfo =
    `<ds:SignedInfo><ds:CanonicalizationMethod Algorithm="${C14N}"></ds:CanonicalizationMethod>` +
    `<ds:SignatureMethod Algorithm="http://www.w3.org/2001/04/xmldsig-more#rsa-sha256"></ds:SignatureMethod>` +
    `<ds:Reference URI="#${id}"><ds:Transforms>` +
    `<ds:Transform Algorithm="${NS_DS}enveloped-signature"></ds:Transform>` +
    `<ds:Transform Algorithm="${C14N}"></ds:Transform></ds:Transforms>` +
    `<ds:DigestMethod Algorithm="http://www.w3.org/2001/04/xmlenc#sha256"></ds:DigestMethod>` +
    `<ds:DigestValue>${digest}</ds:DigestValue></ds:Reference></ds:SignedInfo>`;
  // Canoniek staat de naamruimte op SignedInfo zelf; in het document staat hij op ds:Signature.
  const canoniek = signedInfo.replace("<ds:SignedInfo>", `<ds:SignedInfo xmlns:ds="${NS_DS}">`);
  const waarde = createSign("sha256").update(canoniek).sign(sleutel.privateKey, "base64");
  return (
    `<ds:Signature xmlns:ds="${NS_DS}">${signedInfo}<ds:SignatureValue>${waarde}</ds:SignatureValue>` +
    `<ds:KeyInfo><ds:X509Data><ds:X509Certificate>${sleutel.certificaat}</ds:X509Certificate></ds:X509Data></ds:KeyInfo></ds:Signature>`
  );
}

/**
 * Een assertion als tekst. `sleutel` ondertekent hem (enveloped, over de assertion zelf); zonder sleutel blijft hij
 * ongetekend. `authn` is de lijst AuthnContextClassRefs, een per AuthnStatement (`null` = statement zonder ClassRef).
 * `advice` is XML dat in het Advice-element komt (een ingepakte assertion).
 */
export function assertion({ id, requestId, endpoint, sp, idp, subject = "handmatig-1", authn = [], attributen = [], versleuteldAttribuut = "", advice = "", sleutel = null }) {
  const nu = new Date();
  const instant = nu.toISOString();
  const verloopt = new Date(nu.getTime() + 5 * 60_000).toISOString();
  const open = `<saml:Assertion xmlns:saml="${NS_SAML}" ID="${id}" IssueInstant="${instant}" Version="2.0">`;
  const issuer = `<saml:Issuer>${idp}</saml:Issuer>`;
  const rest =
    `<saml:Subject><saml:NameID Format="urn:oasis:names:tc:SAML:2.0:nameid-format:persistent">${escapeTekst(subject)}</saml:NameID>` +
    `<saml:SubjectConfirmation Method="urn:oasis:names:tc:SAML:2.0:cm:bearer">` +
    `<saml:SubjectConfirmationData InResponseTo="${requestId}" NotOnOrAfter="${verloopt}" Recipient="${endpoint}"></saml:SubjectConfirmationData>` +
    `</saml:SubjectConfirmation></saml:Subject>` +
    `<saml:Conditions NotBefore="${instant}" NotOnOrAfter="${verloopt}"><saml:AudienceRestriction><saml:Audience>${sp}</saml:Audience></saml:AudienceRestriction></saml:Conditions>` +
    (advice ? `<saml:Advice>${advice}</saml:Advice>` : "") +
    authn
      .map((classRef) =>
        classRef === null
          ? `<saml:AuthnStatement AuthnInstant="${instant}"><saml:AuthnContext></saml:AuthnContext></saml:AuthnStatement>`
          : `<saml:AuthnStatement AuthnInstant="${instant}"><saml:AuthnContext><saml:AuthnContextClassRef>${classRef}</saml:AuthnContextClassRef></saml:AuthnContext></saml:AuthnStatement>`,
      )
      .join("") +
    (attributen.length || versleuteldAttribuut
      ? `<saml:AttributeStatement>${attributen.map(attribuut).join("")}${versleuteldAttribuut}</saml:AttributeStatement>`
      : "");
  const zonder = `${open}${issuer}${rest}</saml:Assertion>`;
  if (!sleutel) return zonder;
  return `${open}${issuer}${handtekening(id, zonder, sleutel)}${rest}</saml:Assertion>`;
}

/** Een Response met deze kinderen (assertions, en eventueel een Extensions-element ervoor). */
export function response({ requestId, endpoint, idp, voorStatus = "", assertions }) {
  const instant = new Date().toISOString();
  return (
    `<samlp:Response xmlns:samlp="${NS_SAMLP}" xmlns:saml="${NS_SAML}" ID="_r${randomBytes(6).toString("hex")}" Version="2.0" IssueInstant="${instant}" Destination="${endpoint}" InResponseTo="${requestId}">` +
    `<saml:Issuer>${idp}</saml:Issuer>${voorStatus}<samlp:Status><samlp:StatusCode Value="urn:oasis:names:tc:SAML:2.0:status:Success"></samlp:StatusCode></samlp:Status>` +
    `${assertions}</samlp:Response>`
  );
}

export const nieuwId = () => `_${randomBytes(8).toString("hex")}`;
