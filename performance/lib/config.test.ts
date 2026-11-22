import { test } from "node:test";
import assert from "node:assert/strict";
import { gebruikerVoorVu, loadConfig, loadUserPool } from "./config.ts";

const CREDENTIALS = { PERF_USERNAME: "gebruiker", PERF_PASSWORD: "wachtwoord" };

test("gebruikt de lokale compose-stack als er geen URL's zijn opgegeven", () => {
  const config = loadConfig(CREDENTIALS);

  assert.equal(config.graphqlUrl, "http://localhost:8080/graphql");
  assert.equal(config.tokenUrl, "http://localhost:8082/realms/nlportal/protocol/openid-connect/token");
  assert.equal(config.clientId, "nl-portal");
});

test("laat de URL's en de client overschrijven via de omgeving", () => {
  const config = loadConfig({
    ...CREDENTIALS,
    PERF_BACKEND_URL: "http://backend:8080/",
    PERF_KEYCLOAK_URL: "http://keycloak:8082/auth/",
    PERF_REALM: "ander",
    PERF_CLIENT_ID: "perf",
  });

  assert.equal(config.graphqlUrl, "http://backend:8080/graphql");
  assert.equal(config.tokenUrl, "http://keycloak:8082/auth/realms/ander/protocol/openid-connect/token");
  assert.equal(config.clientId, "perf");
});

test("faalt zonder gebruikersnaam of wachtwoord, en noemt de ontbrekende variabele", () => {
  assert.throws(() => loadConfig({ PERF_USERNAME: "gebruiker" }), /PERF_PASSWORD/);
  assert.throws(() => loadConfig({ PERF_PASSWORD: "wachtwoord" }), /PERF_USERNAME/);
});

test("de journey-gebruikerspool verdeelt VU's over burger-001.. en wisselt rond", () => {
  const pool = loadUserPool({ PERF_PASSWORD: "geheim", PERF_USERS: "3" });
  assert.deepEqual([1, 2, 3, 4].map((vu) => gebruikerVoorVu(pool, vu).username), ["burger-001", "burger-002", "burger-003", "burger-001"]);
  assert.equal(gebruikerVoorVu(pool, 1).password, "geheim");
});

test("de gebruikerspool vraagt het wachtwoord en een positief aantal", () => {
  assert.throws(() => loadUserPool({}), /PERF_PASSWORD/);
  assert.throws(() => loadUserPool({ PERF_PASSWORD: "x", PERF_USERS: "0" }), /PERF_USERS/);
});
