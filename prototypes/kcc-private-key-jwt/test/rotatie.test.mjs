// Prototype #501: bewijst op een echte Keycloak 26.8 met keycloak-config-cli dat de client nl-portal-backend per
// tenant met private_key_jwt authenticeert (autorisatiecode-uitwisseling met PKCE, geen client_credentials),
// ook met twee sleutels tijdens rotatie, en dat Keycloak alles wat niet klopt weigert.
// Seam: het autorisatie- en token-endpoint van Keycloak (HTTP) na een kcc-import; geen mocks.
// Elke test importeert zelf wat hij nodig heeft, dus de tests zijn los te draaien (--test-name-pattern).
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { createHash, randomBytes } from 'node:crypto'
import { appendFileSync, mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { ALG, ANDER_ALG, maakSleutel, clientAssertion, rendeerRealm, metTestOverlay, thumbprint } from '../lib.mjs'

const KEYCLOAK_IMAGE = process.env.KEYCLOAK_IMAGE ?? 'quay.io/keycloak/keycloak:26.8.2'
const KCC_IMAGE = process.env.KCC_IMAGE ?? 'adorsys/keycloak-config-cli:6.5.1-26.5.5'
const PROJECT = 'kcc-pkj-501'
const NETWORK = `${PROJECT}-net`
const PORT = process.env.KC_PORT ?? '18501'
const BASIS = `http://localhost:${PORT}`
const REALM = 'tenant-00000001823288444000'
const CLIENT = 'nl-portal-backend'
const ISSUER = `${BASIS}/realms/${REALM}`
const TOKEN_ENDPOINT = `${ISSUER}/protocol/openid-connect/token`
const AUTH_ENDPOINT = `${ISSUER}/protocol/openid-connect/auth`
const REDIRECT = 'https://portaal.gemeente-a.example/login/oauth2/code/keycloak'
const GEBRUIKER = { naam: 'burger', wachtwoord: randomBytes(12).toString('base64url') }
const werkmap = mkdtempSync(join(tmpdir(), 'kcc-pkj-'))

const docker = (...args) => execFileSync('docker', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })

function importeer(sleutels, { redirectUris = [REDIRECT], alg = ALG } = {}) {
  const sjabloon = rendeerRealm({ realm: REALM, clientId: CLIENT, redirectUris, jwks: { keys: sleutels.map((s) => s.publiek) }, alg })
  writeFileSync(join(werkmap, 'realm.json'), JSON.stringify(metTestOverlay(sjabloon, GEBRUIKER), null, 2))
  const uitvoer = docker('run', '--rm', '--network', NETWORK,
    '-e', 'KEYCLOAK_URL=http://keycloak:8080', '-e', 'KEYCLOAK_USER=admin', '-e', 'KEYCLOAK_PASSWORD=admin',
    '-e', 'KEYCLOAK_AVAILABILITYCHECK_ENABLED=true', '-e', 'KEYCLOAK_AVAILABILITYCHECK_TIMEOUT=120s',
    '-e', 'IMPORT_FILES_LOCATIONS=/config/realm.json', '-e', 'IMPORT_CACHE_ENABLED=false',
    '-e', 'IMPORT_VAR_SUBSTITUTION_ENABLED=true', '-e', 'IMPORT_VAR_SUBSTITUTION_UNDEFINED_THROWS_EXCEPTIONS=true',
    '-v', `${werkmap}:/config:ro`, KCC_IMAGE)
  if (process.env.KCC_LOG) appendFileSync(process.env.KCC_LOG, uitvoer)
  return uitvoer
}

function formulier(url, params, koppen = {}) {
  return fetch(url, { method: 'POST', redirect: 'manual', headers: { 'content-type': 'application/x-www-form-urlencoded', ...koppen }, body: new URLSearchParams(params) })
}
async function tokenAanvraag(params, endpoint = TOKEN_ENDPOINT) {
  const res = await formulier(endpoint, params)
  return { status: res.status, body: await res.json() }
}

