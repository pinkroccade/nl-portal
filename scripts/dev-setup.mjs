#!/usr/bin/env node
// Genereert de lokale dev-omgeving met willekeurige secrets (mise run dev:setup).
// Rendert dezelfde waarden uit templates in de compose-omgeving (docker-compose/.env), de seed-gegevens van de
// ZGW-componenten en de lokale omgeving van :app (backend/app/.env.properties). De Keycloak-realms genereert het niet
// meer zelf: per tenantbestand (docker-compose/imports/tenants) komen er de per-tenant-waarden en een sleutelpaar voor
// `private_key_jwt`, en het ene realm-template (keycloak/realm-template) wordt daarmee tot een realm dat
// keycloak-config-cli laadt (service nl-portal-keycloak-config in de compose). Alles wat dit script schrijft is gitignored.
// Opnieuw uitvoeren geeft nieuwe secrets en sleutels; draai daarna `docker compose down -v` en `up`, zodat de
// services opnieuw starten met de nieuwe realms en fixtures en met lege databases.
// Usage: node scripts/dev-setup.mjs [--root <repo-root>]

import { pbkdf2Sync, randomBytes } from "node:crypto";
import { chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { maakClientSleutel } from "./lib/clientsleutel.mjs";
import { genereerRealm } from "./lib/realm.mjs";
import { leesTenantbestanden } from "./lib/tenantbestand.mjs";

export const SECRET_NAMES = [
  "KEYCLOAK_ADMIN_PASSWORD",
  // Redis voor de sessies van de BFF: ACL-gebruiker `nlportal` (de default-gebruiker staat uit).
  "REDIS_PASSWORD",
  "KEYCLOAK_DB_PASSWORD",
  // Open Zaak: JWT-secrets van de ZGW-clients en de koppeling met Open Notificaties.
  "OPENZAAK_NLPORTAL_SECRET",
  "OPENZAAK_VALTIMO_CLIENT_SECRET",
  "OPENZAAK_OPENNOTIFICATIES_SECRET",
  "OPENZAAK_OPENFORMULIEREN_SECRET",
  "NOTIFICATIES_OBJECTS_SECRET",
  "NOTIFICATIES_OPENZAAK_SECRET",
  // API-tokens.
  "OBJECTS_API_TOKEN",
  "OPENKLANT_API_TOKEN",
  "OPENPRODUCT_API_TOKEN",
  // Databasewachtwoorden, Django SECRET_KEYs, beheerderswachtwoorden en de RabbitMQ-broker.
  "OPENZAAK_DB_PASSWORD",
  "OPENKLANT_DB_PASSWORD",
  "OBJECTS_DB_PASSWORD",
  "NOTIFICATIES_DB_PASSWORD",
  "OPENPRODUCT_DB_PASSWORD",
  "OPENZAAK_SECRET_KEY",
  "OPENKLANT_SECRET_KEY",
  "OBJECTS_SECRET_KEY",
  "NOTIFICATIES_SECRET_KEY",
  "OPENPRODUCT_SECRET_KEY",
  "OPENZAAK_ADMIN_PASSWORD",
  "OPENKLANT_ADMIN_PASSWORD",
  "OBJECTS_ADMIN_PASSWORD",
  "NOTIFICATIES_ADMIN_PASSWORD",
  "OPENPRODUCT_ADMIN_PASSWORD",
  "NOTIFICATIES_RABBITMQ_PASSWORD",
  "BACKEND_BEHEER_PASSWORD",
];

// Template -> uitvoer. Alle uitvoerpaden moeten gitignored zijn; de rooktest (dev-setup.test.mjs) bewaakt dat.
export const TEMPLATES = [
  { template: "docker-compose/templates/openzaak/configuration.json", output: "docker-compose/.generated/openzaak/configuration.json" },
  { template: "docker-compose/templates/open-object/configuration.json", output: "docker-compose/.generated/open-object/configuration.json" },
  { template: "docker-compose/templates/open-notificaties/configuration.json", output: "docker-compose/.generated/open-notificaties/configuration.json" },
  { template: "docker-compose/templates/openklant-2/configuration.json", output: "docker-compose/.generated/openklant-2/configuration.json" },
  { template: "docker-compose/templates/openklant-2/admin_user.json", output: "docker-compose/.generated/openklant-2/admin_user.json" },
  { template: "docker-compose/templates/openproduct/configuration.json", output: "docker-compose/.generated/openproduct/configuration.json" },
  { template: "docker-compose/templates/redis/users.acl", output: "docker-compose/.generated/redis/users.acl" },
  { template: "backend/app/.env.properties.example", output: "backend/app/.env.properties" },
];

// :app-dev leest zijn secrets uit een eigen, gitignored bestand: het Redis-wachtwoord van de BFF-sessies en de secrets en tokens
// waarmee het de ZGW-componenten aanroept.
export const APP_DEV_SECRETS = ["REDIS_PASSWORD", "OPENZAAK_NLPORTAL_SECRET", "OBJECTS_API_TOKEN", "OPENKLANT_API_TOKEN", "OPENPRODUCT_API_TOKEN"];
export const APP_DEV_OUTPUT = "backend/app-dev/.env.properties";
// Tenants: waar de tenantbestanden staan en waar het realm en de clientsleutel per tenant terechtkomen (gitignored).
export const TENANTS_MAP = "docker-compose/imports/tenants";
export const REALM_UITVOER = (oin) => `docker-compose/.generated/keycloak/${oin}.json`;
export const CLIENTSLEUTEL_UITVOER = (oin) => `docker-compose/.generated/tenants/${oin}/clientsleutel.jwk.json`;
// Tot de tweede tenant gebruiken de bestaande configuratie en de perf-seed de realmnaam `nlportal`; met één tenant is
// dat geen conflict. Een tweede tenant (stap 8) krijgt zijn OIN als realmnaam (ADR 0021 besluit 3).
export const DEV_REALM = "nlportal";
export const DEV_BROKER_URL = "http://localhost:8082";
export const COMPOSE_ENV = "docker-compose/.env";
export const COMPOSE_ENV_EXAMPLE = "docker-compose/.env.example";

const BLOCK_START = "# --- dev:setup: gegenereerd, niet handmatig wijzigen ---";
const BLOCK_END = "# --- einde dev:setup ---";

export function generateSecrets(bytes = randomBytes) {
  return Object.fromEntries(SECRET_NAMES.map((name) => [name, bytes(24).toString("base64url")]));
}

// Django slaat wachtwoorden op als pbkdf2_sha256$<iteraties>$<salt>$<base64-hash>; Open Klant heeft geen
// superuser-hook, dus de beheerder komt als gerenderde fixture met deze hash.
export function djangoPasswordHash(password, salt = randomBytes(12).toString("hex"), iterations = 1_000_000) {
  const hash = pbkdf2Sync(password, salt, iterations, 32, "sha256").toString("base64");
  return `pbkdf2_sha256$${iterations}$${salt}$${hash}`;
}

export function render(template, values, source = "template") {
  return template.replace(/\{\{([A-Z0-9_]+)\}\}/g, (_, name) => {
    if (!(name in values)) throw new Error(`${source}: onbekende placeholder {{${name}}}`);
    return values[name];
  });
}

/**
 * De private clientsleutel is 0600 van de gebruiker die dev:setup draait. De backend-container leest hem alleen als dezelfde uid
 * (compose: `user: ${LOCAL_UID}:${LOCAL_GID}`), dus het blok legt die uid vast. Windows kent geen uid; compose valt dan terug op 1000.
 */
export function withManagedBlock(existing, values, gebruiker = {}) {
  const gebruikerRegels = gebruiker.uid === undefined ? [] : [`LOCAL_UID=${gebruiker.uid}`, `LOCAL_GID=${gebruiker.gid}`];
  const block = [BLOCK_START, ...SECRET_NAMES.map((name) => `${name}=${values[name]}`), ...gebruikerRegels, BLOCK_END].join("\n");
  const pattern = new RegExp(`${BLOCK_START}[\\s\\S]*?${BLOCK_END}`);
  if (pattern.test(existing)) return existing.replace(pattern, () => block);
  return `${existing.replace(/\s*$/, "")}\n\n${block}\n`;
}

function write(root, path, content, mode) {
  const target = join(root, path);
  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, content);
  // writeFileSync past de modus van een bestaand bestand niet aan; chmod ook dan.
  if (mode !== undefined) chmodSync(target, mode);
}

