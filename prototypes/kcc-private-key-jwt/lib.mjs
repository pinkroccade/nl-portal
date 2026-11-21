import { generateKeyPairSync, sign, randomUUID, constants } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const b64 = (v) => Buffer.from(typeof v === 'string' ? v : JSON.stringify(v)).toString('base64url')

/** Het algoritme voor clientauthenticatie: NL GOV OAuth §1.3.3 eist RS256 en beveelt PS256 aan; ES256 staat er niet in. */
export const ALG = process.env.CLIENT_AUTH_ALG ?? 'PS256'

/** Een RSA-sleutelpaar (3072 bit) met een kid, de publieke kant als JWK voor het realm. */
export function maakSleutel(kid) {
  const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 3072 })
  const publiek = { ...publicKey.export({ format: 'jwk' }), kid, use: 'sig', alg: ALG }
  return { kid, privateKey, publiek }
}

/** Een client-assertion volgens RFC 7523 / OIDC Core 9: iss=sub=client_id, aud=token-endpoint, unieke jti. */
export function clientAssertion(sleutel, { clientId, audience, lifetimeSeconds = 60 }) {
  const nu = Math.floor(Date.now() / 1000)
  const kop = b64({ alg: ALG, typ: 'JWT', kid: sleutel.kid })
  const claims = b64({ iss: clientId, sub: clientId, aud: audience, jti: randomUUID() + randomUUID(), iat: nu, exp: nu + lifetimeSeconds })
  const opties = ALG === 'PS256' ? { padding: constants.RSA_PKCS1_PSS_PADDING, saltLength: 32 } : {}
  const handtekening = sign('sha256', Buffer.from(`${kop}.${claims}`), { key: sleutel.privateKey, ...opties })
  return `${kop}.${claims}.${handtekening.toString('base64url')}`
}

/** Rendert het realm-template met de tenantwaarden (in productie doet Helm dit; secrets gaan via kcc-substitutie). */
export function rendeerRealm({ realm, clientId, redirectUris, jwks }) {
  const alg = ALG
  const sjabloon = JSON.parse(readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'realm-template.json'), 'utf8'))
  const vul = (waarde) => {
    if (Array.isArray(waarde)) return waarde.map(vul)
    if (waarde && typeof waarde === 'object') return Object.fromEntries(Object.entries(waarde).map(([k, v]) => [k, vul(v)]))
    const waarden = { '$(realm)': realm, '$(clientId)': clientId, '$(redirectUris)': redirectUris, '$(jwks)': JSON.stringify(jwks), '$(clientAuthAlg)': alg }
    return waarde in waarden ? waarden[waarde] : waarde
  }
  return vul(sjabloon)
}