/** Echte code-flow met PKCE S256 en een testgebruiker; geeft de autorisatiecode en de code_verifier. */
async function haalCode() {
  const verifier = randomBytes(32).toString('base64url')
  const challenge = createHash('sha256').update(verifier).digest('base64url')
  const start = await fetch(`${AUTH_ENDPOINT}?${new URLSearchParams({ response_type: 'code', client_id: CLIENT, scope: 'openid', state: 'x', redirect_uri: REDIRECT, code_challenge: challenge, code_challenge_method: 'S256' })}`, { redirect: 'manual' })
  assert.equal(start.status, 200, 'inlogscherm verwacht')
  const cookies = start.headers.getSetCookie().map((c) => c.split(';')[0]).join('; ')
  const html = await start.text()
  const actie = html.match(/<form[^>]*kc-form-login[^>]*action="([^"]+)"/)?.[1]?.replaceAll('&amp;', '&')
  assert.ok(actie, 'login-formulier niet gevonden')
  const login = await formulier(actie, { username: GEBRUIKER.naam, password: GEBRUIKER.wachtwoord, credentialId: '' }, { cookie: cookies })
  const code = new URL(login.headers.get('location') ?? 'http://geen').searchParams.get('code')
  assert.ok(code, `geen code na login (status ${login.status})`)
  return { code, verifier }
}

const ASSERTIE_TYPE = 'urn:ietf:params:oauth:client-assertion-type:jwt-bearer'
/** Wisselt een verse autorisatiecode in met de gegeven assertion (of zonder, met extra parameters). */
async function wissel(assertion, extra = {}) {
  const { code, verifier } = await haalCode()
  return tokenAanvraag({
    grant_type: 'authorization_code', client_id: CLIENT, code, redirect_uri: REDIRECT, code_verifier: verifier,
    ...(assertion ? { client_assertion_type: ASSERTIE_TYPE, client_assertion: assertion } : {}), ...extra,
  })
}
const assertie = (sleutel, opties = {}) => clientAssertion(sleutel, { clientId: CLIENT, audience: ISSUER, ...opties })
const metSleutel = (sleutel, opties) => wissel(assertie(sleutel, opties))

const geweigerd = (r) => {
  assert.ok(r.status === 400 || r.status === 401, JSON.stringify(r))
  assert.match(r.body.error, /^(unauthorized_client|invalid_client)$/, JSON.stringify(r))
}
const decodeer = (jwt) => jwt.split('.').slice(0, 2).map((d) => JSON.parse(Buffer.from(d, 'base64url').toString()))

before(async () => {
  try { docker('network', 'rm', NETWORK) } catch { /* bestond niet */ }
  docker('network', 'create', NETWORK)
  docker('run', '-d', '--name', `${PROJECT}-keycloak`, '--network', NETWORK, '--network-alias', 'keycloak', '-p', `${PORT}:8080`,
    '-e', 'KC_BOOTSTRAP_ADMIN_USERNAME=admin', '-e', 'KC_BOOTSTRAP_ADMIN_PASSWORD=admin', KEYCLOAK_IMAGE, 'start-dev')
  for (let i = 0; i < 90; i++) {
    try { if ((await fetch(`${BASIS}/realms/master`)).ok) return } catch { /* nog niet klaar */ }
    await new Promise((r) => setTimeout(r, 2000))
  }
  throw new Error('Keycloak niet gestart')
}, { timeout: 240_000 })

after(() => {
  try { docker('rm', '-f', `${PROJECT}-keycloak`) } catch { /* al weg */ }
  try { docker('network', 'rm', NETWORK) } catch { /* al weg */ }
})

