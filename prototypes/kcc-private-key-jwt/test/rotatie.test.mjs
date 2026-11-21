// Prototype #501: bewijst op een echte Keycloak 26.8 met keycloak-config-cli dat de client
// nl-portal-backend per tenant met private_key_jwt authenticeert, ook met twee sleutels tijdens rotatie.
// Seam: het token-endpoint van Keycloak (HTTP) na een kcc-import; geen mocks.
import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { appendFileSync, mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { ALG, maakSleutel, clientAssertion, rendeerRealm } from '../lib.mjs'

const KEYCLOAK_IMAGE = process.env.KEYCLOAK_IMAGE ?? 'quay.io/keycloak/keycloak:26.8.2'
const KCC_IMAGE = process.env.KCC_IMAGE ?? 'adorsys/keycloak-config-cli:6.5.1-26.5.5'
const PROJECT = 'kcc-pkj-501'
const NETWORK = `${PROJECT}-net`
const PORT = process.env.KC_PORT ?? '18501'
const BASIS = `http://localhost:${PORT}`
const REALM = 'tenant-00000001823288444000'
const CLIENT = 'nl-portal-backend'
const TOKEN_ENDPOINT = `${BASIS}/realms/${REALM}/protocol/openid-connect/token`
const HIER = dirname(fileURLToPath(import.meta.url))
const werkmap = mkdtempSync(join(tmpdir(), 'kcc-pkj-'))

const docker = (...args) => execFileSync('docker', args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })

function importeer(sleutels, redirectUris = ['https://portaal.gemeente-a.example/login/oauth2/code/keycloak']) {
  const realm = rendeerRealm({ realm: REALM, clientId: CLIENT, redirectUris, jwks: { keys: sleutels.map((s) => s.publiek) } })
  writeFileSync(join(werkmap, 'realm.json'), JSON.stringify(realm, null, 2))
  const uitvoer = docker('run', '--rm', '--network', NETWORK,
    '-e', 'KEYCLOAK_URL=http://keycloak:8080', '-e', 'KEYCLOAK_USER=admin', '-e', 'KEYCLOAK_PASSWORD=admin',
    '-e', 'KEYCLOAK_AVAILABILITYCHECK_ENABLED=true', '-e', 'KEYCLOAK_AVAILABILITYCHECK_TIMEOUT=120s',
    '-e', 'IMPORT_FILES_LOCATIONS=/config/realm.json', '-e', 'IMPORT_CACHE_ENABLED=false',
    '-e', 'IMPORT_VAR_SUBSTITUTION_ENABLED=true', '-e', 'IMPORT_VAR_SUBSTITUTION_UNDEFINED_THROWS_EXCEPTIONS=true',
    '-v', `${werkmap}:/config:ro`, KCC_IMAGE)
  if (process.env.KCC_LOG) appendFileSync(process.env.KCC_LOG, uitvoer)
  return uitvoer
}

async function tokenAanvraag(params, endpoint = TOKEN_ENDPOINT) {
  const res = await fetch(endpoint, { method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(params) })
  return { status: res.status, body: await res.json() }
}
const metAssertion = (sleutel) => tokenAanvraag({
  grant_type: 'client_credentials', client_id: CLIENT,
  client_assertion_type: 'urn:ietf:params:oauth:client-assertion-type:jwt-bearer',
  client_assertion: clientAssertion(sleutel, { clientId: CLIENT, audience: TOKEN_ENDPOINT }),
})
const decodeer = (jwt) => jwt.split('.').slice(0, 2).map((d) => JSON.parse(Buffer.from(d, 'base64url').toString()))

const sleutelA = maakSleutel('tenant1-2026-a')
const sleutelB = maakSleutel('tenant1-2026-b')
const onbekend = maakSleutel('tenant1-vreemd')

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

