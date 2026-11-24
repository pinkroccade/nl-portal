// Pure tekstbewerking op helm/nl-portal/Chart.yaml voor `mise run release:cut-rc`.
// Bewust geen YAML-parser: alleen de regels `version`, `appVersion` en de image-tags in de
// `artifacthub.io/images`-annotatie veranderen; commentaar en opmaak blijven staan.

const SEMVER = /^(\d+)\.(\d+)\.(\d+)$/;

export function bumpChartMinor(version) {
  const m = SEMVER.exec(version);
  if (!m) throw new Error(`"${version}" is geen geldige SemVer-versie (X.Y.Z).`);
  return `${m[1]}.${Number(m[2]) + 1}.0`;
}

export function updateChartForRelease(text, appVersion) {
  const versionLine = /^version: *"?([^"\s]+)"?[ \t]*$/m.exec(text);
  if (!versionLine) throw new Error("Chart.yaml heeft geen regel `version:`.");
  if (!/^appVersion: /m.test(text)) throw new Error("Chart.yaml heeft geen regel `appVersion:`.");
  const chartVersion = bumpChartMinor(versionLine[1]);
  const updated = text
    .replace(/^version: .*$/m, `version: ${chartVersion}`)
    .replace(/^appVersion: .*$/m, `appVersion: "${appVersion}"`)
    .replace(/^(\s+image: ghcr\.io\/pinkroccade\/nl-portal-(?:backend|frontend)):\S+$/gm, `$1:${appVersion}`);
  return { text: updated, chartVersion };
}
