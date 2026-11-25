# Referentie: releasen

*Diátaxis: referentie.* Workflows, invoer, tags, artefacten en handtekeningen van het releaseproces. Uitleg: [hoe releasen werkt](./release-uitleg.md);
stappen: [een release maken](./release.md).

## Workflows

| Workflow | Start | Doet |
|---|---|---|
| `build-once.yml` | push op `main` (niet bij alleen `site/**`, `docs/**` of `*.md`) | Bouwt één keer, draait de poorten, pusht en ondertekent `<versie>-<sha>` |
| `promote.yml` | handmatig, alleen vanaf `main`, achter de `release`-environment | Retagt een geslaagde build tot release, ondertekent, attesteert en maakt de GitHub Release |

Poorten en volgorde van `build-once.yml`: stage backend en frontend (parallel: compileren, unit- en integratietests, SBOM-bundel), smoke
(quickstart-compose, BFF-login en GraphQL), images (parallel: bouwen, pushen, ondertekenen, SBOM attesteren), summary.

Proefrun van de poorten op een branch: `gh workflow run build-once.yml --ref <branch>` (zonder `-f publish=true` wordt niets gepusht).

## Invoer van `promote.yml`

| Invoer | Standaard | Betekenis |
|---|---|---|
| `build` | leeg: nieuwste geslaagde push-run van `build-once.yml` op `main` | Commit-sha (kort of volledig, minstens 7 tekens) of build-tag `<versie>-<sha>` |
| `version` | de versie uit de build-tag | Release-versie `x.y.z`. De versie in de build-tag komt uit `version.txt`; geef haar expliciet mee zolang die niet per release oploopt |

## Tags per image

Voor `ghcr.io/pinkroccade/nl-portal-backend` en `ghcr.io/pinkroccade/nl-portal-frontend`:

| Tag | Gezet door | Beweegt |
|---|---|---|
| `<versie>-<sha>` | `build-once.yml` | nooit |
| `x.y.z` | `promote.yml` | nooit; een andere digest wordt geweigerd |
| `x.y` | `promote.yml` | alleen naar een versie die niet lager is dan de hoogste in die minor |
| `x` | `promote.yml` | alleen naar een versie die niet lager is dan de hoogste in die major |
| `latest` | `promote.yml` | alleen naar een versie die niet lager is dan de hoogste release |

De regel staat in `scripts/promote-tags.mjs` (tests: `scripts/promote-tags.test.mjs`). De hoogste bestaande versie komt uit de git-tags `x.y.z`.
`promote.yml` zet de git-tag vóór het retaggen, zodat die toestand ook klopt als een run halverwege afbreekt.

## Artefacten

| Artefact | Waar | Inhoud | Bewaartermijn |
|---|---|---|---|
| `build-digests` | run van `build-once.yml` | `build-digests.json`: build-tag, commit, digest backend en frontend | standaard 90 dagen van GitHub-artefacten; een oudere build is daarna niet via `promote.yml` te promoveren |
| GitHub Release | releases van de repository | notes, image-SBOM's per image en, als het artefact nog bestaat, backend-SBOM en licentierapport | permanent |

De notes komen uit `CHANGELOG.md` als daar een sectie voor de versie staat, anders genereert GitHub ze.

## Handtekeningen en attestaties

Keyless cosign via GitHub OIDC; issuer `https://token.actions.githubusercontent.com`. Elke digest heeft er twee:

| Gezet door | Certificaat-identiteit |
|---|---|
| Build | `https://github.com/pinkroccade/nl-portal/.github/workflows/_build-app-image.yml@refs/heads/main` |
| Promotie | `https://github.com/pinkroccade/nl-portal/.github/workflows/promote.yml@refs/heads/main` |

Beide digests hebben ook een CycloneDX-SBOM-attestatie (`cosign verify-attestation --type cyclonedx`). Verifieer met `--certificate-identity` en
`--certificate-oidc-issuer`; zie [de how-to](./release.md) voor een compleet commando.

## Rechten en geheimen

- `promote.yml` heeft `packages: write` (tags), `id-token: write` (handtekening) en haalt een token van de GitHub App (`NL_PORTAL_APP_*`) met `contents: write` voor de git-tag en de Release.
- De `release`-environment bepaalt wie mag goedkeuren.
- Er is één `promote`-concurrencygroep: promoties lopen na elkaar. Een derde dispatch vervangt een tweede die nog op goedkeuring wacht.
