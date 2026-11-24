// Pure logica achter .github/workflows/publish-helm-chart.yml: wanneer publiceren we, en hoe zien de release notes
// eruit. Geen I/O; chart-release.mjs (CLI) leest bestanden en schrijft naar stdout.

const REGISTRY_CHART = "ghcr.io/pinkroccade/charts/nl-portal";
const DOCS_URL = "https://pinkroccade.github.io/nl-portal/hosting-en-configuratie/kubernetes/how-to-handtekening-verifieren";
const OIDC_ISSUER = "https://token.actions.githubusercontent.com";

export function chartField(chartYaml, key) {
  const match = chartYaml.match(new RegExp(`^${key}: *(.+?) *$`, "m"));
  return match ? match[1].replace(/^["']|["']$/g, "") : null;
}

// De chart-CHANGELOG kent koppen als `## 0.1.1`.
export function chartChangesSection(changelog, version) {
  const lines = changelog.split("\n");
  const start = lines.findIndex((line) => line.trim() === `## ${version}`);
  if (start === -1) return null;
  const rest = lines.slice(start + 1);
  const end = rest.findIndex((line) => line.startsWith("## "));
  return (end === -1 ? rest : rest.slice(0, end)).join("\n").trim();
}

// event "tag": de run is gestart door een chart-tag; "push": door een push naar main of release/*.
export function decideRelease({ event, tagVersion, chartVersion, ociExists, tagExists, imagesExist }) {
  if (event === "tag" && tagVersion !== chartVersion) {
    throw new Error(`Tag wijst naar chartversie ${tagVersion}, maar Chart.yaml heeft versie ${chartVersion}.`);
  }
  if (ociExists) {
    return { publish: false, createTag: false, reason: `Chart ${chartVersion} bestaat al in het register; niets te publiceren.` };
  }
  if (!imagesExist) {
    const reason = "De images van appVersion bestaan nog niet; publiceer eerst de app-release (die tagt de chart daarna).";
    if (event === "tag") throw new Error(reason);
    return { publish: false, createTag: false, reason };
  }
  if (event === "push" && tagExists) {
    return {
      publish: false,
      createTag: false,
      reason: `Tag chart-nl-portal-${chartVersion} bestaat al; de chart wordt via die tag gepubliceerd.`,
    };
  }
  return { publish: true, createTag: event === "push", reason: `Chart ${chartVersion} wordt gepubliceerd.` };
}

export function renderReleaseNotes({ version, appVersion, repository, ref, digest, changes }) {
  const identity = `https://github.com/${repository}/.github/workflows/publish-helm-chart.yml@${ref}`;
  return [
    `Helm-chart \`nl-portal\` ${version} (appVersion ${appVersion}).`,
    "",
    "## Wijzigingen",
    "",
    changes ?? "Geen changelog-sectie voor deze versie gevonden in helm/nl-portal/CHANGELOG.md.",
    "",
    "## Installeren",
    "",
    "```shell",
    `helm install nl-portal oci://${REGISTRY_CHART} --version ${version}`,
    "```",
    "",
    "## Handtekening verifiëren",
    "",
    "De chart is keyless gesigneerd met cosign (GitHub OIDC). Controleer de handtekening vóór je uitrolt:",
    "",
    "```shell",
    `cosign verify ${REGISTRY_CHART}@${digest} \\`,
    `  --certificate-identity ${identity} \\`,
    `  --certificate-oidc-issuer ${OIDC_ISSUER}`,
    "```",
    "",
    `Meer uitleg: [de cosign-handtekening verifiëren](${DOCS_URL}).`,
    "",
  ].join("\n");
}