test('kcc laadt het realm-template op Keycloak 26.8 en de client authenticeert met private_key_jwt', { timeout: 240_000 }, async () => {
  const uitvoer = importeer([sleutelA])
  assert.match(uitvoer, /Keycloak\s+version|Importing file|import/i)

  const { status, body } = await metAssertion(sleutelA)
  assert.equal(status, 200, JSON.stringify(body))

  const [kop, claims] = decodeer(body.access_token)
  assert.equal(kop.typ, 'at+jwt')
  // Het access token wordt met de realm-sleutel getekend (Keycloak-standaard RS256), los van de clientauthenticatie (${ALG}).
  assert.equal(kop.alg, 'RS256')
  assert.ok(kop.kid)
  assert.equal(claims.azp, CLIENT)
  assert.ok(claims.jti && claims.iss.endsWith(`/realms/${REALM}`))
})

test('een client_secret, een vreemde sleutel of een assertion zonder geldige handtekening wordt geweigerd', async () => {
  const geweigerd = (r) => { assert.ok(r.status === 400 || r.status === 401, JSON.stringify(r)); assert.match(r.body.error, /unauthorized_client|invalid_client/) }
  geweigerd(await tokenAanvraag({ grant_type: 'client_credentials', client_id: CLIENT, client_secret: 'geheim' }))
  geweigerd(await metAssertion(onbekend))
})

test('rotatie: met twee sleutels in het realm werken beide, na verwijderen van de oude alleen de nieuwe', { timeout: 240_000 }, async () => {
  importeer([sleutelA, sleutelB])
  assert.equal((await metAssertion(sleutelA)).status, 200)
  assert.equal((await metAssertion(sleutelB)).status, 200)

  importeer([sleutelB])
  const oudeSleutel = await metAssertion(sleutelA)
  assert.ok(oudeSleutel.status >= 400, JSON.stringify(oudeSleutel))
  assert.equal((await metAssertion(sleutelB)).status, 200)
})

test('clientconfiguratie: PKCE S256, exacte redirect-URI, client-jwt, PS256/RS256, geen secret-authenticatie', async () => {
  const uri = ['https://portaal.gemeente-a.example/login/oauth2/code/keycloak']
  importeer([sleutelB], uri)
  const admin = await tokenAanvraag({ grant_type: 'password', client_id: 'admin-cli', username: 'admin', password: 'admin' }, `${BASIS}/realms/master/protocol/openid-connect/token`)
  const res = await fetch(`${BASIS}/admin/realms/${REALM}/clients?clientId=${CLIENT}`, { headers: { authorization: `Bearer ${admin.body.access_token}` } })
  const [client] = await res.json()
  assert.equal(client.clientAuthenticatorType, 'client-jwt')
  assert.equal(client.publicClient, false)
  assert.deepEqual(client.redirectUris, uri)
  assert.equal(client.attributes['pkce.code.challenge.method'], 'S256')
  assert.equal(client.attributes['token.endpoint.auth.signing.alg'], ALG)
  assert.equal(client.attributes['access.token.header.type.rfc9068'], 'true')
  assert.equal(client.implicitFlowEnabled, false)
})

test('autorisatieverzoek: zonder PKCE S256 of met een afwijkende redirect-URI weigert Keycloak', async () => {
  const uri = 'https://portaal.gemeente-a.example/login/oauth2/code/keycloak'
  const auth = (extra) => fetch(`${BASIS}/realms/${REALM}/protocol/openid-connect/auth?${new URLSearchParams({ response_type: 'code', client_id: CLIENT, scope: 'openid', state: 'x', ...extra })}`, { redirect: 'manual' })

  const zonderPkce = await auth({ redirect_uri: uri })
  assert.match(zonderPkce.headers.get('location') ?? '', /error=invalid_request/)

  const metPkce = await auth({ redirect_uri: uri, code_challenge: 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM', code_challenge_method: 'S256' })
  assert.equal(metPkce.status, 200)

  const plain = await auth({ redirect_uri: uri, code_challenge: 'abc', code_challenge_method: 'plain' })
  assert.match(plain.headers.get('location') ?? '', /error=invalid_request/)

  const prefix = await auth({ redirect_uri: `${uri}/extra`, code_challenge: 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM', code_challenge_method: 'S256' })
  assert.equal(prefix.status, 400)
})
