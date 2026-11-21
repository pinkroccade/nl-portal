// Hulpcode voor de test: Keycloak bereiken, de realms afmaken en een login als een browser doorlopen.
import { createHash, randomBytes } from "node:crypto";

export const KC = "http://localhost:18080";
export const REDIRECT_URI = "http://localhost:18081/bff/callback";

export async function waitForKeycloak(timeoutMs = 180_000) {
  const start = Date.now();
  for (;;) {
    try {
      const r = await fetch(`${KC}/realms/portal/.well-known/openid-configuration`);
      const r2 = await fetch(`${KC}/realms/idp-sim/.well-known/openid-configuration`);
      if (r.ok && r2.ok) return;
    } catch {
      // nog niet gestart
    }
    if (Date.now() - start > timeoutMs) throw new Error("Keycloak niet bereikbaar");
    await new Promise((res) => setTimeout(res, 2000));
  }
}

async function adminToken() {
  const r = await fetch(`${KC}/realms/master/protocol/openid-connect/token`, {
    method: "POST",
    body: new URLSearchParams({ grant_type: "password", client_id: "admin-cli", username: "admin", password: "admin" }),
  });
  return (await r.json()).access_token;
}

export async function admin(path, init = {}) {
  const token = await adminToken();
  const r = await fetch(`${KC}/admin/realms${path}`, {
    ...init,
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json", ...(init.headers ?? {}) },
  });
  if (!r.ok && r.status !== 404) throw new Error(`${init.method ?? "GET"} ${path}: ${r.status} ${await r.text()}`);
  return r;
}

/** Zet het ondertekeningscertificaat van de simulator in de DigiD-koppeling (de sleutel wordt per start gegenereerd). */
export async function provisionDigidSigningCert() {
  const keys = await (await admin("/idp-sim/keys")).json();
  const cert = keys.keys.find((k) => k.type === "RSA" && k.status === "ACTIVE" && k.use === "SIG").certificate;
  const idp = await (await admin("/portal/identity-provider/instances/digid")).json();
  idp.config.signingCertificate = cert;
  await admin("/portal/identity-provider/instances/digid", { method: "PUT", body: JSON.stringify(idp) });
}

export class Jar {
  cookies = new Map();

  store(res) {
    for (const line of res.headers.getSetCookie()) {
      const [pair, ...attrs] = line.split(";").map((s) => s.trim());
      const eq = pair.indexOf("=");
      const name = pair.slice(0, eq);
      const value = pair.slice(eq + 1);
      const path = attrs.find((a) => a.toLowerCase().startsWith("path="))?.slice(5) ?? "/";
      const key = `${name}|${path}`;
      if (value === "" || /max-age=0/i.test(line)) this.cookies.delete(key);
      else this.cookies.set(key, { name, value, path });
    }
  }

  header(url) {
    const p = new URL(url).pathname;
    return [...this.cookies.values()]
      .filter((c) => p.startsWith(c.path))
      .map((c) => `${c.name}=${c.value}`)
      .join("; ");
  }
}

const decodeHtml = (s) =>
  s.replace(/&amp;/g, "&").replace(/&quot;/g, '"').replace(/&#x3d;/gi, "=").replace(/&#61;/g, "=").replace(/&lt;/g, "<").replace(/&gt;/g, ">");

const textOf = (html) =>
  html
    .replace(/<script[\s\S]*?<\/script>/g, "")
    .replace(/<[^>]+>/g, " ")
    .replace(/\s+/g, " ");

function parseForm(html) {
  const form = html.match(/<form[^>]*action="([^"]*)"[^>]*>([\s\S]*?)<\/form>/i);
  if (!form) return null;
  const fields = {};
  for (const m of form[2].matchAll(/<input[^>]*>/gi)) {
    const name = m[0].match(/name="([^"]*)"/i)?.[1];
    if (name) fields[name] = decodeHtml(m[0].match(/value="([^"]*)"/i)?.[1] ?? "");
  }
  return { action: decodeHtml(form[1]), fields };
}

/**
 * Doorloopt een authorization-code-login zoals de BFF en een browser dat zouden doen, bij `nl-portal-backend` in
 * realm `portal`, tot de redirect naar de BFF. Geeft de code of het foutantwoord terug.
 */
export async function login({ idp, username, password, acrValues, prompt, jar = new Jar() }) {
  const verifier = randomBytes(32).toString("base64url");
  const challenge = createHash("sha256").update(verifier).digest("base64url");
  const state = randomBytes(8).toString("hex");
  const params = new URLSearchParams({
    client_id: "nl-portal-backend",
    redirect_uri: REDIRECT_URI,
    response_type: "code",
    scope: "openid",
    state,
    nonce: randomBytes(8).toString("hex"),
    code_challenge: challenge,
    code_challenge_method: "S256",
    ...(idp ? { kc_idp_hint: idp } : {}),
  });
  if (acrValues) params.set("acr_values", acrValues);
  if (prompt) params.set("prompt", prompt);

  let url = `${KC}/realms/portal/protocol/openid-connect/auth?${params}`;
  let init = { method: "GET" };
  const trace = [];
  const urls = [];
  const pages = [];
  for (let hop = 0; hop < 25; hop++) {
    const res = await fetch(url, { ...init, redirect: "manual", headers: { cookie: jar.header(url), ...(init.headers ?? {}) } });
    jar.store(res);
    trace.push(`${init.method} ${url.split("?")[0]} -> ${res.status}`);
    urls.push(url);
    if (res.status >= 300 && res.status < 400) {
      url = new URL(res.headers.get("location"), url).toString();
      if (url.startsWith(REDIRECT_URI)) {
        const q = new URL(url).searchParams;
        return { code: q.get("code"), error: q.get("error"), errorDescription: q.get("error_description"), verifier, trace, pages, urls };
      }
      init = { method: "GET" };
      continue;
    }
    const html = await res.text();
    pages.push(html);
    const form = parseForm(html);
    // Het eigen wachtwoordformulier van de portal-realm: de koppeling is mislukt en Keycloak valt terug op de browserflow.
    if (form && url.includes("/realms/portal/login-actions/authenticate")) {
      return { code: null, error: null, wachtwoordformulier: true, pageText: textOf(html).trim(), trace, pages, urls };
    }
    if (!form) return { code: null, error: null, pageText: textOf(html).trim(), trace, pages, urls };
    const target = new URL(form.action, url).toString();
    if (form.fields.username !== undefined) form.fields.username = username;
    if (form.fields.password !== undefined) form.fields.password = password;
    url = target;
    init = {
      method: "POST",
      body: new URLSearchParams(form.fields),
      headers: { "content-type": "application/x-www-form-urlencoded" },
    };
  }
  if (process.env.DUMP) console.log(textOf(pages.at(-1)).slice(0, 600));
  throw new Error(`Te veel doorverwijzingen: ${trace.join(" | ")}`);
}

