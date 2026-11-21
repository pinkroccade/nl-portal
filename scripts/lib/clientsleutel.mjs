// Clientsleutel voor `private_key_jwt` (ADR 0022 besluit 9, prototype #501): RSA 3072 bit, algoritme PS256, `kid` = JWK-thumbprint.
// De publieke sleutel gaat in het realm-template (geen geheim), de private JWK naar de bestandsmount van de backend.
import { constants, createHash, createPrivateKey, generateKeyPairSync, randomBytes, sign } from "node:crypto";

export const CLIENT_AUTH_ALG = "PS256";

/** JWK-thumbprint volgens RFC 7638 (SHA-256, base64url) over de verplichte RSA-leden. */
export function thumbprint({ e, kty, n }) {
  return createHash("sha256").update(JSON.stringify({ e, kty, n })).digest("base64url");
}

/** Een nieuw RSA-sleutelpaar als twee JWK's: `publiek` voor het realm, `prive` voor de backend. */
export function maakClientSleutel(alg = CLIENT_AUTH_ALG) {
  const { privateKey, publicKey } = generateKeyPairSync("rsa", { modulusLength: 3072 });
  const kaal = publicKey.export({ format: "jwk" });
  const kid = thumbprint(kaal);
  const kop = { kid, use: "sig", alg };
  return { kid, alg, publiek: { ...kaal, ...kop }, prive: { ...privateKey.export({ format: "jwk" }), ...kop } };
}

const b64 = (waarde) => Buffer.from(JSON.stringify(waarde)).toString("base64url");

/** Een client-assertion (RFC 7523) voor de tests: `iss` = `sub` = client-id, `aud` = issuer, unieke `jti` van 256 bit, 60 s. */
export function clientAssertion(prive, { clientId, audience }) {
  const nu = Math.floor(Date.now() / 1000);
  const kop = b64({ alg: prive.alg, typ: "JWT", kid: prive.kid });
  const claims = b64({ iss: clientId, sub: clientId, aud: audience, jti: randomBytes(32).toString("base64url"), iat: nu, exp: nu + 60 });
  const opties = prive.alg === "PS256" ? { padding: constants.RSA_PKCS1_PSS_PADDING, saltLength: 32 } : {};
  const sleutel = createPrivateKey({ key: prive, format: "jwk" });
  return `${kop}.${claims}.${sign("sha256", Buffer.from(`${kop}.${claims}`), { key: sleutel, ...opties }).toString("base64url")}`;
}