/** Genereert per tenant het realm en het sleutelpaar voor `private_key_jwt`; geeft de geschreven paden terug. */
export function genereerTenantRealms(root, sleutelMaker = maakClientSleutel) {
  const tenants = leesTenantbestanden(join(root, TENANTS_MAP));
  if (tenants.length > 1) throw new Error("Met meer dan een tenant moet elk realm zijn OIN als naam krijgen; dat bouwt de tweede-tenantstap (#499 stap 8)");
  return tenants.flatMap((tenant) => {
    const sleutel = sleutelMaker();
    const realm = genereerRealm({ tenant, realm: DEV_REALM, brokerUrl: DEV_BROKER_URL, clientSleutels: [sleutel.publiek] });
    write(root, REALM_UITVOER(tenant.oin), `${JSON.stringify(realm, null, 2)}\n`);
    write(root, CLIENTSLEUTEL_UITVOER(tenant.oin), `${JSON.stringify(sleutel.prive, null, 2)}\n`, 0o600);
    return [REALM_UITVOER(tenant.oin), CLIENTSLEUTEL_UITVOER(tenant.oin)];
  });
}

/**
 * Uid 0 (dev:setup onder sudo) schrijft geen LOCAL_UID: de backend-container zou als root draaien. Compose valt dan terug op 1000
 * en de 0600-sleutel is voor root, dus de run faalt zichtbaar in plaats van stil met te veel rechten.
 */