test('kcc laadt het realm-template op Keycloak 26.8 zonder waarschuwingen en de code-flow slaagt met private_key_jwt', { timeout: 240_000 }, async () => {
  const sleutel = maakSleutel()
  const uitvoer = importeer([sleutel])
  assert.match(uitvoer, /Using version: 26\.8\./)
  assert.doesNotMatch(uitvoer, /\s(WARN|ERROR)\s+\d+ ---/)

  const { status, body } = await metSleutel(sleutel)
  assert.equal(status, 200, JSON.stringify(body))
  assert.ok(body.id_token, 'ID-token verwacht bij scope openid')

  const [kop, claims] = decodeer(body.access_token)
  assert.equal(kop.typ, 'at+jwt')
  // Het access token wordt met de realm-sleutel getekend (Keycloak-standaard RS256), los van de clientauthenticatie.
  assert.equal(kop.alg, 'RS256')
  assert.ok(kop.kid)
  assert.equal(claims.azp, CLIENT)
  assert.ok(claims.jti)
  assert.equal(claims.iss, ISSUER)
})

test('aud = issuer (RFC 10070) en aud = token-endpoint (NL GOV letterlijk) worden beide geaccepteerd; typ client-authentication+jwt ook', { timeout: 240_000 }, async () => {
  const sleutel = maakSleutel()
  importeer([sleutel])
  assert.equal((await metSleutel(sleutel, { audience: ISSUER })).status, 200)
  assert.equal((await metSleutel(sleutel, { audience: TOKEN_ENDPOINT })).status, 200)
  assert.equal((await metSleutel(sleutel, { typ: 'client-authentication+jwt' })).status, 200)
})

test('Keycloak weigert elke assertion die niet klopt: secret, vreemde of gemanipuleerde sleutel, alg none, verkeerde kid, aud, exp, iss of sub', { timeout: 480_000 }, async () => {
  const a = maakSleutel()
  const b = maakSleutel()
  importeer([a, b])
  const vreemd = maakSleutel()

  geweigerd(await wissel(null, { client_secret: 'geheim' }))
  geweigerd(await metSleutel(vreemd))
  geweigerd(await metSleutel(a, { handtekening: 'gemanipuleerd' }))
  geweigerd(await metSleutel(a, { handtekening: 'none' }))
  geweigerd(await metSleutel(a, { kid: b.kid }))
  geweigerd(await metSleutel(a, { audience: 'https://andere-as.example/realms/x' }))
  geweigerd(await metSleutel(a, { iatOffset: -300, expOffset: -120 }))
  // Zonder iat weigert Keycloak een exp ver in de toekomst (max.exp); mét iat klampt hij de levensduur, dat is geen weigering.
  geweigerd(await metSleutel(a, { lifetimeSeconds: 3600, zonderIat: true }))
  geweigerd(await metSleutel(a, { iss: 'andere-client' }))
  geweigerd(await metSleutel(a, { sub: 'andere-client' }))
  // Controle dat de weigeringen aan de assertion liggen en niet aan de opzet: dezelfde sleutel zonder fout slaagt.
  assert.equal((await metSleutel(a)).status, 200)
  assert.equal((await metSleutel(a, { lifetimeSeconds: 3600 })).status, 200)
})

test('replay: dezelfde assertion een tweede keer wordt geweigerd', { timeout: 240_000 }, async () => {
  const sleutel = maakSleutel()
  importeer([sleutel])
  const eenmalig = assertie(sleutel)
  assert.equal((await wissel(eenmalig)).status, 200)
  geweigerd(await wissel(eenmalig))
})

test('alg-pinning: Keycloak accepteert alleen het geconfigureerde algoritme, in beide richtingen', { timeout: 480_000 }, async () => {
  const sleutel = maakSleutel(ALG)
  importeer([sleutel], { alg: ALG })
  geweigerd(await metSleutel(sleutel, { alg: ANDER_ALG }))
  assert.equal((await metSleutel(sleutel, { alg: ALG })).status, 200)

  const omgekeerd = maakSleutel(ANDER_ALG)
  importeer([omgekeerd], { alg: ANDER_ALG })
  geweigerd(await metSleutel(omgekeerd, { alg: ALG }))
  assert.equal((await metSleutel(omgekeerd, { alg: ANDER_ALG })).status, 200)
})

