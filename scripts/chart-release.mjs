#!/usr/bin/env node
// CLI voor .github/workflows/publish-helm-chart.yml. Twee subcommando's:
//   decide  leest EVENT (tag|push), TAG_VERSION, OCI_EXISTS, TAG_EXISTS, IMAGES_EXIST (true|false) en Chart.yaml; schrijft
//           publish=, createTag=, version=, appVersion= naar stdout (key=value, geschikt voor $GITHUB_OUTPUT)
//   notes   leest VERSION, APP_VERSION, REPOSITORY, REF, DIGEST en helm/nl-portal/CHANGELOG.md; schrijft de release notes
import { readFileSync } from "node:fs";
import { chartField, chartChangesSection, decideRelease, renderReleaseNotes } from "./lib/chart-release.mjs";

const CHART_DIR = process.env.CHART_DIR ?? "helm/nl-portal";
const env = (name) => {
  const value = process.env[name];
  if (value === undefined || value === "") throw new Error(`Omgevingsvariabele ${name} ontbreekt.`);
  return value;
};

try {
  const command = process.argv[2];
  if (command === "decide") {
    const chartYaml = readFileSync(`${CHART_DIR}/Chart.yaml`, "utf8");
    const chartVersion = chartField(chartYaml, "version");
    const decision = decideRelease({
      event: env("EVENT"),
      tagVersion: process.env.TAG_VERSION,
      chartVersion,
      ociExists: env("OCI_EXISTS") === "true",
      tagExists: env("TAG_EXISTS") === "true",
      imagesExist: env("IMAGES_EXIST") === "true",
    });
    console.error(decision.reason);
    console.log(`publish=${decision.publish}`);
    console.log(`createTag=${decision.createTag}`);
    console.log(`version=${chartVersion}`);
    console.log(`appVersion=${chartField(chartYaml, "appVersion")}`);
  } else if (command === "notes") {
    const version = env("VERSION");
    const changes = chartChangesSection(readFileSync(`${CHART_DIR}/CHANGELOG.md`, "utf8"), version);
    process.stdout.write(
      renderReleaseNotes({ version, appVersion: env("APP_VERSION"), repository: env("REPOSITORY"), ref: env("REF"), digest: env("DIGEST"), changes }),
    );
  } else {
    throw new Error("Gebruik: node scripts/chart-release.mjs decide|notes");
  }
} catch (error) {
  console.error(`::error::${error.message}`);
  process.exit(1);
}