export const gebruikerVoor = (uid, gid) => {
  if (uid === 0) throw new Error("dev:setup draaien als root (sudo) is niet toegestaan: de backend-container zou als root draaien; draai het als gewone gebruiker");
  return uid === undefined ? {} : { uid, gid };
};

const huidigeGebruiker = () => (process.getuid ? gebruikerVoor(process.getuid(), process.getgid()) : {});

export function devSetup(root, secrets = generateSecrets()) {
  const written = [];
  const values = { ...secrets, OPENKLANT_ADMIN_PASSWORD_HASH: djangoPasswordHash(secrets.OPENKLANT_ADMIN_PASSWORD) };
  for (const { template, output } of TEMPLATES) {
    write(root, output, render(readFileSync(join(root, template), "utf8"), values, template));
    written.push(output);
  }

  written.push(...genereerTenantRealms(root));

  // Eigen regels in een bestaande docker-compose/.env (RUN_MODE, NETWERK, ...) blijven staan.
  const base = existsSync(join(root, COMPOSE_ENV)) ? COMPOSE_ENV : COMPOSE_ENV_EXAMPLE;
  write(root, COMPOSE_ENV, withManagedBlock(readFileSync(join(root, base), "utf8"), secrets, huidigeGebruiker()));
  written.push(COMPOSE_ENV);

  write(
    root,
    APP_DEV_OUTPUT,
    [
      "# Gegenereerd door mise run dev:setup (gitignored).",
      ...APP_DEV_SECRETS.map((name) => `${name}=${secrets[name]}`),
      // bootRun op de host praat met de TLS-loze Redis van de compose; buiten lokaal staat REDIS_SSL standaard aan.
      "REDIS_SSL=false",
      "",
    ].join("\n"),
  );
  written.push(APP_DEV_OUTPUT);
  return written;
}

function main() {
  const rootArg = process.argv.indexOf("--root");
  const root = resolve(rootArg > -1 ? process.argv[rootArg + 1] : join(dirname(fileURLToPath(import.meta.url)), ".."));
  const written = devSetup(root);
  console.log("Dev-omgeving gegenereerd met nieuwe willekeurige secrets (alles gitignored):");
  for (const path of written) console.log(`  ${path}`);
  console.log("\nDe beheerderswachtwoorden (Keycloak, Open Zaak, Open Klant, ...) staan in docker-compose/.env (*_ADMIN_PASSWORD).");
  console.log("Draaide de stack al? Start hem opnieuw met lege databases:");
  console.log("  cd docker-compose && docker compose down -v && docker compose up -d");
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
