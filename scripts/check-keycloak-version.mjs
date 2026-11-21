#!/usr/bin/env node
// Guard: keycloakVersion in keycloak/provider/gradle.properties is de Keycloak-versie waartegen de provider wordt
// gecompileerd; keycloak/Dockerfile moet dezelfde versie als basisimage gebruiken, vastgepind op digest.
// Draai via `mise run keycloak:check-version`.
// Usage: node scripts/check-keycloak-version.mjs [--root <repo-root>]

import { readFileSync } from "node:fs";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const read = (root, path) => readFileSync(join(root, path), "utf8");

export function checkKeycloakVersion(root) {
  const version = /^keycloakVersion=(\S+)\s*$/m.exec(read(root, "keycloak/provider/gradle.properties"))?.[1];
  if (!version) throw new Error("keycloakVersion ontbreekt in keycloak/provider/gradle.properties");

  const dockerfile = read(root, "keycloak/Dockerfile");
  const dockerVersion = /^ARG KEYCLOAK_VERSION=(\S+)\s*$/m.exec(dockerfile)?.[1];
  if (!dockerVersion) throw new Error("ARG KEYCLOAK_VERSION ontbreekt in keycloak/Dockerfile");

  const mismatches = [];
  if (dockerVersion !== version) {
    mismatches.push(
      `keycloak/Dockerfile: KEYCLOAK_VERSION is ${dockerVersion}, keycloakVersion in keycloak/provider/gradle.properties is ${version}`,
    );
  }
  const digest = /^ARG KEYCLOAK_DIGEST=(\S+)\s*$/m.exec(dockerfile)?.[1];
  if (!digest || !/^sha256:[0-9a-f]{64}$/.test(digest)) {
    mismatches.push(`keycloak/Dockerfile: KEYCLOAK_DIGEST is geen sha256-digest (${digest ?? "ontbreekt"})`);
  }
  for (const line of dockerfile.split(/\r?\n/)) {
    if (/^FROM\s+quay\.io\/keycloak\/keycloak:/.test(line) && !/@\$\{KEYCLOAK_DIGEST\}/.test(line)) {
      mismatches.push(`keycloak/Dockerfile: het basisimage staat niet op digest (${line.trim()})`);
    }
  }
  return { version, mismatches };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const rootIndex = process.argv.indexOf("--root");
  const root = rootIndex >= 0 ? resolve(process.argv[rootIndex + 1]) : resolve(fileURLToPath(new URL("..", import.meta.url)));
  try {
    const { version, mismatches } = checkKeycloakVersion(root);
    if (mismatches.length > 0) {
      console.error(`Keycloak-versie loopt uiteen (verwacht ${version}):\n- ${mismatches.join("\n- ")}`);
      process.exit(1);
    }
    console.log(`Keycloak-versie consistent: ${version}`);
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}