export async function exchange({ code, verifier }) {
  const r = await fetch(`${KC}/realms/portal/protocol/openid-connect/token`, {
    method: "POST",
    body: new URLSearchParams({
      grant_type: "authorization_code",
      code,
      redirect_uri: REDIRECT_URI,
      client_id: "nl-portal-backend",
      client_secret: "backend-secret",
      code_verifier: verifier,
    }),
  });
  const body = await r.json();
  if (!r.ok) throw new Error(`Tokenuitwisseling mislukt: ${JSON.stringify(body)}`);
  return body;
}

export function decodeJwt(jwt) {
  return JSON.parse(Buffer.from(jwt.split(".")[1], "base64url").toString("utf8"));
}

export async function loginForIdToken(opts) {
  const result = await login(opts);
  if (!result.code) return { ...result, idToken: null };
  const tokens = await exchange(result);
  return { ...result, tokens, idToken: decodeJwt(tokens.id_token), accessToken: decodeJwt(tokens.access_token) };
}

const SAML_CLASSES = "urn:oasis:names:tc:SAML:2.0:ac:classes:";
export const DIGID = {
  basis: `${SAML_CLASSES}PasswordProtectedTransport`,
  midden: `${SAML_CLASSES}MobileTwoFactorContract`,
  substantieel: `${SAML_CLASSES}Smartcard`,
  hoog: `${SAML_CLASSES}SmartcardPKI`,
};

/** Wat de koppeling bij DigiD opvraagt (de RequestedAuthnContext), per tenant in de IdP-instelling. */
export async function setDigidRequestedLevel(classRef, comparison = "minimum") {
  const idp = await (await admin("/portal/identity-provider/instances/digid")).json();
  idp.config.authnContextClassRefs = JSON.stringify([classRef]);
  idp.config.authnContextComparisonType = comparison;
  await admin("/portal/identity-provider/instances/digid", { method: "PUT", body: JSON.stringify(idp) });
}

/** De AuthnRequest die Keycloak naar de DigiD-simulator stuurde, uit het formulier van de eerste pagina. */
export function samlAuthnRequest(result) {
  for (const page of result.pages) {
    const m = page.match(/name="SAMLRequest" value="([^"]*)"/);
    if (m) return Buffer.from(m[1], "base64").toString("utf8");
  }
  return null;
}

export async function setSimUserAttribute(username, attribute, values) {
  const users = await (await admin(`/idp-sim/users?username=${username}&exact=true`)).json();
  const user = users[0];
  const attributes = { ...user.attributes };
  if (values === null) delete attributes[attribute];
  else attributes[attribute] = values;
  await admin(`/idp-sim/users/${user.id}`, { method: "PUT", body: JSON.stringify({ ...user, attributes }) });
}

export async function clearDigidRequestedLevel() {
  const idp = await (await admin("/portal/identity-provider/instances/digid")).json();
  delete idp.config.authnContextClassRefs;
  delete idp.config.authnContextComparisonType;
  await admin("/portal/identity-provider/instances/digid", { method: "PUT", body: JSON.stringify(idp) });
}

export async function addPortalIdpMapper(mapper) {
  const r = await admin(`/portal/identity-provider/instances/${mapper.identityProviderAlias}/mappers`, { method: "POST", body: JSON.stringify(mapper) });
  return r.headers.get("location").split("/").pop();
}

export async function deletePortalIdpMapper(alias, id) {
  await admin(`/portal/identity-provider/instances/${alias}/mappers/${id}`, { method: "DELETE" });
}

export async function portalUsersWithAttribute(attribute, value) {
  return (await admin(`/portal/users?q=${attribute}:${value}`)).json();
}

export async function portalUsers() {
  return (await admin("/portal/users?max=100")).json();
}

export async function deletePortalUsers() {
  for (const user of await portalUsers()) await admin(`/portal/users/${user.id}`, { method: "DELETE" });
}

export async function createPortalUser(user) {
  const r = await admin("/portal/users", { method: "POST", body: JSON.stringify({ enabled: true, ...user }) });
  return r.headers.get("location").split("/").pop();
}

export async function addPortalClientMapper(mapper) {
  const clients = await (await admin("/portal/clients?clientId=nl-portal-backend")).json();
  const r = await admin(`/portal/clients/${clients[0].id}/protocol-mappers/models`, { method: "POST", body: JSON.stringify(mapper) });
  return { clientUuid: clients[0].id, id: r.headers.get("location").split("/").pop() };
}

export async function deletePortalClientMapper({ clientUuid, id }) {
  await admin(`/portal/clients/${clientUuid}/protocol-mappers/models/${id}`, { method: "DELETE" });
}