test('rotatie: met twee sleutels in het realm werken beide, na verwijderen van de oude alleen de nieuwe', { timeout: 240_000 }, async () => {
  const a = maakSleutel()
  const b = maakSleutel()
  importeer([a, b])
  assert.equal((await metSleutel(a)).status, 200)
  assert.equal((await metSleutel(b)).status, 200)

  importeer([b])
  geweigerd(await metSleutel(a))
  assert.equal((await metSleutel(b)).status, 200)
})

test('de client mag geen client_credentials: de grant wordt geweigerd, ook met een geldige assertion', { timeout: 240_000 }, async () => {
  const sleutel = maakSleutel()
  importeer([sleutel])
  const r = await tokenAanvraag({ grant_type: 'client_credentials', client_id: CLIENT, client_assertion_type: ASSERTIE_TYPE, client_assertion: assertie(sleutel) })
  assert.equal(r.status, 401, JSON.stringify(r))
  assert.equal(r.body.error, 'unauthorized_client')
})

test('clientconfiguratie en template: TLS vereist, PKCE S256, exacte redirect-URI, client-jwt, alg-pin, max.exp, geen service-account', { timeout: 240_000 }, async () => {
  const sleutel = maakSleutel()
  const sjabloon = rendeerRealm({ realm: REALM, clientId: CLIENT, redirectUris: [REDIRECT], jwks: { keys: [sleutel.publiek] } })
  assert.equal(sjabloon.sslRequired, 'all')
  assert.equal(thumbprint(sleutel.publiek), sleutel.kid)

  importeer([sleutel])
  const admin = await tokenAanvraag({ grant_type: 'password', client_id: 'admin-cli', username: 'admin', password: 'admin' }, `${BASIS}/realms/master/protocol/openid-connect/token`)
  const res = await fetch(`${BASIS}/admin/realms/${REALM}/clients?clientId=${CLIENT}`, { headers: { authorization: `Bearer ${admin.body.access_token}` } })
  const [client] = await res.json()
  assert.equal(client.clientAuthenticatorType, 'client-jwt')
  assert.equal(client.publicClient, false)
  assert.deepEqual(client.redirectUris, [REDIRECT])
  assert.equal(client.attributes['pkce.code.challenge.method'], 'S256')
  assert.equal(client.attributes['token.endpoint.auth.signing.alg'], ALG)
  assert.equal(client.attributes['token.endpoint.auth.signing.max.exp'], '60')
  assert.equal(client.attributes['access.token.header.type.rfc9068'], 'true')
  assert.equal(client.implicitFlowEnabled, false)
  assert.equal(client.directAccessGrantsEnabled, false)
  assert.equal(client.serviceAccountsEnabled, false)
})

test('autorisatieverzoek: zonder PKCE S256 of met een afwijkende redirect-URI weigert Keycloak', { timeout: 240_000 }, async () => {
  importeer([maakSleutel()])
  const auth = (extra) => fetch(`${AUTH_ENDPOINT}?${new URLSearchParams({ response_type: 'code', client_id: CLIENT, scope: 'openid', state: 'x', ...extra })}`, { redirect: 'manual' })
  const challenge = 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM'

  const zonderPkce = await auth({ redirect_uri: REDIRECT })
  assert.match(zonderPkce.headers.get('location') ?? '', /error=invalid_request/)

  const metPkce = await auth({ redirect_uri: REDIRECT, code_challenge: challenge, code_challenge_method: 'S256' })
  assert.equal(metPkce.status, 200)

  const plain = await auth({ redirect_uri: REDIRECT, code_challenge: 'abc', code_challenge_method: 'plain' })
  assert.match(plain.headers.get('location') ?? '', /error=invalid_request/)

  const prefix = await auth({ redirect_uri: `${REDIRECT}/extra`, code_challenge: challenge, code_challenge_method: 'S256' })
  assert.equal(prefix.status, 400)
})
