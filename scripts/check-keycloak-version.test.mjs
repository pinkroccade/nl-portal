import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { checkKeycloakVersion } from "./check-keycloak-version.mjs";

const DIGEST = "sha256:" + "a".repeat(64);
const DOCKERFILE = `ARG KEYCLOAK_VERSION=26.8.2
ARG KEYCLOAK_DIGEST=${DIGEST}
FROM quay.io/keycloak/keycloak:\${KEYCLOAK_VERSION}@\${KEYCLOAK_DIGEST} AS builder
FROM quay.io/keycloak/keycloak:\${KEYCLOAK_VERSION}@\${KEYCLOAK_DIGEST}
`;
const CONSISTENT = {
  "keycloak/provider/gradle.properties": "keycloakVersion=26.8.2\nkeycloakJavaRelease=21\n",
  "keycloak/Dockerfile": DOCKERFILE,
};

function withRepo(files, run) {
  const root = mkdtempSync(join(tmpdir(), "check-keycloak-version-"));
  try {
    for (const [path, content] of Object.entries(files)) {
      mkdirSync(dirname(join(root, path)), { recursive: true });
      writeFileSync(join(root, path), content);
    }
    return run(root);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

test("meldt niets als de Dockerfile dezelfde Keycloak-versie heeft als de provider-build en op digest staat", () => {
  const result = withRepo(CONSISTENT, checkKeycloakVersion);
  assert.equal(result.version, "26.8.2");
  assert.deepEqual(result.mismatches, []);
});

test("meldt een andere versie in de Dockerfile dan in de provider-build", () => {
  const result = withRepo(
    { ...CONSISTENT, "keycloak/Dockerfile": DOCKERFILE.replace("KEYCLOAK_VERSION=26.8.2", "KEYCLOAK_VERSION=26.8.3") },
    checkKeycloakVersion,
  );
  assert.equal(result.mismatches.length, 1);
  assert.match(result.mismatches[0], /26\.8\.3.*26\.8\.2/);
});

test("meldt een basisimage dat op een tag staat in plaats van op een digest", () => {
  const result = withRepo(
    { ...CONSISTENT, "keycloak/Dockerfile": DOCKERFILE.replace("FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}@${KEYCLOAK_DIGEST} AS builder", "FROM quay.io/keycloak/keycloak:26.8.2 AS builder") },
    checkKeycloakVersion,
  );
  assert.equal(result.mismatches.length, 1);
  assert.match(result.mismatches[0], /digest/);
});

test("meldt een KEYCLOAK_DIGEST die geen sha256-digest is", () => {
  const result = withRepo(
    { ...CONSISTENT, "keycloak/Dockerfile": DOCKERFILE.replace(DIGEST, "latest") },
    checkKeycloakVersion,
  );
  assert.equal(result.mismatches.length, 1);
  assert.match(result.mismatches[0], /KEYCLOAK_DIGEST/);
});

test("faalt als keycloakVersion of de Dockerfile-versie ontbreekt", () => {
  assert.throws(
    () => withRepo({ ...CONSISTENT, "keycloak/provider/gradle.properties": "x=1\n" }, checkKeycloakVersion),
    /keycloakVersion/,
  );
  assert.throws(
    () => withRepo({ ...CONSISTENT, "keycloak/Dockerfile": "FROM scratch\n" }, checkKeycloakVersion),
    /KEYCLOAK_VERSION/,
  );
});
