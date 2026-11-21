import { generateKeyPairSync, sign, randomBytes, createHash, constants } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const b64 = (v) => Buffer.from(typeof v === 'string' ? v : JSON.stringify(v)).toString('base64url')

/**
 * Het algoritme voor clientauthenticatie. OpenID NLGov 1.0.1 §7.2.1: RS256 MUST ondersteund worden, PS256 SHOULD,
 * "PS256 is RECOMMENDED over RS256", andere algoritmen mogen als ze minstens even sterk zijn (dus ES256 mag ook).
 */
export const ALG = process.env.CLIENT_AUTH_ALG ?? 'PS256'
/** Het andere algoritme, voor de alg-pinningtests. */
export const ANDER_ALG = ALG === 'PS256' ? 'RS256' : 'PS256'

/** JWK-thumbprint volgens RFC 7638 (SHA-256, base64url) over de verplichte RSA-leden. */
export function thumbprint({ e, kty, n }) {
  return createHash('sha256').update(JSON.stringify({ e, kty, n })).digest('base64url')
}

/** Een RSA-sleutelpaar (3072 bit); de `kid` is de JWK-thumbprint, dus uniek en zonder beheer. */
export function maakSleutel(alg = ALG) {
  const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 3072 })
  const kaal = publicKey.export({ format: 'jwk' })
  const kid = thumbprint(kaal)
  return { kid, alg, privateKey, publiek: { ...kaal, kid, use: 'sig', alg } }
}

const ondertekenOpties = (alg) => (alg === 'PS256' ? { padding: constants.RSA_PKCS1_PSS_PADDING, saltLength: 32 } : {})

/**
 * Een client-assertion volgens RFC 7523 / OIDC Core 9: iss=sub=client_id, unieke jti van 256 bit.
 * Met `opties` is elk onderdeel te verknoeien voor de negatieve tests: aud, iss, sub, alg, kid, typ, jti,
 * iatOffset/expOffset (seconden t.o.v. nu), zonderIat, `handtekening: 'gemanipuleerd' | 'none'`.
 */
export function clientAssertion(sleutel, { clientId, audience, lifetimeSeconds = 60, ...opties }) {
  const nu = Math.floor(Date.now() / 1000)
  const alg = opties.alg ?? sleutel.alg
  const kop = b64({ alg: opties.handtekening === 'none' ? 'none' : alg, typ: opties.typ ?? 'JWT', kid: opties.kid ?? sleutel.kid })
  const claims = b64({
    iss: opties.iss ?? clientId,
    sub: opties.sub ?? clientId,
    aud: audience,
    jti: opties.jti ?? randomBytes(32).toString('base64url'),
    ...(opties.zonderIat ? {} : { iat: nu + (opties.iatOffset ?? 0) }),
    exp: nu + (opties.expOffset ?? lifetimeSeconds),
  })
  if (opties.handtekening === 'none') return `${kop}.${claims}.`
  let handtekening = sign('sha256', Buffer.from(`${kop}.${claims}`), { key: sleutel.privateKey, ...ondertekenOpties(alg) }).toString('base64url')
  if (opties.handtekening === 'gemanipuleerd') {
    const midden = Math.floor(handtekening.length / 2)
    handtekening = handtekening.slice(0, midden) + (handtekening[midden] === 'A' ? 'B' : 'A') + handtekening.slice(midden + 1)
  }
  return `${kop}.${claims}.${handtekening}`
}

/** Rendert het realm-template met de tenantwaarden (in productie doet Helm dit; secrets gaan via kcc-substitutie). */
export function rendeerRealm({ realm, clientId, redirectUris, jwks, alg = ALG }) {
  const sjabloon = JSON.parse(readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'realm-template.json'), 'utf8'))
  const waarden = { '$(realm)': realm, '$(clientId)': clientId, '$(redirectUris)': redirectUris, '$(jwks)': JSON.stringify(jwks), '$(clientAuthAlg)': alg }
  const vul = (waarde) => {
    if (Array.isArray(waarde)) return waarde.map(vul)
    if (waarde && typeof waarde === 'object') return Object.fromEntries(Object.entries(waarde).map(([k, v]) => [k, vul(v)]))
    return waarde in waarden ? waarden[waarde] : waarde
  }
  return vul(sjabloon)
}

/**
 * Alleen voor de proef: het template eist TLS (`sslRequired: all`), de testcontainer praat HTTP, en de code-flow
 * heeft een testgebruiker nodig. Dit overlay staat dus niet in het template.
 */
export function metTestOverlay(realm, gebruiker) {
  return {
    ...realm,
    sslRequired: 'none',
    users: [{ username: gebruiker.naam, enabled: true, emailVerified: true, email: 'burger@example.test', firstName: 'Test', lastName: 'Burger', credentials: [{ type: 'password', value: gebruiker.wachtwoord, temporary: false }] }],
  }
}
